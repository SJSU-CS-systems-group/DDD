package org.signal.core.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.Locale;

/**
 * Allows you to detect if a string resource is readable by the user according to their language settings.
 */
public final class TranslationDetection {
  private final Resources     resourcesLocal;
  private final Resources     resourcesEn;
  private final Configuration configurationLocal;

  /**
   * @param context Do not pass Application context, as this may not represent the users selected in-app locale.
   */
  public TranslationDetection(@NonNull Context context) {
    this.resourcesLocal     = context.getResources();
    this.configurationLocal = resourcesLocal.getConfiguration();
    this.resourcesEn        = ResourceUtil.getEnglishResources(context);
  }

  /**
   * @param context     Can be Application context.
   * @param usersLocale Locale of user.
   */
  public TranslationDetection(@NonNull Context context, @NonNull Locale usersLocale) {
    this.resourcesLocal     = ResourceUtil.getResources(context.getApplicationContext(), usersLocale);
    this.configurationLocal = resourcesLocal.getConfiguration();
    this.resourcesEn        = ResourceUtil.getEnglishResources(context);
  }

  /**
   * Returns true if any of these are true:
   * - The current locale is English.
   * - In a multi-locale capable device, the device supports any English locale in any position.
   * - The text for the current locale does not Equal the English.
   */
  public boolean textExistsInUsersLanguage(@StringRes int resId) {
    if (configSupportsEnglish()) {
      return true;
    }

    String stringEn    = resourcesEn.getString(resId);
    String stringLocal = resourcesLocal.getString(resId);

    return !stringEn.equals(stringLocal);
  }

  public boolean textExistsInUsersLanguage(@StringRes int... resIds) {
    for (int resId : resIds) {
      if (!textExistsInUsersLanguage(resId)) {
        return false;
      }
    }
    return true;
  }

  protected boolean configSupportsEnglish() {
    if (configurationLocal.locale.getLanguage().equals("en")) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= 24) {
      Locale firstMatch = configurationLocal.getLocales().getFirstMatch(new String[]{"en"});

      return firstMatch != null && firstMatch.getLanguage().equals("en");
    }

    return false;
  }
}