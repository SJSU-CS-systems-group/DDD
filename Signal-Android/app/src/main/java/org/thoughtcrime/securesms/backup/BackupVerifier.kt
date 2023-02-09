package org.thoughtcrime.securesms.backup

import org.greenrobot.eventbus.EventBus
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.BackupProtos.BackupFrame
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Given a backup file, run over it and verify it will decrypt properly when attempting to import it.
 */
object BackupVerifier {

  private val TAG = Log.tag(BackupVerifier::class.java)

  @JvmStatic
  @Throws(IOException::class, FullBackupExporter.BackupCanceledException::class)
  fun verifyFile(cipherStream: InputStream, passphrase: String, expectedCount: Long, cancellationSignal: FullBackupExporter.BackupCancellationSignal): Boolean {
    val inputStream = BackupRecordInputStream(cipherStream, passphrase)

    var count = 0L
    var frame: BackupFrame = inputStream.readFrame()

    cipherStream.use {
      while (!frame.end && !cancellationSignal.isCanceled) {
        val verified = when {
          frame.hasAttachment() -> verifyAttachment(frame.attachment, inputStream)
          frame.hasSticker() -> verifySticker(frame.sticker, inputStream)
          frame.hasAvatar() -> verifyAvatar(frame.avatar, inputStream)
          else -> true
        }

        if (!verified) {
          return false
        }

        EventBus.getDefault().post(BackupEvent(BackupEvent.Type.PROGRESS_VERIFYING, ++count, expectedCount))

        frame = inputStream.readFrame()
      }
    }

    if (cancellationSignal.isCanceled) {
      throw FullBackupExporter.BackupCanceledException()
    }

    return true
  }

  private fun verifyAttachment(attachment: BackupProtos.Attachment, inputStream: BackupRecordInputStream): Boolean {
    try {
      inputStream.readAttachmentTo(NullOutputStream, attachment.length)
    } catch (e: IOException) {
      Log.w(TAG, "Bad attachment id: ${attachment.attachmentId} len: ${attachment.length}", e)
      return false
    }

    return true
  }

  private fun verifySticker(sticker: BackupProtos.Sticker, inputStream: BackupRecordInputStream): Boolean {
    try {
      inputStream.readAttachmentTo(NullOutputStream, sticker.length)
    } catch (e: IOException) {
      Log.w(TAG, "Bad sticker id: ${sticker.rowId} len: ${sticker.length}", e)
      return false
    }
    return true
  }

  private fun verifyAvatar(avatar: BackupProtos.Avatar, inputStream: BackupRecordInputStream): Boolean {
    try {
      inputStream.readAttachmentTo(NullOutputStream, avatar.length)
    } catch (e: IOException) {
      Log.w(TAG, "Bad avatar id: ${avatar.recipientId} len: ${avatar.length}", e)
      return false
    }
    return true
  }

  private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray?) = Unit
    override fun write(b: ByteArray?, off: Int, len: Int) = Unit
  }
}
