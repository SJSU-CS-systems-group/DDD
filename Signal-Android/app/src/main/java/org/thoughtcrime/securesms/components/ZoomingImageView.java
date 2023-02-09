package org.thoughtcrime.securesms.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory;
import com.github.chrisbanes.photoview.PhotoView;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.subsampling.AttachmentBitmapDecoder;
import org.thoughtcrime.securesms.components.subsampling.AttachmentRegionDecoder;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.ActionRequestListener;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.signal.core.util.concurrent.SimpleTask;

import java.io.IOException;
import java.io.InputStream;


public class ZoomingImageView extends FrameLayout {

  private static final String TAG = Log.tag(ZoomingImageView.class);

  private static final int ZOOM_TRANSITION_DURATION = 300;

  private static final float ZOOM_LEVEL_MIN = 1.0f;

  private static final float LARGE_IMAGES_ZOOM_LEVEL_MID = 2.0f;
  private static final float LARGE_IMAGES_ZOOM_LEVEL_MAX = 5.0f;

  private static final float SMALL_IMAGES_ZOOM_LEVEL_MID = 3.0f;
  private static final float SMALL_IMAGES_ZOOM_LEVEL_MAX = 8.0f;

  private final PhotoView                 photoView;
  private final SubsamplingScaleImageView subsamplingImageView;

  public ZoomingImageView(Context context) {
    this(context, null);
  }

  public ZoomingImageView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ZoomingImageView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    inflate(context, R.layout.zooming_image_view, this);

    this.photoView            = findViewById(R.id.image_view);
    this.subsamplingImageView = findViewById(R.id.subsampling_image_view);

    this.photoView.setZoomTransitionDuration(ZOOM_TRANSITION_DURATION);
    this.photoView.setScaleLevels(ZOOM_LEVEL_MIN, SMALL_IMAGES_ZOOM_LEVEL_MID, SMALL_IMAGES_ZOOM_LEVEL_MAX);

    this.subsamplingImageView.setDoubleTapZoomDuration(ZOOM_TRANSITION_DURATION);
    this.subsamplingImageView.setDoubleTapZoomScale(LARGE_IMAGES_ZOOM_LEVEL_MID);
    this.subsamplingImageView.setMaxScale(LARGE_IMAGES_ZOOM_LEVEL_MAX);

    this.photoView.setOnClickListener(v -> ZoomingImageView.this.callOnClick());
    this.subsamplingImageView.setOnClickListener(v -> ZoomingImageView.this.callOnClick());
  }

  @SuppressLint("StaticFieldLeak")
  public void setImageUri(@NonNull GlideRequests glideRequests, @NonNull Uri uri, @NonNull String contentType, @NonNull Runnable onMediaReady)
  {
    final Context context        = getContext();
    final int     maxTextureSize = BitmapUtil.getMaxTextureSize();

    Log.i(TAG, "Max texture size: " + maxTextureSize);

    SimpleTask.run(ViewUtil.getActivityLifecycle(this), () -> {
      if (MediaUtil.isGif(contentType)) return null;

      try {
        InputStream inputStream = PartAuthority.getAttachmentStream(context, uri);
        return BitmapUtil.getDimensions(inputStream);
      } catch (IOException | BitmapDecodingException e) {
        Log.w(TAG, e);
        return null;
      }
    }, dimensions -> {
      Log.i(TAG, "Dimensions: " + (dimensions == null ? "(null)" : dimensions.first + ", " + dimensions.second));

      if (dimensions == null || (dimensions.first <= maxTextureSize && dimensions.second <= maxTextureSize)) {
        Log.i(TAG, "Loading in standard image view...");
        setImageViewUri(glideRequests, uri, onMediaReady);
      } else {
        Log.i(TAG, "Loading in subsampling image view...");
        setSubsamplingImageViewUri(uri);
        onMediaReady.run();
      }
    });
  }

  private void setImageViewUri(@NonNull GlideRequests glideRequests, @NonNull Uri uri, @NonNull Runnable onMediaReady) {
    photoView.setVisibility(View.VISIBLE);
    subsamplingImageView.setVisibility(View.GONE);

    glideRequests.load(new DecryptableUri(uri))
                 .diskCacheStrategy(DiskCacheStrategy.NONE)
                 .dontTransform()
                 .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                 .addListener(ActionRequestListener.onEither(onMediaReady))
                 .into(photoView);
  }

  private void setSubsamplingImageViewUri(@NonNull Uri uri) {
    subsamplingImageView.setBitmapDecoderFactory(new AttachmentBitmapDecoderFactory());
    subsamplingImageView.setRegionDecoderFactory(new AttachmentRegionDecoderFactory());

    subsamplingImageView.setVisibility(View.VISIBLE);
    photoView.setVisibility(View.GONE);

    // We manually set the orientation ourselves because using
    // SubsamplingScaleImageView.ORIENTATION_USE_EXIF is unreliable:
    // https://github.com/signalapp/Signal-Android/issues/11732#issuecomment-963203545
    try {
      final InputStream inputStream = PartAuthority.getAttachmentStream(getContext(), uri);
      final int orientation = BitmapUtil.getExifOrientation(new ExifInterface(inputStream));
      inputStream.close();
      if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
        subsamplingImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_90);
      } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
        subsamplingImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_180);
      } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
        subsamplingImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_270);
      } else {
        subsamplingImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_0);
      }
    } catch (IOException e) {
      Log.w(TAG, e);
    }

    subsamplingImageView.setImage(ImageSource.uri(uri));
  }

  public void cleanup() {
    photoView.setImageDrawable(null);
    subsamplingImageView.recycle();
  }

  private static class AttachmentBitmapDecoderFactory implements DecoderFactory<AttachmentBitmapDecoder> {
    @Override
    public AttachmentBitmapDecoder make() throws IllegalAccessException, InstantiationException {
      return new AttachmentBitmapDecoder();
    }
  }

  private static class AttachmentRegionDecoderFactory implements DecoderFactory<AttachmentRegionDecoder> {
    @Override
    public AttachmentRegionDecoder make() throws IllegalAccessException, InstantiationException {
      return new AttachmentRegionDecoder();
    }
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    getParent().requestDisallowInterceptTouchEvent(event.getPointerCount() > 1);
    return false;
  }
}
