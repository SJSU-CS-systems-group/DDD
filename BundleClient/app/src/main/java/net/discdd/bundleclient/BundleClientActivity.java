package net.discdd.bundleclient;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import net.discdd.android.fragments.LogFragment;
import net.discdd.android.fragments.PermissionStateManager;
import net.discdd.android.fragments.PermissionsFragment;
import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.client.bundlesecurity.BundleSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
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
    private PermissionsFragment permissionsFragment;
    private PermissionStateManager permissionStateManager;
    CompletableFuture<BundleClientActivity> serviceReady = new CompletableFuture<>();

    public BundleClientActivity() {
        connection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                // We've bound to LocalService, cast the IBinder and get LocalService instance.
                var binder = (BundleClientWifiDirectService.BundleClientWifiDirectServiceBinder) service;
                wifiBgService = binder.getService();
                serviceReady.complete(BundleClientActivity.this);
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

        //set up view
        setContentView(R.layout.activity_bundle_client);

        permissionStateManager = new ViewModelProvider(this).get(PermissionStateManager.class);
        permissionsFragment = new PermissionsFragment(permissionStateManager);
        permissionStateManager.getPermissionSatisfied().observe(this, this::updateTabs);

        renderTabs();
        setUpViewPager();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        //Application context
        var resources = getApplicationContext().getResources();
        /* Set up Server Keys before initializing Security Module */
        try (InputStream inServerIdentity = resources.openRawResource(
                R.raw.server_identity); InputStream inServerSignedPre = resources.openRawResource(
                R.raw.server_signed_pre); InputStream inServerRatchet = resources.openRawResource(
                R.raw.server_ratchet)) {
            BundleSecurity.initializeKeyPaths(inServerIdentity, inServerSignedPre, inServerRatchet,
                                              Paths.get(getApplicationContext().getApplicationInfo().dataDir));
        } catch (IOException e) {
            logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys", e);
        }
    }

    private void setUpViewPager() {
        //Set up ViewPager and TabLayout
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);

        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        var tabMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> tab.setText(
                fragmentsWithTitles.get(position).title()));
        tabMediator.attach();
    }

    private void updateTabs(Boolean aBoolean) {
        renderTabs();
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        if (viewPager.getAdapter() != null) {
            viewPager.getAdapter().notifyDataSetChanged();
        }
    }

    public void renderTabs() {
        fragmentsWithTitles.clear();
        Boolean permissionsSatisfied = permissionStateManager.getPermissionSatisfied().getValue();
        if (Boolean.TRUE.equals(permissionsSatisfied)) {
            BundleClientWifiDirectFragment bundleClientWifiDirectFragment = new BundleClientWifiDirectFragment();
            fragmentsWithTitles.add(new FragmentWithTitle(bundleClientWifiDirectFragment, getString(R.string.home_tab)));
            fragmentsWithTitles.add(new FragmentWithTitle(new UsbFragment(), getString(R.string.usb_tab)));
            fragmentsWithTitles.add(new FragmentWithTitle(new ServerFragment(), getString(R.string.server_tab)));
            fragmentsWithTitles.add(new FragmentWithTitle(new LogFragment(), getString(R.string.logs_tab)));
        } else {
            fragmentsWithTitles.add(new FragmentWithTitle(permissionsFragment, getString(R.string.permissions_tab)));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (!sharedPreferences.getBoolean(
                BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false)) {
            stopService(new Intent(this, BundleClientWifiDirectService.class));
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
}
