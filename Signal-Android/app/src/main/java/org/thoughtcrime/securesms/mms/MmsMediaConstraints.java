package org.thoughtcrime.securesms.mms;

import android.content.Context;

import com.android.mms.service_alt.MmsConfig;

final class MmsMediaConstraints extends MediaConstraints {

  private final int subscriptionId;

  private static final int MIN_IMAGE_DIMEN = 1024;

  MmsMediaConstraints(int subscriptionId) {
    this.subscriptionId = subscriptionId;
  }

  @Override
  public int getImageMaxWidth(Context context) {
    return Math.max(MIN_IMAGE_DIMEN, getOverriddenMmsConfig(context).getMaxImageWidth());
  }

  @Override
  public int getImageMaxHeight(Context context) {
    return Math.max(MIN_IMAGE_DIMEN, getOverriddenMmsConfig(context).getMaxImageHeight());
  }

  @Override
  public int[] getImageDimensionTargets(Context context) {
    int[] targets = new int[4];

    targets[0] = getImageMaxHeight(context);

    for (int i = 1; i < targets.length; i++) {
      targets[i] = targets[i - 1] / 2;
    }

    return targets;
  }

  @Override
  public int getImageMaxSize(Context context) {
    return getMaxMessageSize(context);
  }

  @Override
  public int getGifMaxSize(Context context) {
    return getMaxMessageSize(context);
  }

  @Override
  public int getVideoMaxSize(Context context) {
    return getMaxMessageSize(context);
  }

  @Override
  public int getUncompressedVideoMaxSize(Context context) {
    return Math.max(getVideoMaxSize(context), 15 * 1024 * 1024);
  }

  @Override
  public int getAudioMaxSize(Context context) {
    return getMaxMessageSize(context);
  }

  @Override
  public int getDocumentMaxSize(Context context) {
    return getMaxMessageSize(context);
  }

  private int getMaxMessageSize(Context context) {
    return getOverriddenMmsConfig(context).getMaxMessageSize();
  }

  private MmsConfig.Overridden getOverriddenMmsConfig(Context context) {
    MmsConfig mmsConfig = MmsConfigManager.getMmsConfig(context, subscriptionId);

    return new MmsConfig.Overridden(mmsConfig, null);
  }
}
