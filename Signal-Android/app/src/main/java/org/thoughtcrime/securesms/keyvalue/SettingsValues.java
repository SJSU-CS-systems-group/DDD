package org.thoughtcrime.securesms.keyvalue;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.mms.SentMediaQuality;
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.util.SingleLiveEvent;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.webrtc.CallBandwidthMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("deprecation")
public final class SettingsValues extends SignalStoreValues {

  private static final String TAG = Log.tag(SettingsValues.class);

  public static final String LINK_PREVIEWS          = "settings.link_previews";
  public static final String KEEP_MESSAGES_DURATION = "settings.keep_messages_duration";

  public static final String PREFER_SYSTEM_CONTACT_PHOTOS = "settings.prefer.system.contact.photos";

  private static final String SIGNAL_BACKUP_DIRECTORY        = "settings.signal.backup.directory";
  private static final String SIGNAL_LATEST_BACKUP_DIRECTORY = "settings.signal.backup.directory,latest";

  private static final String CALL_BANDWIDTH_MODE = "settings.signal.call.bandwidth.mode";

  public static final String THREAD_TRIM_LENGTH  = "pref_trim_length";
  public static final String THREAD_TRIM_ENABLED = "pref_trim_threads";

  public static final  String THEME                                   = "settings.theme";
  public static final  String MESSAGE_FONT_SIZE                       = "settings.message.font.size";
  public static final  String LANGUAGE                                = "settings.language";
  public static final  String PREFER_SYSTEM_EMOJI                     = "settings.use.system.emoji";
  public static final  String ENTER_KEY_SENDS                         = "settings.enter.key.sends";
  public static final  String BACKUPS_ENABLED                         = "settings.backups.enabled";
  public static final  String BACKUPS_SCHEDULE_HOUR                   = "settings.backups.schedule.hour";
  public static final  String BACKUPS_SCHEDULE_MINUTE                 = "settings.backups.schedule.minute";
  public static final  String SMS_DELIVERY_REPORTS_ENABLED            = "settings.sms.delivery.reports.enabled";
  public static final  String WIFI_CALLING_COMPATIBILITY_MODE_ENABLED = "settings.wifi.calling.compatibility.mode.enabled";
  public static final  String MESSAGE_NOTIFICATIONS_ENABLED           = "settings.message.notifications.enabled";
  public static final  String MESSAGE_NOTIFICATION_SOUND              = "settings.message.notifications.sound";
  public static final  String MESSAGE_VIBRATE_ENABLED                 = "settings.message.vibrate.enabled";
  public static final  String MESSAGE_LED_COLOR                       = "settings.message.led.color";
  public static final  String MESSAGE_LED_BLINK_PATTERN               = "settings.message.led.blink";
  public static final  String MESSAGE_IN_CHAT_SOUNDS_ENABLED          = "settings.message.in.chats.sounds.enabled";
  public static final  String MESSAGE_REPEAT_ALERTS                   = "settings.message.repeat.alerts";
  public static final  String MESSAGE_NOTIFICATION_PRIVACY            = "settings.message.notification.privacy";
  public static final  String CALL_NOTIFICATIONS_ENABLED              = "settings.call.notifications.enabled";
  public static final  String CALL_RINGTONE                           = "settings.call.ringtone";
  public static final  String CALL_VIBRATE_ENABLED                    = "settings.call.vibrate.enabled";
  public static final  String NOTIFY_WHEN_CONTACT_JOINS_SIGNAL        = "settings.notify.when.contact.joins.signal";
  private static final String DEFAULT_SMS                             = "settings.default_sms";
  private static final String UNIVERSAL_EXPIRE_TIMER                  = "settings.universal.expire.timer";
  private static final String SENT_MEDIA_QUALITY                      = "settings.sentMediaQuality";
  private static final String CENSORSHIP_CIRCUMVENTION_ENABLED        = "settings.censorshipCircumventionEnabled";
  private static final String KEEP_MUTED_CHATS_ARCHIVED               = "settings.keepMutedChatsArchived";

  private final SingleLiveEvent<String> onConfigurationSettingChanged = new SingleLiveEvent<>();

  SettingsValues(@NonNull KeyValueStore store) {
    super(store);
  }

  @Override
  void onFirstEverAppLaunch() {
    if (!getStore().containsKey(LINK_PREVIEWS)) {
      getStore().beginWrite()
                .putBoolean(LINK_PREVIEWS, true)
                .apply();
    }
  }

