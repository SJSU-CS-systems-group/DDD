package org.thoughtcrime.securesms.jobs;

import android.graphics.Bitmap;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.blurhash.BlurHashEncoder;
import org.thoughtcrime.securesms.database.AttachmentTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.PartProgressEvent;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.NotificationController;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResumableUploadResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.ResumeLocationInvalidException;
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Uploads an attachment without alteration.
 * <p>
 * Queue {@link AttachmentCompressionJob} before to compress.
 */
public final class AttachmentUploadJob extends BaseJob {

  public static final String KEY = "AttachmentUploadJobV2";

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(AttachmentUploadJob.class);

  private static final long UPLOAD_REUSE_THRESHOLD = TimeUnit.DAYS.toMillis(3);

  private static final String KEY_ROW_ID      = "row_id";
  private static final String KEY_UNIQUE_ID   = "unique_id";
  private static final String KEY_FORCE_V2    = "force_v2";

  /**
   * Foreground notification shows while uploading attachments above this.
   */
  private static final int FOREGROUND_LIMIT = 10 * 1024 * 1024;

  private final AttachmentId attachmentId;

  private boolean forceV2;

  public AttachmentUploadJob(AttachmentId attachmentId) {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .setMaxAttempts(Parameters.UNLIMITED)
                           .build(),
         attachmentId,
         false);
  }

  private AttachmentUploadJob(@NonNull Job.Parameters parameters, @NonNull AttachmentId attachmentId, boolean forceV2) {
    super(parameters);
    this.attachmentId = attachmentId;
    this.forceV2      = forceV2;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_ROW_ID, attachmentId.getRowId())
                             .putLong(KEY_UNIQUE_ID, attachmentId.getUniqueId())
                             .putBoolean(KEY_FORCE_V2, forceV2)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected boolean shouldTrace() {
    return true;
  }

  @Override
  public void onRun() throws Exception {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    Data inputData = getInputData();

    ResumableUploadSpec resumableUploadSpec;

    if (forceV2) {
      Log.d(TAG, "Forcing utilization of V2");
      resumableUploadSpec = null;
    } else if (inputData != null && inputData.hasString(ResumableUploadSpecJob.KEY_RESUME_SPEC)) {
      Log.d(TAG, "Using attachments V3");
      resumableUploadSpec = ResumableUploadSpec.deserialize(inputData.getString(ResumableUploadSpecJob.KEY_RESUME_SPEC));
    } else {
      Log.d(TAG, "Using attachments V2");
      resumableUploadSpec = null;
    }

    SignalServiceMessageSender messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
    AttachmentTable            database           = SignalDatabase.attachments();
    DatabaseAttachment         databaseAttachment = database.getAttachment(attachmentId);

    if (databaseAttachment == null) {
      throw new InvalidAttachmentException("Cannot find the specified attachment.");
    }

    long timeSinceUpload = System.currentTimeMillis() - databaseAttachment.getUploadTimestamp();
    if (timeSinceUpload < UPLOAD_REUSE_THRESHOLD && !TextUtils.isEmpty(databaseAttachment.getLocation())) {
      Log.i(TAG, "We can re-use an already-uploaded file. It was uploaded " + timeSinceUpload + " ms ago. Skipping.");
      return;
    } else if (databaseAttachment.getUploadTimestamp() > 0) {
      Log.i(TAG, "This file was previously-uploaded, but too long ago to be re-used. Age: " + timeSinceUpload + " ms");
    }

    Log.i(TAG, "Uploading attachment for message " + databaseAttachment.getMmsId() + " with ID " + databaseAttachment.getAttachmentId());

    try (NotificationController notification = getNotificationForAttachment(databaseAttachment)) {
      try (SignalServiceAttachmentStream localAttachment = getAttachmentFor(databaseAttachment, notification, resumableUploadSpec)) {
        SignalServiceAttachmentPointer remoteAttachment = messageSender.uploadAttachment(localAttachment);
        Attachment                     attachment       = PointerAttachment.forPointer(Optional.of(remoteAttachment), null, databaseAttachment.getFastPreflightId()).get();

        database.updateAttachmentAfterUpload(databaseAttachment.getAttachmentId(), attachment, remoteAttachment.getUploadTimestamp());
      }
    } catch (NonSuccessfulResumableUploadResponseCodeException e) {
      if (e.getCode() == 400) {
        Log.w(TAG, "Failed to upload due to a 400 when getting resumable upload information. Downgrading to attachments v2", e);
        forceV2 = true;
      }
    }
  }

  private @Nullable NotificationController getNotificationForAttachment(@NonNull Attachment attachment) {
    if (attachment.getSize() >= FOREGROUND_LIMIT) {
      try {
        return ForegroundServiceUtil.startGenericTaskWhenCapable(context, context.getString(R.string.AttachmentUploadJob_uploading_media));
      } catch (UnableToStartException e) {
        Log.w(TAG, "Unable to start foreground service", e);
        return null;
      }
    } else {
      return null;
    }
  }

  @Override
  public void onFailure() {
    if (isCanceled()) {
      SignalDatabase.attachments().deleteAttachment(attachmentId);
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ResumeLocationInvalidException) return false;

    return exception instanceof IOException && !(exception instanceof NotPushRegisteredException);
  }

  private @NonNull SignalServiceAttachmentStream getAttachmentFor(Attachment attachment, @Nullable NotificationController notification, @Nullable ResumableUploadSpec resumableUploadSpec) throws InvalidAttachmentException {
    if (attachment.getUri() == null || attachment.getSize() == 0) {
      throw new InvalidAttachmentException(new IOException("Assertion failed, outgoing attachment has no data!"));
    }

    try {
      InputStream is = PartAuthority.getAttachmentStream(context, attachment.getUri());
      SignalServiceAttachment.Builder builder = SignalServiceAttachment.newStreamBuilder()
                                                                       .withStream(is)
                                                                       .withContentType(attachment.getContentType())
                                                                       .withLength(attachment.getSize())
                                                                       .withFileName(attachment.getFileName())
                                                                       .withVoiceNote(attachment.isVoiceNote())
                                                                       .withBorderless(attachment.isBorderless())
                                                                       .withGif(attachment.isVideoGif())
                                                                       .withWidth(attachment.getWidth())
                                                                       .withHeight(attachment.getHeight())
                                                                       .withUploadTimestamp(System.currentTimeMillis())
                                                                       .withCaption(attachment.getCaption())
                                                                       .withCancelationSignal(this::isCanceled)
                                                                       .withResumableUploadSpec(resumableUploadSpec)
                                                                       .withListener((total, progress) -> {
                                                                         EventBus.getDefault().postSticky(new PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress));
                                                                         if (notification != null) {
                                                                           notification.setProgress(total, progress);
                                                                         }
                                                                       });
      if (MediaUtil.isImageType(attachment.getContentType())) {
        return builder.withBlurHash(getImageBlurHash(attachment)).build();
      } else if (MediaUtil.isVideoType(attachment.getContentType())) {
        return builder.withBlurHash(getVideoBlurHash(attachment)).build();
      } else {
        return builder.build();
      }
    } catch (IOException ioe) {
      throw new InvalidAttachmentException(ioe);
    }
  }

  private @Nullable String getImageBlurHash(@NonNull Attachment attachment) throws IOException {
    if (attachment.getBlurHash() != null) return attachment.getBlurHash().getHash();
    if (attachment.getUri() == null) return null;

    try (InputStream inputStream = PartAuthority.getAttachmentStream(context, attachment.getUri())) {
      return BlurHashEncoder.encode(inputStream);
    }
  }

  private @Nullable String getVideoBlurHash(@NonNull Attachment attachment) throws IOException {
    if (attachment.getBlurHash() != null) {
      return attachment.getBlurHash().getHash();
    }

    if (Build.VERSION.SDK_INT < 23) {
      Log.w(TAG, "Video thumbnails not supported...");
      return null;
    }

    Bitmap bitmap = MediaUtil.getVideoThumbnail(context, Objects.requireNonNull(attachment.getUri()), 1000);

    if (bitmap != null) {
      Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 100, 100, false);
      bitmap.recycle();

      Log.i(TAG, "Generated video thumbnail...");
      String hash = BlurHashEncoder.encode(thumb);
      thumb.recycle();

      return hash;
    } else {
      return null;
    }
  }

  private class InvalidAttachmentException extends Exception {
    InvalidAttachmentException(String message) {
      super(message);
    }

    InvalidAttachmentException(Exception e) {
      super(e);
    }
  }

  public static final class Factory implements Job.Factory<AttachmentUploadJob> {
    @Override
    public @NonNull AttachmentUploadJob create(@NonNull Parameters parameters, @NonNull org.thoughtcrime.securesms.jobmanager.Data data) {
      return new AttachmentUploadJob(parameters, new AttachmentId(data.getLong(KEY_ROW_ID), data.getLong(KEY_UNIQUE_ID)), data.getBooleanOrDefault(KEY_FORCE_V2, false));
    }
  }
}
