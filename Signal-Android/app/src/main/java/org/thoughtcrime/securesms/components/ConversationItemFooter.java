package org.thoughtcrime.securesms.components;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.animation.AnimationCompleteListener;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.MessageRecordUtil;
import org.thoughtcrime.securesms.util.Projection;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.dualsim.SubscriptionInfoCompat;
import org.thoughtcrime.securesms.util.dualsim.SubscriptionManagerCompat;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ConversationItemFooter extends ConstraintLayout {

  private TextView                    dateView;
  private TextView                    simView;
  private ExpirationTimerView         timerView;
  private ImageView                   insecureIndicatorView;
  private DeliveryStatusView          deliveryStatusView;
  private boolean                     onlyShowSendingStatus;
  private TextView                    audioDuration;
  private LottieAnimationView         revealDot;
  private PlaybackSpeedToggleTextView playbackSpeedToggleTextView;
  private boolean                     isOutgoing;
  private boolean                     hasShrunkDate;

  private OnTouchDelegateChangedListener onTouchDelegateChangedListener;

  private final Rect speedToggleHitRect = new Rect();
  private final int  touchTargetSize    = ViewUtil.dpToPx(48);

  private long previousMessageId;

  public ConversationItemFooter(Context context) {
    super(context);
    init(null);
  }

  public ConversationItemFooter(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init(attrs);
  }

  public ConversationItemFooter(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init(attrs);
  }

  private void init(@Nullable AttributeSet attrs) {
    final TypedArray typedArray;
    if (attrs != null) {
      typedArray = getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.ConversationItemFooter, 0, 0);
    } else {
      typedArray = null;
    }

    final @LayoutRes int contentId;
    if (typedArray != null) {
      int mode = typedArray.getInt(R.styleable.ConversationItemFooter_footer_mode, 0);
      isOutgoing = mode == 0;

      if (isOutgoing) {
        contentId = R.layout.conversation_item_footer_outgoing;
      } else {
        contentId = R.layout.conversation_item_footer_incoming;
      }
    } else {
      contentId  = R.layout.conversation_item_footer_outgoing;
      isOutgoing = true;
    }

    inflate(getContext(), contentId, this);

    dateView                    = findViewById(R.id.footer_date);
    simView                     = findViewById(R.id.footer_sim_info);
    timerView                   = findViewById(R.id.footer_expiration_timer);
    insecureIndicatorView       = findViewById(R.id.footer_insecure_indicator);
    deliveryStatusView          = findViewById(R.id.footer_delivery_status);
    audioDuration               = findViewById(R.id.footer_audio_duration);
    revealDot                   = findViewById(R.id.footer_revealed_dot);
    playbackSpeedToggleTextView = findViewById(R.id.footer_audio_playback_speed_toggle);

    if (typedArray != null) {
      setTextColor(typedArray.getInt(R.styleable.ConversationItemFooter_footer_text_color, getResources().getColor(R.color.core_white)));
      setIconColor(typedArray.getInt(R.styleable.ConversationItemFooter_footer_icon_color, getResources().getColor(R.color.core_white)));
      setRevealDotColor(typedArray.getInt(R.styleable.ConversationItemFooter_footer_reveal_dot_color, getResources().getColor(R.color.core_white)));
      typedArray.recycle();
    }

    dateView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      if (oldLeft != left || oldRight != right) {
        notifyTouchDelegateChanged(getPlaybackSpeedToggleTouchDelegateRect(), playbackSpeedToggleTextView);
      }
    });
  }

  public void setOnTouchDelegateChangedListener(@Nullable OnTouchDelegateChangedListener onTouchDelegateChangedListener) {
    this.onTouchDelegateChangedListener = onTouchDelegateChangedListener;
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    timerView.stopAnimation();
  }

  public void setMessageRecord(@NonNull MessageRecord messageRecord, @NonNull Locale locale) {
    presentDate(messageRecord, locale);
    presentSimInfo(messageRecord);
    presentTimer(messageRecord);
    presentInsecureIndicator(messageRecord);
    presentDeliveryStatus(messageRecord);
    presentAudioDuration(messageRecord);
  }

  public void setAudioDuration(long totalDurationMillis, long currentPostionMillis) {
    long remainingSecs = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(totalDurationMillis - currentPostionMillis));
    audioDuration.setText(getResources().getString(R.string.AudioView_duration, remainingSecs / 60, remainingSecs % 60));
  }

  public void setPlaybackSpeedListener(@Nullable PlaybackSpeedToggleTextView.PlaybackSpeedListener playbackSpeedListener) {
    playbackSpeedToggleTextView.setPlaybackSpeedListener(playbackSpeedListener);
  }

  public void setAudioPlaybackSpeed(float playbackSpeed, boolean isPlaying) {
    if (isPlaying) {
      showPlaybackSpeedToggle();
    } else {
      hidePlaybackSpeedToggle();
    }

    playbackSpeedToggleTextView.setCurrentSpeed(playbackSpeed);
  }

  public void setTextColor(int color) {
    dateView.setTextColor(color);
    simView.setTextColor(color);
    audioDuration.setTextColor(color);
  }

  public void setIconColor(int color) {
    timerView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    insecureIndicatorView.setColorFilter(color);
    deliveryStatusView.setTint(color);
  }

  public void setRevealDotColor(int color) {
    revealDot.addValueCallback(
        new KeyPath("**"),
        LottieProperty.COLOR_FILTER,
        frameInfo -> new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    );
  }

  public void setOnlyShowSendingStatus(boolean onlyShowSending, MessageRecord messageRecord) {
    this.onlyShowSendingStatus = onlyShowSending;
    presentDeliveryStatus(messageRecord);
  }

  public void enableBubbleBackground(@DrawableRes int drawableRes, @Nullable Integer tint) {
    setBackgroundResource(drawableRes);

    if (tint != null) {
      getBackground().setColorFilter(tint, PorterDuff.Mode.MULTIPLY);
    } else {
      getBackground().clearColorFilter();
    }
  }

  public void disableBubbleBackground() {
    setBackground(null);
  }

  public @Nullable Projection getProjection(@NonNull ViewGroup coordinateRoot) {
    if (getVisibility() == VISIBLE) {
      return Projection.relativeToParent(coordinateRoot, this, new Projection.Corners(ViewUtil.dpToPx(11)));
    } else {
      return null;
    }
  }

  public TextView getDateView() {
    return dateView;
  }

  private void notifyTouchDelegateChanged(@NonNull Rect rect, @NonNull View touchDelegate) {
    if (onTouchDelegateChangedListener != null) {
      onTouchDelegateChangedListener.onTouchDelegateChanged(rect, touchDelegate);
    }
  }

  private void showPlaybackSpeedToggle() {
    if (hasShrunkDate) {
      return;
    }

    hasShrunkDate = true;

    playbackSpeedToggleTextView.animate()
                               .alpha(1f)
                               .scaleX(1f)
                               .scaleY(1f)
                               .setDuration(150L)
                               .setListener(new AnimationCompleteListener() {
                                 @Override
                                 public void onAnimationEnd(Animator animation) {
                                   playbackSpeedToggleTextView.setClickable(true);
                                 }
                               });

    if (isOutgoing) {
      dateView.setMaxWidth(ViewUtil.dpToPx(32));
    } else {
      ConstraintSet constraintSet = new ConstraintSet();
      constraintSet.clone(this);
      constraintSet.constrainMaxWidth(R.id.date_and_expiry_wrapper, ViewUtil.dpToPx(40));
      constraintSet.applyTo(this);
    }
  }

  private void hidePlaybackSpeedToggle() {
    if (!hasShrunkDate) {
      return;
    }

    hasShrunkDate = false;

    playbackSpeedToggleTextView.animate()
                               .alpha(0f)
                               .scaleX(0.5f)
                               .scaleY(0.5f)
                               .setDuration(150L).setListener(new AnimationCompleteListener() {
                                 @Override
                                 public void onAnimationEnd(Animator animation) {
                                   playbackSpeedToggleTextView.setClickable(false);
                                   playbackSpeedToggleTextView.clearRequestedSpeed();
                                 }
                               });

    if (isOutgoing) {
      dateView.setMaxWidth(Integer.MAX_VALUE);
    } else {
      ConstraintSet constraintSet = new ConstraintSet();
      constraintSet.clone(this);
      constraintSet.constrainMaxWidth(R.id.date_and_expiry_wrapper, -1);
      constraintSet.applyTo(this);
    }
  }

  private @NonNull Rect getPlaybackSpeedToggleTouchDelegateRect() {
    playbackSpeedToggleTextView.getHitRect(speedToggleHitRect);

    int widthOffset  = (touchTargetSize - speedToggleHitRect.width()) / 2;
    int heightOffset = (touchTargetSize - speedToggleHitRect.height()) / 2;

    speedToggleHitRect.top -= heightOffset;
    speedToggleHitRect.left -= widthOffset;
    speedToggleHitRect.right += widthOffset;
    speedToggleHitRect.bottom += heightOffset;

    return speedToggleHitRect;
  }

  private void presentDate(@NonNull MessageRecord messageRecord, @NonNull Locale locale) {
    dateView.forceLayout();
    if (messageRecord.isFailed()) {
      int errorMsg;
      if (messageRecord.hasFailedWithNetworkFailures()) {
        errorMsg = R.string.ConversationItem_error_network_not_delivered;
      } else if (messageRecord.getRecipient().isPushGroup() && messageRecord.isIdentityMismatchFailure()) {
        errorMsg = R.string.ConversationItem_error_partially_not_delivered;
      } else {
        errorMsg = R.string.ConversationItem_error_not_sent_tap_for_details;
      }

      dateView.setText(errorMsg);
    } else if (messageRecord.isPendingInsecureSmsFallback()) {
      dateView.setText(R.string.ConversationItem_click_to_approve_unencrypted);
    } else if (messageRecord.isRateLimited()) {
      dateView.setText(R.string.ConversationItem_send_paused);
    } else if (MessageRecordUtil.isScheduled(messageRecord)) {
      dateView.setText(DateUtils.getOnlyTimeString(getContext(), locale, ((MediaMmsMessageRecord) messageRecord).getScheduledDate()));
    } else {
      dateView.setText(DateUtils.getSimpleRelativeTimeSpanString(getContext(), locale, messageRecord.getTimestamp()));
    }
  }

  private void presentSimInfo(@NonNull MessageRecord messageRecord) {
    SubscriptionManagerCompat subscriptionManager = new SubscriptionManagerCompat(getContext());

    if (messageRecord.isPush() || messageRecord.getSubscriptionId() == -1 || !Permissions.hasAll(getContext(), Manifest.permission.READ_PHONE_STATE) || !subscriptionManager.isMultiSim()) {
      simView.setVisibility(View.GONE);
    } else {
      Optional<SubscriptionInfoCompat> subscriptionInfo = subscriptionManager.getActiveSubscriptionInfo(messageRecord.getSubscriptionId());

      if (subscriptionInfo.isPresent() && messageRecord.isOutgoing()) {
        simView.setText(getContext().getString(R.string.ConversationItem_from_s, subscriptionInfo.get().getDisplayName()));
        simView.setVisibility(View.VISIBLE);
      } else if (subscriptionInfo.isPresent()) {
        simView.setText(getContext().getString(R.string.ConversationItem_to_s, subscriptionInfo.get().getDisplayName()));
        simView.setVisibility(View.VISIBLE);
      } else {
        simView.setVisibility(View.GONE);
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private void presentTimer(@NonNull final MessageRecord messageRecord) {
    if (messageRecord.getExpiresIn() > 0 && !messageRecord.isPending()) {
      this.timerView.setVisibility(View.VISIBLE);
      this.timerView.setPercentComplete(0);

      if (messageRecord.getExpireStarted() > 0) {
        this.timerView.setExpirationTime(messageRecord.getExpireStarted(),
                                         messageRecord.getExpiresIn());
        this.timerView.startAnimation();

        if (messageRecord.getExpireStarted() + messageRecord.getExpiresIn() <= System.currentTimeMillis()) {
          ApplicationDependencies.getExpiringMessageManager().checkSchedule();
        }
      } else if (!messageRecord.isOutgoing() && !messageRecord.isMediaPending()) {
        SignalExecutors.BOUNDED.execute(() -> {
          ExpiringMessageManager expirationManager = ApplicationDependencies.getExpiringMessageManager();
          long                   id                = messageRecord.getId();
          boolean                mms               = messageRecord.isMms();

          if (mms) {
            SignalDatabase.messages().markExpireStarted(id);
          } else {
            SignalDatabase.messages().markExpireStarted(id);
          }

          expirationManager.scheduleDeletion(id, mms, messageRecord.getExpiresIn());
        });
      }
    } else {
      this.timerView.setVisibility(View.GONE);
    }
  }

  private void presentInsecureIndicator(@NonNull MessageRecord messageRecord) {
    insecureIndicatorView.setVisibility(messageRecord.isSecure() ? View.GONE : View.VISIBLE);
  }

  private void presentDeliveryStatus(@NonNull MessageRecord messageRecord) {
    long newMessageId = buildMessageId(messageRecord);

    if (previousMessageId == newMessageId && deliveryStatusView.isPending() && !messageRecord.isPending()) {
      if (messageRecord.getRecipient().isGroup()) {
        SignalLocalMetrics.GroupMessageSend.onUiUpdated(messageRecord.getId());
      } else {
        SignalLocalMetrics.IndividualMessageSend.onUiUpdated(messageRecord.getId());
      }
    }

    previousMessageId = newMessageId;


    if (messageRecord.isFailed() || messageRecord.isPendingInsecureSmsFallback() || MessageRecordUtil.isScheduled(messageRecord)) {
      deliveryStatusView.setNone();
      return;
    }

    if (onlyShowSendingStatus) {
      if (messageRecord.isOutgoing() && messageRecord.isPending()) {
        deliveryStatusView.setPending();
      } else {
        deliveryStatusView.setNone();
      }
    } else {
      if (!messageRecord.isOutgoing()) {
        deliveryStatusView.setNone();
      } else if (messageRecord.isPending()) {
        deliveryStatusView.setPending();
      } else if (messageRecord.isRemoteRead()) {
        deliveryStatusView.setRead();
      } else if (messageRecord.isDelivered()) {
        deliveryStatusView.setDelivered();
      } else {
        deliveryStatusView.setSent();
      }
    }
  }

  private void presentAudioDuration(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms()) {
      MmsMessageRecord mmsMessageRecord = (MmsMessageRecord) messageRecord;

      if (mmsMessageRecord.getSlideDeck().getAudioSlide() != null) {
        showAudioDurationViews();

        if (messageRecord.getViewedReceiptCount() > 0 || (messageRecord.isOutgoing() && Objects.equals(messageRecord.getRecipient(), Recipient.self()))) {
          revealDot.setProgress(1f);
        } else {
          revealDot.setProgress(0f);
        }
      } else {
        hideAudioDurationViews();
      }
    } else {
      hideAudioDurationViews();
    }
  }

  private void showAudioDurationViews() {
    audioDuration.setVisibility(View.VISIBLE);
    revealDot.setVisibility(View.VISIBLE);
    playbackSpeedToggleTextView.setVisibility(View.VISIBLE);
  }

  private void hideAudioDurationViews() {
    audioDuration.setVisibility(View.GONE);
    revealDot.setVisibility(View.GONE);
    playbackSpeedToggleTextView.setVisibility(View.GONE);
  }

  private long buildMessageId(@NonNull MessageRecord record) {
    return record.isMms() ? -record.getId() : record.getId();
  }

  public interface OnTouchDelegateChangedListener {
    void onTouchDelegateChanged(@NonNull Rect delegateRect, @NonNull View delegateView);
  }
}