  @Override
  @NonNull List<String> getKeysToIncludeInBackup() {
    return Arrays.asList(LINK_PREVIEWS,
                         KEEP_MESSAGES_DURATION,
                         PREFER_SYSTEM_CONTACT_PHOTOS,
                         CALL_BANDWIDTH_MODE,
                         THREAD_TRIM_LENGTH,
                         THREAD_TRIM_ENABLED,
                         LANGUAGE,
                         THEME,
                         MESSAGE_FONT_SIZE,
                         PREFER_SYSTEM_EMOJI,
                         ENTER_KEY_SENDS,
                         BACKUPS_ENABLED,
                         MESSAGE_NOTIFICATIONS_ENABLED,
                         MESSAGE_NOTIFICATION_SOUND,
                         MESSAGE_VIBRATE_ENABLED,
                         MESSAGE_LED_COLOR,
                         MESSAGE_LED_BLINK_PATTERN,
                         MESSAGE_IN_CHAT_SOUNDS_ENABLED,
                         MESSAGE_REPEAT_ALERTS,
                         MESSAGE_NOTIFICATION_PRIVACY,
                         CALL_NOTIFICATIONS_ENABLED,
                         CALL_RINGTONE,
                         CALL_VIBRATE_ENABLED,
                         NOTIFY_WHEN_CONTACT_JOINS_SIGNAL,
                         UNIVERSAL_EXPIRE_TIMER,
                         SENT_MEDIA_QUALITY,
                         KEEP_MUTED_CHATS_ARCHIVED);
  }

  public @NonNull LiveData<String> getOnConfigurationSettingChanged() {
    return onConfigurationSettingChanged;
  }

  public boolean isLinkPreviewsEnabled() {
    return getBoolean(LINK_PREVIEWS, false);
  }

  public void setLinkPreviewsEnabled(boolean enabled) {
    putBoolean(LINK_PREVIEWS, enabled);
  }

  public @NonNull KeepMessagesDuration getKeepMessagesDuration() {
    return KeepMessagesDuration.fromId(getInteger(KEEP_MESSAGES_DURATION, 0));
  }

  public void setKeepMessagesForDuration(@NonNull KeepMessagesDuration duration) {
    putInteger(KEEP_MESSAGES_DURATION, duration.getId());
  }

  public boolean isTrimByLengthEnabled() {
    return getBoolean(THREAD_TRIM_ENABLED, false);
  }

  public void setThreadTrimByLengthEnabled(boolean enabled) {
    putBoolean(THREAD_TRIM_ENABLED, enabled);
  }

  public int getThreadTrimLength() {
    return getInteger(THREAD_TRIM_LENGTH, 500);
  }

  public void setThreadTrimLength(int length) {
    putInteger(THREAD_TRIM_LENGTH, length);
  }

  public void setSignalBackupDirectory(@NonNull Uri uri) {
    putString(SIGNAL_BACKUP_DIRECTORY, uri.toString());
    putString(SIGNAL_LATEST_BACKUP_DIRECTORY, uri.toString());
  }

  public void setPreferSystemContactPhotos(boolean preferSystemContactPhotos) {
    putBoolean(PREFER_SYSTEM_CONTACT_PHOTOS, preferSystemContactPhotos);
  }

  public boolean isPreferSystemContactPhotos() {
    return getBoolean(PREFER_SYSTEM_CONTACT_PHOTOS, false);
  }

  public @Nullable Uri getSignalBackupDirectory() {
    return getUri(SIGNAL_BACKUP_DIRECTORY);
  }

  public @Nullable Uri getLatestSignalBackupDirectory() {
    return getUri(SIGNAL_LATEST_BACKUP_DIRECTORY);
  }

  public void clearSignalBackupDirectory() {
    putString(SIGNAL_BACKUP_DIRECTORY, null);
  }

  public void setCallBandwidthMode(@NonNull CallBandwidthMode callBandwidthMode) {
    putInteger(CALL_BANDWIDTH_MODE, callBandwidthMode.getCode());
  }

  public @NonNull CallBandwidthMode getCallBandwidthMode() {
    return CallBandwidthMode.fromCode(getInteger(CALL_BANDWIDTH_MODE, CallBandwidthMode.HIGH_ALWAYS.getCode()));
  }

  public @NonNull Theme getTheme() {
    return Theme.deserialize(getString(THEME, TextSecurePreferences.getTheme(ApplicationDependencies.getApplication())));
  }

  public void setTheme(@NonNull Theme theme) {
    putString(THEME, theme.serialize());
    onConfigurationSettingChanged.postValue(THEME);
  }

