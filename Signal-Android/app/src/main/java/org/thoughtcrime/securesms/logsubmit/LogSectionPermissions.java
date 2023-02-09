package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogSectionPermissions implements LogSection {
  @Override
  public @NonNull String getTitle() {
    return "PERMISSIONS";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    StringBuilder               out    = new StringBuilder();
    List<Pair<String, Boolean>> status = new ArrayList<>();

    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_PERMISSIONS);

      for (int i = 0; i < info.requestedPermissions.length; i++) {
        status.add(new Pair<>(info.requestedPermissions[i],
                              (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0));
      }
    } catch (PackageManager.NameNotFoundException e) {
      return "Unable to retrieve.";
    }

    Collections.sort(status, (o1, o2) -> o1.first().compareTo(o2.first()));

    for (Pair<String, Boolean> pair : status) {
      out.append(pair.first()).append(": ");
      out.append(pair.second() ? "YES" : "NO");
      out.append("\n");
    }

    return out;
  }
}
