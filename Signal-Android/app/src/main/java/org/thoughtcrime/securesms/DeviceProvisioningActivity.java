package org.thoughtcrime.securesms;

import android.content.Intent;
import android.os.Bundle;
import android.view.Window;

import androidx.appcompat.app.AlertDialog;

import org.signal.core.util.logging.Log;

public class DeviceProvisioningActivity extends PassphraseRequiredActivity {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(DeviceProvisioningActivity.class);

  @Override
  protected void onPreCreate() {
    supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
  }

  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle(getString(R.string.DeviceProvisioningActivity_link_a_signal_device))
        .setMessage(getString(R.string.DeviceProvisioningActivity_it_looks_like_youre_trying_to_link_a_signal_device_using_a_3rd_party_scanner))
        .setPositiveButton(R.string.DeviceProvisioningActivity_continue, (dialog1, which) -> {
          Intent intent = new Intent(DeviceProvisioningActivity.this, DeviceActivity.class);
          intent.putExtra("add", true);
          startActivity(intent);
          finish();
        })
        .setNegativeButton(android.R.string.cancel, (dialog12, which) -> {
          dialog12.dismiss();
          finish();
        })
        .setOnDismissListener(dialog13 -> finish())
        .create();

    dialog.setIcon(getResources().getDrawable(R.drawable.icon_dialog));
    dialog.show();
  }
}