  public int getMessageFontSize() {
    return getInteger(MESSAGE_FONT_SIZE, TextSecurePreferences.getMessageBodyTextSize(ApplicationDependencies.getApplication()));
  }

  public int getMessageQuoteFontSize(@NonNull Context context) {
    int   currentMessageSize   = getMessageFontSize();
    int[] possibleMessageSizes = context.getResources().getIntArray(R.array.pref_message_font_size_values);
    int[] possibleQuoteSizes   = context.getResources().getIntArray(R.array.pref_message_font_quote_size_values);
    int   sizeIndex            = Arrays.binarySearch(possibleMessageSizes, currentMessageSize);

    if (sizeIndex < 0) {
      int closestSizeIndex = 0;
      int closestSizeDiff  = Integer.MAX_VALUE;

      for (int i = 0; i < possibleMessageSizes.length; i++) {
        int diff = Math.abs(possibleMessageSizes[i] - currentMessageSize);
        if (diff < closestSizeDiff) {
          closestSizeIndex = i;
          closestSizeDiff  = diff;
        }
      }

      int newSize = possibleMessageSizes[closestSizeIndex];
      Log.w(TAG, "Using non-standard font size of " + currentMessageSize + ". Closest match was " + newSize + ". Updating.");

      setMessageFontSize(newSize);
      sizeIndex = Arrays.binarySearch(possibleMessageSizes, newSize);
    }

    return possibleQuoteSizes[sizeIndex];
  }

  public void setMessageFontSize(int messageFontSize) {
    putInteger(MESSAGE_FONT_SIZE, messageFontSize);
  }

  public @NonNull String getLanguage() {
    return TextSecurePreferences.getLanguage(ApplicationDependencies.getApplication());
  }

  public void setLanguage(@NonNull String language) {
    TextSecurePreferences.setLanguage(ApplicationDependencies.getApplication(), language);
    onConfigurationSettingChanged.postValue(LANGUAGE);
  }

  public boolean isPreferSystemEmoji() {
    return getBoolean(PREFER_SYSTEM_EMOJI, TextSecurePreferences.isSystemEmojiPreferred(ApplicationDependencies.getApplication()));
  }

  public void setPreferSystemEmoji(boolean useSystemEmoji) {
    putBoolean(PREFER_SYSTEM_EMOJI, useSystemEmoji);
  }

  public boolean isEnterKeySends() {
    return getBoolean(ENTER_KEY_SENDS, TextSecurePreferences.isEnterSendsEnabled(ApplicationDependencies.getApplication()));
  }

  public void setEnterKeySends(boolean enterKeySends) {
    putBoolean(ENTER_KEY_SENDS, enterKeySends);
  }

  public boolean isBackupEnabled() {
    return getBoolean(BACKUPS_ENABLED, TextSecurePreferences.isBackupEnabled(ApplicationDependencies.getApplication()));
  }

  public void setBackupEnabled(boolean backupEnabled) {
    putBoolean(BACKUPS_ENABLED, backupEnabled);
  }

  public int getBackupHour() {
    return getInteger(BACKUPS_SCHEDULE_HOUR, 2);
  }

  public int getBackupMinute() {
    return getInteger(BACKUPS_SCHEDULE_MINUTE, 0);
  }

  public void setBackupSchedule(int hour, int minute) {
    putInteger(BACKUPS_SCHEDULE_HOUR, hour);
    putInteger(BACKUPS_SCHEDULE_MINUTE, minute);
  }

  public boolean isSmsDeliveryReportsEnabled() {
    return getBoolean(SMS_DELIVERY_REPORTS_ENABLED, TextSecurePreferences.isSmsDeliveryReportsEnabled(ApplicationDependencies.getApplication()));
  }

  public void setSmsDeliveryReportsEnabled(boolean smsDeliveryReportsEnabled) {
    putBoolean(SMS_DELIVERY_REPORTS_ENABLED, smsDeliveryReportsEnabled);
  }

  public boolean isWifiCallingCompatibilityModeEnabled() {
    return getBoolean(WIFI_CALLING_COMPATIBILITY_MODE_ENABLED, TextSecurePreferences.isWifiSmsEnabled(ApplicationDependencies.getApplication()));
  }

  public void setWifiCallingCompatibilityModeEnabled(boolean wifiCallingCompatibilityModeEnabled) {
    putBoolean(WIFI_CALLING_COMPATIBILITY_MODE_ENABLED, wifiCallingCompatibilityModeEnabled);
  }

