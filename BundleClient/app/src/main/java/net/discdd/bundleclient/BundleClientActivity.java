package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;
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
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
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

import net.discdd.android.fragments.LogFragment;
import net.discdd.android.fragments.PermissionsFragment;
import net.discdd.viewmodels.PermissionsViewModel;
import net.discdd.client.bundlerouting.ClientWindow;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class BundleClientActivity extends AppCompatActivity {

    //constant
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());
    // instantiate window for bundles
    public static ClientWindow clientWindow;
    ConnectivityManager connectivityManager;
    ArrayList<FragmentWithTitle> fragmentsWithTitles = new ArrayList<>();
    private SharedPreferences sharedPreferences;
    BundleClientWifiDirectService wifiBgService;
    private final ServiceConnection connection;
    CompletableFuture<BundleClientActivity> serviceReady = new CompletableFuture<>();
    private PermissionsFragment permissionsFragment;
    private BundleClientWifiDirectFragment homeFragment;
    private UsbFragment usbFragment;
    private ServerFrag serverFrag;
    private LogFragment logFragment;
    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private TabLayoutMediator tabLayoutMediator;
    PermissionsViewModel permissionsViewModel;
    private BroadcastReceiver mUsbReceiver;
    protected boolean usbExists;

    public BundleClientActivity() {
        connection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                // We've bound to LocalService, cast the IBinder and get LocalService instance.
                var binder = (BundleClientWifiDirectService.BundleClientWifiDirectServiceBinder) service;
                wifiBgService = binder.getService();
                serviceReady.complete(BundleClientActivity.this);
                serverFrag.setWifiService(wifiBgService);
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                wifiBgService = null;
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences =
                getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE);

        ComponentName comp;
        try {
            comp = getApplicationContext().startForegroundService(
                    new Intent(this, BundleClientWifiDirectService.class));
        } catch (Exception e) {
            logger.log(WARNING, "Failed to start TransportWifiDirectService", e);
        }

        var intent = new Intent(this, BundleClientWifiDirectService.class);
        var svc = bindService(intent, connection, Context.BIND_AUTO_CREATE);

        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbBroadcast(intent);
            }
        };
        try {
            //Register USB broadcast receiver
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            registerReceiver(mUsbReceiver, filter);
        } catch (Exception e) {
            logger.log(WARNING, "Failed to register usb broadcast", e);
        }

        permissionsViewModel = new ViewModelProvider(this).get(PermissionsViewModel.class);
        permissionsFragment = PermissionsFragment.newInstance();
        homeFragment = BundleClientWifiDirectFragment.newInstance();
        usbFragment = UsbFragment.newInstance();
        logFragment = LogFragment.newInstance();
        fragmentsWithTitles.add(new FragmentWithTitle(permissionsFragment, getString(R.string.permissions_tab)));
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        serverFrag = new ServerFrag();

        //set up view
        setContentView(R.layout.activity_bundle_client);

        //Set up ViewPager and TabLayout
        viewPager = findViewById(R.id.view_pager);
        tabLayout = findViewById(R.id.tab_layout);
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        tabLayoutMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> tab.setText(
                fragmentsWithTitles.get(position).title()));
        tabLayoutMediator.attach();

        //set observer on view model for permissions
        permissionsViewModel.getPermissionSatisfied().observe(this, this::updateTabs);

        //Checks if USB connected before app started
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if(usbManager != null) {
            updateUsbExists(!usbManager.getDeviceList().isEmpty());
        }
        else {
            logger.log(WARNING, "Usbmanager was null, failed to connect");
        }
    }

    private void updateTabs(Boolean satisfied) {
        logger.log(INFO, "UPDATING TABS ... Permissions satisfied: " + satisfied);

        ArrayList<FragmentWithTitle> newFragments = new ArrayList<>();
        if (satisfied) {
            logger.log(INFO, "ALL TABS BEING SHOWN");
            newFragments.add(new FragmentWithTitle(homeFragment, getString(R.string.home_tab)));
            newFragments.add(new FragmentWithTitle(serverFrag, getString(R.string.server_tab)));
            newFragments.add(new FragmentWithTitle(logFragment, getString(R.string.logs_tab)));
            if (usbExists) {
                newFragments.add(new FragmentWithTitle(usbFragment, getString(R.string.usb_tab)));
            }
        } else {
            logger.log(INFO, "ONLY PERMISSIONS TAB IS BEING SHOWN");
            newFragments.add(new FragmentWithTitle(permissionsFragment, getString(R.string.permissions_tab)));
        }

        if (!newFragments.equals(fragmentsWithTitles)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (tabLayoutMediator != null) {
                    tabLayoutMediator.detach();
                }

                fragmentsWithTitles.clear();
                fragmentsWithTitles.addAll(newFragments);

                ViewPagerAdapter adapter = new ViewPagerAdapter(this);
                viewPager.setAdapter(adapter);

                tabLayoutMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> tab.setText(
                        fragmentsWithTitles.get(position).title()));
                tabLayoutMediator.attach();
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkRuntimePermission();
    }

    @Override
    public void onDestroy() {
        if (!sharedPreferences.getBoolean(
                BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false)) {
            stopService(new Intent(this, BundleClientWifiDirectService.class));
        }
        if (mUsbReceiver != null) {
            unregisterReceiver(mUsbReceiver);
        }
        unbindService(connection);
        super.onDestroy();
    }

    record FragmentWithTitle(Fragment fragment, String title) {}

    // ViewPagerAdapter class for managing fragments in the ViewPager
    private class ViewPagerAdapter extends FragmentStateAdapter {

        public ViewPagerAdapter(@NonNull BundleClientActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return fragmentsWithTitles.get(position).fragment();
        }

        @Override
        public int getItemCount() {
            return fragmentsWithTitles.size();
        }
    }

    private void handleUsbBroadcast(Intent intent) {
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            updateUsbExists(false);
            permissionsViewModel.getPermissionSatisfied().observe(this, this::updateTabs);
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            updateUsbExists(true);
            permissionsViewModel.getPermissionSatisfied().observe(this, this::updateTabs);
        }
    }

    public void updateUsbExists(boolean result) {
        usbExists = result;
    }

    public void checkRuntimePermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
            permissionsViewModel.updatePermissions(true);
        }
    }
}
