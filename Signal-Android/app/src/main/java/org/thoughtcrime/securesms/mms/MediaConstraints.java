package org.thoughtcrime.securesms.mms;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.MemoryFileDescriptor;

import java.io.IOException;
import java.io.InputStream;

public abstract class MediaConstraints {
  private static final String TAG = Log.tag(MediaConstraints.class);

  public static MediaConstraints getPushMediaConstraints() {
    return getPushMediaConstraints(null);
  }

  public static MediaConstraints getPushMediaConstraints(@Nullable SentMediaQuality sentMediaQuality) {
    return new PushMediaConstraints(sentMediaQuality);
  }

  public static MediaConstraints getMmsMediaConstraints(int subscriptionId) {
    return new MmsMediaConstraints(subscriptionId);
  }

  public abstract int getImageMaxWidth(Context context);
  public abstract int getImageMaxHeight(Context context);
  public abstract int getImageMaxSize(Context context);

  public boolean isHighQuality() {
    return false;
  }

  /**
   * Provide a list of dimensions that should be attempted during compression. We will keep moving
   * down the list until the image can be scaled to fit under {@link #getImageMaxSize(Context)}.
   * The first entry in the list should match your max width/height.
   */
  public abstract int[] getImageDimensionTargets(Context context);

  public abstract int getGifMaxSize(Context context);
  public abstract int getVideoMaxSize(Context context);

  public @IntRange(from = 0, to = 100) int getImageCompressionQualitySetting(@NonNull Context context) {
    return 70;
  }

  public int getUncompressedVideoMaxSize(Context context) {
    return getVideoMaxSize(context);
  }

  public int getCompressedVideoMaxSize(Context context) {
    return getVideoMaxSize(context);
  }

  public abstract int getAudioMaxSize(Context context);
  public abstract int getDocumentMaxSize(Context context);

  public boolean isSatisfied(@NonNull Context context, @NonNull Attachment attachment) {
    try {
      return (MediaUtil.isGif(attachment)    && attachment.getSize() <= getGifMaxSize(context)   && isWithinBounds(context, attachment.getUri())) ||
             (MediaUtil.isImage(attachment)  && attachment.getSize() <= getImageMaxSize(context) && isWithinBounds(context, attachment.getUri())) ||
             (MediaUtil.isAudio(attachment)  && attachment.getSize() <= getAudioMaxSize(context)) ||
             (MediaUtil.isVideo(attachment)  && attachment.getSize() <= getVideoMaxSize(context)) ||
             (MediaUtil.isFile(attachment) && attachment.getSize() <= getDocumentMaxSize(context));
    } catch (IOException ioe) {
      Log.w(TAG, "Failed to determine if media's constraints are satisfied.", ioe);
      return false;
    }
  }

  public boolean isSatisfied(@NonNull Context context, @NonNull Uri uri, @NonNull String contentType, long size) {
    try {
      return (MediaUtil.isGif(contentType)       && size <= getGifMaxSize(context) && isWithinBounds(context, uri))   ||
             (MediaUtil.isImageType(contentType) && size <= getImageMaxSize(context) && isWithinBounds(context, uri)) ||
             (MediaUtil.isAudioType(contentType) && size <= getAudioMaxSize(context))                                 ||
             (MediaUtil.isVideoType(contentType) && size <= getVideoMaxSize(context))                                 ||
             size <= getDocumentMaxSize(context);
    } catch (IOException ioe) {
      Log.w(TAG, "Failed to determine if media's constraints are satisfied.", ioe);
      return false;
    }
  }

  private boolean isWithinBounds(Context context, Uri uri) throws IOException {
    try {
      InputStream is = PartAuthority.getAttachmentStream(context, uri);
      Pair<Integer, Integer> dimensions = BitmapUtil.getDimensions(is);
      return dimensions.first  > 0 && dimensions.first  <= getImageMaxWidth(context) &&
             dimensions.second > 0 && dimensions.second <= getImageMaxHeight(context);
    } catch (BitmapDecodingException e) {
      throw new IOException(e);
    }
  }

  public boolean canResize(@NonNull Attachment attachment) {
    return MediaUtil.isImage(attachment) && !MediaUtil.isGif(attachment) ||
           MediaUtil.isVideo(attachment) && isVideoTranscodeAvailable();
  }

  public boolean canResize(@NonNull String mediaType) {
    return MediaUtil.isImageType(mediaType) && !MediaUtil.isGif(mediaType) ||
           MediaUtil.isVideoType(mediaType) && isVideoTranscodeAvailable();
  }

  public static boolean isVideoTranscodeAvailable() {
    return Build.VERSION.SDK_INT >= 26 && (FeatureFlags.useStreamingVideoMuxer() || MemoryFileDescriptor.supported());
  }
}
