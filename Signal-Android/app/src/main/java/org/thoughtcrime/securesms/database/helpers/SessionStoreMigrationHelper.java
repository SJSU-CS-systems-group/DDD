package org.thoughtcrime.securesms.database.helpers;


import android.content.ContentValues;
import android.content.Context;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.signal.core.util.Conversions;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.thoughtcrime.securesms.database.SessionTable;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class SessionStoreMigrationHelper {

  private static final String TAG = Log.tag(SessionStoreMigrationHelper.class);

  private static final String SESSIONS_DIRECTORY_V2 = "sessions-v2";
  private static final Object FILE_LOCK             = new Object();

  private static final int SINGLE_STATE_VERSION   = 1;
  private static final int ARCHIVE_STATES_VERSION = 2;
  private static final int PLAINTEXT_VERSION      = 3;
  private static final int CURRENT_VERSION        = 3;

  public static void migrateSessions(Context context, SQLiteDatabase database) {
    File directory = new File(context.getFilesDir(), SESSIONS_DIRECTORY_V2);

    if (directory.exists()) {
      File[] sessionFiles = directory.listFiles();

      if (sessionFiles != null) {
        for (File sessionFile : sessionFiles) {
          try {
            String[] parts   = sessionFile.getName().split("[.]");
            String   address = parts[0];

            int deviceId;

            if (parts.length > 1) deviceId = Integer.parseInt(parts[1]);
            else                  deviceId = SignalServiceAddress.DEFAULT_DEVICE_ID;

            FileInputStream in            = new FileInputStream(sessionFile);
            int             versionMarker = readInteger(in);

            if (versionMarker > CURRENT_VERSION) {
              throw new AssertionError("Unknown version: " + versionMarker + ", " + sessionFile.getAbsolutePath());
            }

            byte[] serialized = readBlob(in);
            in.close();

            if (versionMarker < PLAINTEXT_VERSION) {
              throw new AssertionError("Not plaintext: " + versionMarker + ", " + sessionFile.getAbsolutePath());
            }

            SessionRecord sessionRecord;

            if (versionMarker == SINGLE_STATE_VERSION) {
              Log.i(TAG, "Migrating single state version: " + sessionFile.getAbsolutePath());
              sessionRecord = new SessionRecord(serialized);
            } else if (versionMarker >= ARCHIVE_STATES_VERSION) {
              Log.i(TAG, "Migrating session: " + sessionFile.getAbsolutePath());
              sessionRecord = new SessionRecord(serialized);
            } else {
              throw new AssertionError("Unknown version: " + versionMarker + ", " + sessionFile.getAbsolutePath());
            }


            ContentValues contentValues = new ContentValues();
            contentValues.put(SessionTable.ADDRESS, address);
            contentValues.put(SessionTable.DEVICE, deviceId);
            contentValues.put(SessionTable.RECORD, sessionRecord.serialize());

            database.insert(SessionTable.TABLE_NAME, null, contentValues);
          } catch (NumberFormatException | IOException | InvalidMessageException e) {
            Log.w(TAG, e);
          }
        }
      }
    }
  }

  private static byte[] readBlob(FileInputStream in) throws IOException {
    int length       = readInteger(in);
    byte[] blobBytes = new byte[length];

    in.read(blobBytes, 0, blobBytes.length);
    return blobBytes;
  }

  private static int readInteger(FileInputStream in) throws IOException {
    byte[] integer = new byte[4];
    in.read(integer, 0, integer.length);
    return Conversions.byteArrayToInt(integer);
  }

}
