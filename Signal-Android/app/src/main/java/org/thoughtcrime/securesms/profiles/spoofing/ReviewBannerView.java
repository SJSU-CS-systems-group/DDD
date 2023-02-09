package org.thoughtcrime.securesms.profiles.spoofing;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackPhoto20dp;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.recipients.Recipient;

/**
 * Banner displayed within a conversation when a review is suggested.
 */
public class ReviewBannerView extends LinearLayout {

  private ImageView       bannerIcon;
  private TextView        bannerMessage;
  private View            bannerClose;
  private AvatarImageView topLeftAvatar;
  private AvatarImageView bottomRightAvatar;
  private View            stroke;

  public ReviewBannerView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public ReviewBannerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    bannerIcon        = findViewById(R.id.banner_icon);
    bannerMessage     = findViewById(R.id.banner_message);
    bannerClose       = findViewById(R.id.banner_close);
    topLeftAvatar     = findViewById(R.id.banner_avatar_1);
    bottomRightAvatar = findViewById(R.id.banner_avatar_2);
    stroke            = findViewById(R.id.banner_avatar_stroke);

    FallbackPhotoProvider provider = new FallbackPhotoProvider();

    topLeftAvatar.setFallbackPhotoProvider(provider);
    bottomRightAvatar.setFallbackPhotoProvider(provider);

    bannerClose.setOnClickListener(v -> setVisibility(GONE));
  }

  public void setBannerMessage(@Nullable CharSequence charSequence) {
    bannerMessage.setText(charSequence);
  }

  public void setBannerIcon(@Nullable Drawable icon) {
    bannerIcon.setImageDrawable(icon);

    bannerIcon.setVisibility(VISIBLE);
    topLeftAvatar.setVisibility(GONE);
    bottomRightAvatar.setVisibility(GONE);
    stroke.setVisibility(GONE);
  }

  public void setBannerRecipient(@NonNull Recipient recipient) {
    topLeftAvatar.setAvatar(recipient);
    bottomRightAvatar.setAvatar(recipient);

    bannerIcon.setVisibility(GONE);
    topLeftAvatar.setVisibility(VISIBLE);
    bottomRightAvatar.setVisibility(VISIBLE);
    stroke.setVisibility(VISIBLE);
  }

  private static final class FallbackPhotoProvider extends Recipient.FallbackPhotoProvider {
    @Override
    public @NonNull
    FallbackContactPhoto getPhotoForGroup() {
      throw new UnsupportedOperationException("This provider does not support groups");
    }

    @Override
    public @NonNull FallbackContactPhoto getPhotoForResolvingRecipient() {
      throw new UnsupportedOperationException("This provider does not support resolving recipients");
    }

    @Override
    public @NonNull FallbackContactPhoto getPhotoForLocalNumber() {
      throw new UnsupportedOperationException("This provider does not support local number");
    }

    @NonNull
    @Override
    public FallbackContactPhoto getPhotoForRecipientWithName(String name, int targetSize) {
      return new FixedSizeGeneratedContactPhoto(name, R.drawable.ic_profile_outline_20);
    }

    @NonNull
    @Override
    public FallbackContactPhoto getPhotoForRecipientWithoutName() {
      return new FallbackPhoto20dp(R.drawable.ic_profile_outline_20);
    }
  }

  private static final class FixedSizeGeneratedContactPhoto extends GeneratedContactPhoto {
    public FixedSizeGeneratedContactPhoto(@NonNull String name, int fallbackResId) {
      super(name, fallbackResId);
    }

    @Override
    protected Drawable newFallbackDrawable(@NonNull Context context, @NonNull AvatarColor color, boolean inverted) {
      return new FallbackPhoto20dp(getFallbackResId()).asDrawable(context, color, inverted);
    }
  }
}
