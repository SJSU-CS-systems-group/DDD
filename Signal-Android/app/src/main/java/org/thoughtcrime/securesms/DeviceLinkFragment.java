package org.thoughtcrime.securesms;

import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

public class DeviceLinkFragment extends Fragment implements View.OnClickListener {

  private LinearLayout        container;
  private LinkClickedListener linkClickedListener;
  private Uri                 uri;

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup viewGroup, Bundle bundle) {
    this.container = (LinearLayout) inflater.inflate(R.layout.device_link_fragment, container, false);
    this.container.findViewById(R.id.link_device).setOnClickListener(this);
    ViewCompat.setTransitionName(container.findViewById(R.id.devices), "devices");

    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      container.setOrientation(LinearLayout.HORIZONTAL);
    } else {
      container.setOrientation(LinearLayout.VERTICAL);
    }

    return this.container;
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfiguration) {
    super.onConfigurationChanged(newConfiguration);
    if (newConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      container.setOrientation(LinearLayout.HORIZONTAL);
    } else {
      container.setOrientation(LinearLayout.VERTICAL);
    }
  }

  public void setLinkClickedListener(Uri uri, LinkClickedListener linkClickedListener) {
    this.uri                 = uri;
    this.linkClickedListener = linkClickedListener;
  }

  @Override
  public void onClick(View v) {
    if (linkClickedListener != null) {
      linkClickedListener.onLink(uri);
    }
  }

  public interface LinkClickedListener {
    void onLink(Uri uri);
  }
}
