package net.discdd.bundletransport;

import static java.util.logging.Level.WARNING;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import net.discdd.android.fragments.LogFragment;
import net.discdd.android.fragments.PermissionsFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;
import java.util.logging.Logger;

public class BundleTransportActivity extends AppCompatActivity {
    Logger logger = Logger.getLogger(BundleTransportActivity.class.getName());
    private TitledFragment serverUploadFragment;
    private TitledFragment transportWifiFragment;

    record ConnectivityEvent(boolean internetAvailable) {}

    private final SubmissionPublisher<ConnectivityEvent> connectivityEventPublisher = new SubmissionPublisher<>();
    private ViewPager2 viewPager2;
    private FragmentStateAdapter viewPager2Adapter;
    private PermissionsFragment permissionsFragment;

    record TitledFragment(String title, Fragment fragment) {}

    ArrayList<TitledFragment> fragments = new ArrayList<>();
    private static final List<String> wifiDirectPermissions =
            List.of("android.permission.ACCESS_WIFI_STATE", "android.permission.CHANGE_WIFI_STATE",
                    "android.permission.INTERNET", "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.NEARBY_WIFI_DEVICES");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getApplicationContext().startForegroundService(new Intent(this, TransportWifiDirectService.class));
        } catch (Exception e) {
            logger.log(WARNING, "Failed to start TransportWifiDirectService", e);
        }

        setContentView(R.layout.activity_bundle_transport);

        LogFragment.registerLoggerHandler();

        serverUploadFragment =
                new TitledFragment(getString(R.string.upload), new ServerUploadFragment(connectivityEventPublisher));
        transportWifiFragment = new TitledFragment(getString(R.string.local_wifi), new TransportWifiDirectFragment());

        permissionsFragment = new PermissionsFragment();
        fragments.add(serverUploadFragment);
        fragments.add(transportWifiFragment);
        fragments.add(new TitledFragment(getString(R.string.permissions), permissionsFragment));
        fragments.add(new TitledFragment(getString(R.string.logs), new LogFragment()));
        TabLayout tabLayout = findViewById(R.id.tabs);
        viewPager2 = findViewById(R.id.view_pager);
        viewPager2Adapter = new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return fragments.get(position).fragment;
            }

            @Override
            public int getItemCount() {
                return fragments.size();
            }
        };
        viewPager2.setAdapter(viewPager2Adapter);
        var mediator = new TabLayoutMediator(tabLayout, viewPager2, (tab, position) -> {
            tab.setText(fragments.get(position).title);
        });
        mediator.attach();
    }

    protected void onStart() {
        super.onStart();
        permissionsFragment.registerPermissionsWatcher(obtainedPermissions -> {
            logger.info("Permissions obtained: " + obtainedPermissions);
            if (obtainedPermissions.containsAll(wifiDirectPermissions)) {
                enableFragment(transportWifiFragment);
            } else {
                disableFragment(transportWifiFragment);
            }
        });

    }

    @Override
    protected void onPause() {
        unmonitorUploadTab();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitorUploadTab();
    }

    @Override
    protected void onDestroy() {
        unmonitorUploadTab();
        super.onDestroy();
    }

    private void enableFragment(TitledFragment titledFragment) {
        if (true) return;
        // we can't do the following code because things crash
        runOnUiThread(() -> {
            logger.info("Enabling fragment " + titledFragment.title);
            if (fragments.stream().noneMatch(tf -> tf.fragment == titledFragment.fragment)) {
                fragments.add(0, titledFragment);
                viewPager2Adapter.notifyItemRangeChanged(0, fragments.size() - 1);
            }
        });
    }

    private void disableFragment(TitledFragment titledFragment) {
        if (true) return;
        // we can't do the following code because things crash
        runOnUiThread(() -> {
            logger.info("Disabling fragment " + titledFragment.title);
            for (int i = 0; i < fragments.size(); i++) {
                if (fragments.get(i).fragment == titledFragment.fragment) {
                    fragments.remove(i);
                    final var indexToRemove = i;
                    viewPager2Adapter.notifyDataSetChanged();
                    break;
                }
            }
        });
    }

    private final ConnectivityManager.NetworkCallback uploadTabMonitorCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    logger.info("Network available");
                    enableFragment(serverUploadFragment);
                    connectivityEventPublisher.submit(new ConnectivityEvent(true));
                }

                @Override
                public void onLost(Network ni) {
                    logger.info("Network unavailable");
                    disableFragment(serverUploadFragment);
                    connectivityEventPublisher.submit(new ConnectivityEvent(false));
                }

                @Override
                public void onUnavailable() {
                    logger.info("Network unavailable");
                    disableFragment(serverUploadFragment);
                    connectivityEventPublisher.submit(new ConnectivityEvent(false));
                }
            };

    private void unmonitorUploadTab() {
        var connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.unregisterNetworkCallback(uploadTabMonitorCallback);
    }

    private void monitorUploadTab() {
        var connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest =
                new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();

        connectivityManager.registerNetworkCallback(networkRequest, uploadTabMonitorCallback);
    }

}