  public void setMessageNotificationsEnabled(boolean messageNotificationsEnabled) {
    putBoolean(MESSAGE_NOTIFICATIONS_ENABLED, messageNotificationsEnabled);
  }

  public boolean isMessageNotificationsEnabled() {
    return getBoolean(MESSAGE_NOTIFICATIONS_ENABLED, TextSecurePreferences.isNotificationsEnabled(ApplicationDependencies.getApplication()));
  }

  public void setMessageNotificationSound(@NonNull Uri sound) {
    putString(MESSAGE_NOTIFICATION_SOUND, sound.toString());
  }

  public @NonNull Uri getMessageNotificationSound() {
    String result = getString(MESSAGE_NOTIFICATION_SOUND, TextSecurePreferences.getNotificationRingtone(ApplicationDependencies.getApplication()).toString());

    if (result.startsWith("file:")) {
      result = Settings.System.DEFAULT_NOTIFICATION_URI.toString();
    }

    return Uri.parse(result);
  }

  public boolean isMessageVibrateEnabled() {
    return getBoolean(MESSAGE_VIBRATE_ENABLED, TextSecurePreferences.isNotificationVibrateEnabled(ApplicationDependencies.getApplication()));
  }

  public void setMessageVibrateEnabled(boolean messageVibrateEnabled) {
    putBoolean(MESSAGE_VIBRATE_ENABLED, messageVibrateEnabled);
  }

  public @NonNull String getMessageLedColor() {
    return getString(MESSAGE_LED_COLOR, TextSecurePreferences.getNotificationLedColor(ApplicationDependencies.getApplication()));
  }

  public void setMessageLedColor(@NonNull String ledColor) {
    putString(MESSAGE_LED_COLOR, ledColor);
  }

  public @NonNull String getMessageLedBlinkPattern() {
    return getString(MESSAGE_LED_BLINK_PATTERN, TextSecurePreferences.getNotificationLedPattern(ApplicationDependencies.getApplication()));
  }

  public void setMessageLedBlinkPattern(@NonNull String blinkPattern) {
    putString(MESSAGE_LED_BLINK_PATTERN, blinkPattern);
  }

  public boolean isMessageNotificationsInChatSoundsEnabled() {
    return getBoolean(MESSAGE_IN_CHAT_SOUNDS_ENABLED, TextSecurePreferences.isInThreadNotifications(ApplicationDependencies.getApplication()));
  }

  public void setMessageNotificationsInChatSoundsEnabled(boolean inChatSoundsEnabled) {
    putBoolean(MESSAGE_IN_CHAT_SOUNDS_ENABLED, inChatSoundsEnabled);
  }

  public int getMessageNotificationsRepeatAlerts() {
    return getInteger(MESSAGE_REPEAT_ALERTS, TextSecurePreferences.getRepeatAlertsCount(ApplicationDependencies.getApplication()));
  }

  public void setMessageNotificationsRepeatAlerts(int count) {
    putInteger(MESSAGE_REPEAT_ALERTS, count);
  }

  public @NonNull NotificationPrivacyPreference getMessageNotificationsPrivacy() {
    return new NotificationPrivacyPreference(getString(MESSAGE_NOTIFICATION_PRIVACY, TextSecurePreferences.getNotificationPrivacy(ApplicationDependencies.getApplication()).toString()));
  }

  public void setMessageNotificationsPrivacy(@NonNull NotificationPrivacyPreference messageNotificationsPrivacy) {
    putString(MESSAGE_NOTIFICATION_PRIVACY, messageNotificationsPrivacy.toString());
  }

  public boolean isCallNotificationsEnabled() {
    return getBoolean(CALL_NOTIFICATIONS_ENABLED, TextSecurePreferences.isCallNotificationsEnabled(ApplicationDependencies.getApplication()));
  }

  public void setCallNotificationsEnabled(boolean callNotificationsEnabled) {
    putBoolean(CALL_NOTIFICATIONS_ENABLED, callNotificationsEnabled);
  }

  public @NonNull Uri getCallRingtone() {
    String result = getString(CALL_RINGTONE, TextSecurePreferences.getCallNotificationRingtone(ApplicationDependencies.getApplication()).toString());

    if (result != null && result.startsWith("file:")) {
      result = Settings.System.DEFAULT_RINGTONE_URI.toString();
    }

    return Uri.parse(result);
  }

  public void setCallRingtone(@NonNull Uri ringtone) {
    putString(CALL_RINGTONE, ringtone.toString());
  }

  public boolean isCallVibrateEnabled() {
    return getBoolean(CALL_VIBRATE_ENABLED, TextSecurePreferences.isCallNotificationVibrateEnabled(ApplicationDependencies.getApplication()));
  }

