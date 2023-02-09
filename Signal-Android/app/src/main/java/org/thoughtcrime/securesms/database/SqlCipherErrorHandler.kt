package org.thoughtcrime.securesms.database

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.signal.core.util.CursorUtil
import org.signal.core.util.ExceptionUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * The default error handler wipes the file. This one instead prints some diagnostics and then crashes so the original corrupt file isn't lost.
 */
class SqlCipherErrorHandler(private val databaseName: String) : DatabaseErrorHandler {

  override fun onCorruption(db: SQLiteDatabase) {
    val output = StringBuilder()
    output.append("Database '$databaseName' corrupted! Going to try to run some diagnostics.\n")

    val result: DiagnosticResults = runDiagnostics(ApplicationDependencies.getApplication(), db)
    var lines: List<String> = result.logs.split("\n")
    lines = listOf("Database '$databaseName' corrupted!. Diagnostics results:\n") + lines

    Log.e(TAG, "Database '$databaseName' corrupted!. Diagnostics results:\n ${result.logs}")

    if (result is DiagnosticResults.Success) {
      if (result.pragma1Passes && result.pragma2Passes) {
        throw DatabaseCorruptedError_BothChecksPass(lines)
      } else if (!result.pragma1Passes && result.pragma2Passes) {
        throw DatabaseCorruptedError_NormalCheckFailsCipherCheckPasses(lines)
      } else if (result.pragma1Passes && !result.pragma2Passes) {
        throw DatabaseCorruptedError_NormalCheckPassesCipherCheckFails(lines)
      } else {
        throw DatabaseCorruptedError_BothChecksFail(lines)
      }
    } else {
      throw DatabaseCorruptedError_FailedToRunChecks(lines)
    }
  }

  /**
   * Try running diagnostics on the current database instance. If that fails, try opening a new connection and running the diagnostics there.
   */
  private fun runDiagnostics(context: Context, db: SQLiteDatabase): DiagnosticResults {
    val sameConnectionResult: DiagnosticResults = runDiagnosticsMethod("same-connection", queryDatabase(db))

    if (sameConnectionResult is DiagnosticResults.Success) {
      return sameConnectionResult
    }

    val differentConnectionResult: AtomicReference<DiagnosticResults> = AtomicReference()
    val databaseFile = context.getDatabasePath(databaseName)
    val latch = CountDownLatch(1)

    try {
      SQLiteDatabase.openOrCreateDatabase(
        databaseFile.absolutePath, DatabaseSecretProvider.getOrCreateDatabaseSecret(context).asString(), null, null,
        object : SQLiteDatabaseHook {
          override fun preKey(connection: SQLiteConnection) {}
          override fun postKey(connection: SQLiteConnection) {
            if (latch.count > 0) {
              val result: DiagnosticResults = runDiagnosticsMethod("different-connection") { query -> connection.executeForString(query, null, null) }
              differentConnectionResult.set(result)
              latch.countDown()
            }
          }
        }
      )
    } catch (t: Throwable) {
      return DiagnosticResults.Failure(ExceptionUtil.convertThrowableToString(t))
    }

    latch.await()

    return differentConnectionResult.get()!!
  }

  /**
   * Do two different integrity checks and return the results.
   */
  private fun runDiagnosticsMethod(descriptor: String, query: (String) -> String): DiagnosticResults {
    val output = StringBuilder()
    var pragma1Passes = false
    var pragma2Passes = true

    output.append(" ===== PRAGMA integrity_check ($descriptor) =====\n")
    try {
      val results = query("PRAGMA integrity_check")
      output.append(results)
      if (results.lowercase().contains("ok")) {
        pragma1Passes = true
      }
    } catch (t: Throwable) {
      output.append("Failed to do integrity_check!\n").append(ExceptionUtil.convertThrowableToString(t))
      return DiagnosticResults.Failure(output.toString())
    }

    output.append("\n").append("===== PRAGMA cipher_integrity_check ($descriptor) =====\n")
    try {
      val results = query("PRAGMA cipher_integrity_check")
      output.append(results)
      if (results.trim().isNotEmpty()) {
        pragma2Passes = false
      }
    } catch (t: Throwable) {
      output.append("Failed to do cipher_integrity_check!\n")
        .append(ExceptionUtil.convertThrowableToString(t))
      return DiagnosticResults.Failure(output.toString())
    }

    return DiagnosticResults.Success(
      pragma1Passes = pragma1Passes,
      pragma2Passes = pragma2Passes,
      logs = output.toString()
    )
  }

  private fun queryDatabase(db: SQLiteDatabase): (String) -> String {
    return { query ->
      val output = StringBuilder()

      db.rawQuery(query, null).use { cursor ->
        while (cursor.moveToNext()) {
          val row = CursorUtil.readRowAsString(cursor)
          output.append(row).append("\n")
        }
      }

      output.toString()
    }
  }

  private sealed class DiagnosticResults(val logs: String) {
    class Success(
      val pragma1Passes: Boolean,
      val pragma2Passes: Boolean,
      logs: String
    ) : DiagnosticResults(logs)

    class Failure(logs: String) : DiagnosticResults(logs)
  }

  private open class CustomTraceError constructor(lines: List<String>) : Error() {
    init {
      val custom: Array<StackTraceElement> = lines.map { line -> StackTraceElement(line, "", "", 0) }.toTypedArray()
      stackTrace = ExceptionUtil.joinStackTrace(stackTrace, custom)
    }
  }

  private class DatabaseCorruptedError_BothChecksPass constructor(lines: List<String>) : CustomTraceError(lines)
  private class DatabaseCorruptedError_BothChecksFail constructor(lines: List<String>) : CustomTraceError(lines)
  private class DatabaseCorruptedError_NormalCheckFailsCipherCheckPasses constructor(lines: List<String>) : CustomTraceError(lines)
  private class DatabaseCorruptedError_NormalCheckPassesCipherCheckFails constructor(lines: List<String>) : CustomTraceError(lines)
  private class DatabaseCorruptedError_FailedToRunChecks constructor(lines: List<String>) : CustomTraceError(lines)

  companion object {
    private val TAG = Log.tag(SqlCipherErrorHandler::class.java)
  }
}
