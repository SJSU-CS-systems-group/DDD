package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import net.discdd.bundletransport.screens.StorageFragment;
import net.discdd.bundletransport.screens.UploadFragment;
import net.discdd.bundletransport.screens.WifiDirectFragment;
import net.discdd.pathutils.TransportPaths;
import net.discdd.screens.LogFragment;
import net.discdd.screens.PermissionsFragment;
import net.discdd.tls.GrpcSecurity;
import net.discdd.transport.GrpcSecurityHolder;
import net.discdd.viewmodels.PermissionsViewModel;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.logging.Logger;
import java.security.InvalidAlgorithmParameterException;

import net.discdd.bundlesecurity.SecurityUtils;
import org.bouncycastle.operator.OperatorCreationException;

public class BundleTransportActivity extends AppCompatActivity {
    Logger logger = Logger.getLogger(BundleTransportActivity.class.getName());
    private GrpcSecurity transportGrpcSecurity;
    private TitledFragment serverUploadFragment;
    private TitledFragment transportWifiFragment;
    private TitledFragment storageFragment;
    private TransportPaths transportPaths;
    private TitledFragment usbFrag;
    private TitledFragment logFragment;
    private TitledFragment titledPermissionsFragment;
    private PermissionsViewModel permissionsViewModel;

    record ConnectivityEvent(boolean internetAvailable) {}

    private final SubmissionPublisher<ConnectivityEvent> connectivityEventPublisher = new SubmissionPublisher<>();
    private ViewPager2 viewPager2;
    private FragmentStateAdapter viewPager2Adapter;
    private PermissionsFragment permissionsFragment;
    private TabLayout tabLayout;
    private TabLayoutMediator mediator;
    private SharedPreferences sharedPreferences;
    TransportWifiServiceConnection transportWifiServiceConnection = new TransportWifiServiceConnection();
    private BroadcastReceiver mUsbReceiver;
    protected boolean usbExists;

    record TitledFragment(String title, Fragment fragment) {}

    ArrayList<TitledFragment> fragments = new ArrayList<>();
    private static final List<String> wifiDirectPermissions = List.of("android.permission.ACCESS_WIFI_STATE",
                                                                      "android.permission.CHANGE_WIFI_STATE",
                                                                      "android.permission.INTERNET",
                                                                      "android.permission.NEARBY_WIFI_DEVICES");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = getSharedPreferences(TransportWifiDirectService.WIFI_DIRECT_PREFERENCES, MODE_PRIVATE);

        try {
            Intent intent = new Intent(this, TransportWifiDirectService.class);
            getApplicationContext().startForegroundService(intent);
            bindService(intent, transportWifiServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            logger.log(WARNING, "Failed to start TransportWifiDirectService", e);
        }
        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbBroadcast(intent);
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            registerReceiver(mUsbReceiver, filter);
        } catch (Exception e) {
            logger.log(WARNING, "Failed to register usb broadcast", e);
        }

        setContentView(R.layout.activity_bundle_transport);

        LogFragment.registerLoggerHandler();

        this.transportPaths = new TransportPaths(getApplicationContext().getExternalFilesDir(null).toPath());
        try {
            this.transportGrpcSecurity = GrpcSecurityHolder.setGrpcSecurityHolder(transportPaths.grpcSecurityPath);
        } catch (IOException | NoSuchAlgorithmException | InvalidAlgorithmParameterException | CertificateException |
                 NoSuchProviderException | OperatorCreationException e) {
            logger.log(SEVERE, "Failed to initialize GrpcSecurity for transport", e);
        }

        serverUploadFragment = new TitledFragment(getString(R.string.upload), new UploadFragment());
        WifiDirectFragment transportFrag = new WifiDirectFragment();
        //TransportWifiDirectFragment transportFrag = TransportWifiDirectFragment.newInstance(transportPaths);
        transportWifiFragment = new TitledFragment(getString(R.string.local_wifi), transportFrag);
        storageFragment = new TitledFragment("Storage", new StorageFragment());
        usbFrag = new TitledFragment("USB", UsbFragment.newInstance(transportPaths));
        logFragment = new TitledFragment(getString(R.string.logs), new LogFragment());