  public void setCallVibrateEnabled(boolean callVibrateEnabled) {
    putBoolean(CALL_VIBRATE_ENABLED, callVibrateEnabled);
  }

  public boolean isNotifyWhenContactJoinsSignal() {
    return getBoolean(NOTIFY_WHEN_CONTACT_JOINS_SIGNAL, TextSecurePreferences.isNewContactsNotificationEnabled(ApplicationDependencies.getApplication()));
  }

  public void setNotifyWhenContactJoinsSignal(boolean notifyWhenContactJoinsSignal) {
    putBoolean(NOTIFY_WHEN_CONTACT_JOINS_SIGNAL, notifyWhenContactJoinsSignal);
  }

  /**
   * We need to keep track of when the default status changes so we can sync to storage service.
   * So call this when you think it might have changed, but *don't* rely on it for knowing if we
   * *are* the default SMS. For that, continue to use
   * {@link org.thoughtcrime.securesms.util.Util#isDefaultSmsProvider(Context)}.
   */
  public void setDefaultSms(boolean value) {
    boolean lastKnown = getBoolean(DEFAULT_SMS, false);

    if (value != lastKnown && SignalStore.registrationValues().isRegistrationComplete()) {
      Log.i(TAG, "Default SMS state changed! Scheduling a storage sync.");
      putBoolean(DEFAULT_SMS, value);

      SignalExecutors.BOUNDED.execute(() -> {
        SignalDatabase.recipients().markNeedsSync(Recipient.self().getId());
        StorageSyncHelper.scheduleSyncForDataChange();
      });
    }
  }

  public void setUniversalExpireTimer(int seconds) {
    putInteger(UNIVERSAL_EXPIRE_TIMER, seconds);
  }

  public int getUniversalExpireTimer() {
    return getInteger(UNIVERSAL_EXPIRE_TIMER, 0);
  }

  public void setSentMediaQuality(@NonNull SentMediaQuality sentMediaQuality) {
    putInteger(SENT_MEDIA_QUALITY, sentMediaQuality.getCode());
  }

  public @NonNull SentMediaQuality getSentMediaQuality() {
    return SentMediaQuality.fromCode(getInteger(SENT_MEDIA_QUALITY, SentMediaQuality.STANDARD.getCode()));
  }

  public @NonNull CensorshipCircumventionEnabled getCensorshipCircumventionEnabled() {
    return CensorshipCircumventionEnabled.deserialize(getInteger(CENSORSHIP_CIRCUMVENTION_ENABLED, CensorshipCircumventionEnabled.DEFAULT.serialize()));
  }

  public void setCensorshipCircumventionEnabled(boolean enabled) {
    Log.i(TAG, "Changing censorship circumvention state to: " + enabled, new Throwable());
    putInteger(CENSORSHIP_CIRCUMVENTION_ENABLED, enabled ? CensorshipCircumventionEnabled.ENABLED.serialize() : CensorshipCircumventionEnabled.DISABLED.serialize());
  }

  public void setKeepMutedChatsArchived(boolean enabled) {
   putBoolean(KEEP_MUTED_CHATS_ARCHIVED, enabled);
  }

  public boolean shouldKeepMutedChatsArchived() {
    return getBoolean(KEEP_MUTED_CHATS_ARCHIVED, false);
  }

  private @Nullable Uri getUri(@NonNull String key) {
    String uri = getString(key, "");

    if (TextUtils.isEmpty(uri)) {
      return null;
    } else {
      return Uri.parse(uri);
    }
  }

  public enum CensorshipCircumventionEnabled {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    private final int value;

    CensorshipCircumventionEnabled(int value) {
      this.value = value;
    }

    public static CensorshipCircumventionEnabled deserialize(int value) {
      switch (value) {
        case 0:
          return DEFAULT;
        case 1:
          return ENABLED;
        case 2:
          return DISABLED;
        default:
          throw new IllegalArgumentException("Bad value: " + value);
      }
    }

    public int serialize() {
      return value;
    }
  }

  public enum Theme {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    private final String value;

    Theme(String value) {
      this.value = value;
    }

    public @NonNull String serialize() {
      return value;
    }

    public static @NonNull Theme deserialize(@NonNull String value) {
      switch (value) {
        case "system":
          return SYSTEM;
        case "light":
          return LIGHT;
        case "dark":
          return DARK;
        default:
          throw new IllegalArgumentException("Unrecognized value " + value);
      }
    }
  }
}
