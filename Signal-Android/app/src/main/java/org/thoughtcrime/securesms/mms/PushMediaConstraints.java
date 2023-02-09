package org.thoughtcrime.securesms.mms;

import android.content.Context;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.util.LocaleFeatureFlags;
import org.thoughtcrime.securesms.util.Util;

import java.util.Arrays;

public class PushMediaConstraints extends MediaConstraints {

  private static final int KB = 1024;
  private static final int MB = 1024 * KB;

  private final MediaConfig currentConfig;

  public PushMediaConstraints(@Nullable SentMediaQuality sentMediaQuality) {
    currentConfig = getCurrentConfig(ApplicationDependencies.getApplication(), sentMediaQuality);
  }

  @Override
  public boolean isHighQuality() {
    return currentConfig == MediaConfig.LEVEL_3;
  }

  @Override
  public int getImageMaxWidth(Context context) {
    return currentConfig.imageSizeTargets[0];
  }

  @Override
  public int getImageMaxHeight(Context context) {
    return getImageMaxWidth(context);
  }

  @Override
  public int getImageMaxSize(Context context) {
    return currentConfig.maxImageFileSize;
  }

  @Override
  public int[] getImageDimensionTargets(Context context) {
    return currentConfig.imageSizeTargets;
  }

  @Override
  public int getGifMaxSize(Context context) {
    return 25 * MB;
  }

  @Override
  public int getVideoMaxSize(Context context) {
    return 100 * MB;
  }

  @Override
  public int getUncompressedVideoMaxSize(Context context) {
    return isVideoTranscodeAvailable() ? 500 * MB
                                       : getVideoMaxSize(context);
  }

  @Override
  public int getCompressedVideoMaxSize(Context context) {
    return Util.isLowMemory(context) ? 30 * MB
                                     : 50 * MB;
  }

  @Override
  public int getAudioMaxSize(Context context) {
    return 100 * MB;
  }

  @Override
  public int getDocumentMaxSize(Context context) {
    return 100 * MB;
  }

  @Override
  public int getImageCompressionQualitySetting(@NonNull Context context) {
    return currentConfig.qualitySetting;
  }

  private static @NonNull MediaConfig getCurrentConfig(@NonNull Context context, @Nullable SentMediaQuality sentMediaQuality) {
    if (Util.isLowMemory(context)) {
      return MediaConfig.LEVEL_1_LOW_MEMORY;
    }

    if (sentMediaQuality == SentMediaQuality.HIGH) {
      return MediaConfig.LEVEL_3;
    }
    return LocaleFeatureFlags.getMediaQualityLevel().orElse(MediaConfig.getDefault(context));
  }

  public enum MediaConfig {
    LEVEL_1_LOW_MEMORY(true, 1, MB, new int[] { 768, 512 }, 70),

    LEVEL_1(false, 1, MB, new int[] { 1600, 1024, 768, 512 }, 70),
    LEVEL_2(false, 2, (int) (1.5 * MB), new int[] { 2048, 1600, 1024, 768, 512 }, 75),
    LEVEL_3(false, 3, (int) (3 * MB), new int[] { 4096, 3072, 2048, 1600, 1024, 768, 512 }, 75);

    private final boolean isLowMemory;
    private final int     level;
    private final int     maxImageFileSize;
    private final int[]   imageSizeTargets;
    private final int     qualitySetting;

    MediaConfig(boolean isLowMemory,
                int level,
                int maxImageFileSize,
                @NonNull int[] imageSizeTargets,
                @IntRange(from = 0, to = 100) int qualitySetting)
    {
      this.isLowMemory      = isLowMemory;
      this.level            = level;
      this.maxImageFileSize = maxImageFileSize;
      this.imageSizeTargets = imageSizeTargets;
      this.qualitySetting   = qualitySetting;
    }

    public int getMaxImageFileSize() {
      return maxImageFileSize;
    }

    public int[] getImageSizeTargets() {
      return imageSizeTargets;
    }

    public int getQualitySetting() {
      return qualitySetting;
    }

    public static @Nullable MediaConfig forLevel(int level) {
      boolean isLowMemory = Util.isLowMemory(ApplicationDependencies.getApplication());

      return Arrays.stream(values())
                   .filter(v -> v.level == level && v.isLowMemory == isLowMemory)
                   .findFirst()
                   .orElse(null);
    }

    public static @NonNull MediaConfig getDefault(Context context) {
      return Util.isLowMemory(context) ? LEVEL_1_LOW_MEMORY : LEVEL_1;
    }
  }
}