        permissionsViewModel = new ViewModelProvider(this).get(PermissionsViewModel.class);
        permissionsFragment = new PermissionsFragment();
        titledPermissionsFragment = new TitledFragment("Permissions", permissionsFragment);
        fragments.add(titledPermissionsFragment);

        tabLayout = findViewById(R.id.tabs);
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

        mediator = new TabLayoutMediator(tabLayout, viewPager2, (tab, position) -> {
            tab.setText(fragments.get(position).title);
        });
        mediator.attach();

        //set observer on view model for permissions
        permissionsViewModel.getPermissionSatisfied().observe(this, this::updateTabs);

        //Check if USB is connected before app start
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            updateUsbExists(!usbManager.getDeviceList().isEmpty());
        } else {
            logger.log(WARNING, "Usbmanager was null, failed to connect");
        }
    }

    private void updateTabs(Boolean satisfied) {
        logger.log(INFO, "UPDATING TABS ... Permissions satisfied: " + satisfied);

        ArrayList<TitledFragment> newFragments = new ArrayList<>();
        if (satisfied) {
            logger.log(INFO, "ALL TABS BEING SHOWN");
            newFragments.add(serverUploadFragment);
            newFragments.add(transportWifiFragment);
            newFragments.add(storageFragment);
            newFragments.add(logFragment);
            if (usbExists) {
                newFragments.add(usbFrag);
            }
        } else {
            logger.log(INFO, "ONLY PERMISSIONS TAB IS BEING SHOWN");
            newFragments.add(titledPermissionsFragment);
        }

        if (!newFragments.equals(fragments)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mediator != null) {
                    mediator.detach();
                }

                fragments.clear();
                fragments.addAll(newFragments);

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

                mediator = new TabLayoutMediator(tabLayout,
                                                 viewPager2,
                                                 (tab, position) -> tab.setText(fragments.get(position).title()));
                mediator.attach();
            });
        }
    }

    protected void onStart() {
        super.onStart();
        permissionsViewModel.registerPermissionsWatcher(obtainedPermissions -> {
            logger.info("Permissions obtained: " + obtainedPermissions);
            if (obtainedPermissions.containsAll(wifiDirectPermissions)) {
                transportWifiServiceConnection.thenApply(transportWifiDirectService -> transportWifiDirectService.requestDeviceInfo());
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
        checkRuntimePermission();
    }

    @Override
    protected void onDestroy() {
        if (!isBackgroundWifiEnabled()) {
            stopService(new Intent(this, TransportWifiDirectService.class));
        }
        if (mUsbReceiver != null) {
            unregisterReceiver(mUsbReceiver);
        }
        unmonitorUploadTab();
        if (transportWifiServiceConnection.btService != null) unbindService(transportWifiServiceConnection);
        super.onDestroy();
    }

    void setBgWifiEnabled(boolean enabled) {
        sharedPreferences.edit()
                .putBoolean(TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE, enabled)
                .apply();
    }

    boolean isBackgroundWifiEnabled() {
        return sharedPreferences.getBoolean(TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE, true);
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

    private void handleUsbBroadcast(Intent intent) {
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            updateUsbExists(false);
            updateTabs(true);
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            updateUsbExists(true);
            updateTabs(true);
        }
    }

    public void updateUsbExists(boolean result) {
        usbExists = result;
    }

    static class TransportWifiServiceConnection extends CompletableFuture<TransportWifiDirectService>
            implements ServiceConnection {
        public TransportWifiDirectService btService;

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            var binder = (TransportWifiDirectService.TransportWifiDirectServiceBinder) service;
            btService = binder.getService();
            complete(btService);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            btService = null;
        }
    }

    public void checkRuntimePermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED) {
            permissionsViewModel.updatePermissions(true);
        }
    }

    public CompletableFuture<TransportWifiDirectService> serviceReady() {
        return transportWifiServiceConnection;
    }
}
