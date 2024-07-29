package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.Context;
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
import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.android.fragments.WifiDirectFragment;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Set;
import java.util.logging.Logger;

public class BundleClientActivity extends AppCompatActivity {

    private LogFragment logFragment;
    private PermissionsFragment permissionsFragment;
    private Set<String> mainPageFragmentRequiredPermissions;
    private WifiDirectFragment wifiDirectFragment;
    private MainPageFragment mainPageFragment;
    private Set<String> wifiDirectFragmentRequiredPermissions;
    private TabLayout tabLayout;

    record TabInfo(String label, Fragment fragment) {}
    private ArrayList<TabInfo> tabs = new ArrayList<>();
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());
    public static Context ApplicationContext;

    private static String RECEIVE_PATH = "Shared/received-bundles";

    String currentTransportId;
    String BundleExtension = ".bundle";

    // bundle transmission set up
    BundleTransmission bundleTransmission;
    private static final String usbDirName = "DDD_transport";
    public static boolean usbConnected = false;

    // instantiate window for bundles
    public static ClientWindow clientWindow;
    private int WINDOW_LENGTH = 3;
    ViewPagerAdapter adapter;
    // gRPC set up moved to -- MainPageFragment -- //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //set up view
        setContentView(R.layout.activity_bundle_client);

        // get the fragments ready
        mainPageFragment = new MainPageFragment();
        mainPageFragmentRequiredPermissions = Set.of("android.permission.ACCESS_FINE_LOCATION",
                                                         "android.permission.ACCESS_WIFI_STATE",
                                                         "android.permission.CHANGE_WIFI_STATE",
                                                         "android.permission.NEARBY_WIFI_DEVICES");
        wifiDirectFragment = WifiDirectFragment.newInstance(false);
        wifiDirectFragmentRequiredPermissions = mainPageFragmentRequiredPermissions;
        logFragment = new LogFragment();
        permissionsFragment = new PermissionsFragment();

        // Initially we only have the permissions and log
        tabs.add(new TabInfo("Permissions", permissionsFragment));
        tabs.add(new TabInfo("Logs", logFragment));

        //Set up ViewPager and TabLayout
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);
        tabLayout = findViewById(R.id.tab_layout);
        var mediator = new TabLayoutMediator(tabLayout, viewPager,
                                             (tab, position) -> tab.setText(tabs.get(position).label));
        mediator.attach();

        permissionsFragment.registerPermissionsWatcher(permissions -> {
            if (!tabs.stream().anyMatch(t -> t.fragment == mainPageFragment) &&
                    permissions.containsAll(mainPageFragmentRequiredPermissions)) {
                tabs.add(0, new TabInfo("Main", mainPageFragment));
                tabLayout.addTab(tabLayout.newTab().setText("Main"), 0);
                viewPager.post(() -> adapter.notifyItemInserted(0));
            }
            if (!tabs.stream().anyMatch(t -> t.fragment == wifiDirectFragment) &&
                    permissions.containsAll(wifiDirectFragmentRequiredPermissions)) {
                tabs.add(1, new TabInfo("Wi-fi Direct", wifiDirectFragment));
                tabLayout.addTab(tabLayout.newTab().setText("Wi-fi Direct"), 1);
                viewPager.post(() -> adapter.notifyItemInserted(1));
            }
        });

        //Application context
        ApplicationContext = getApplicationContext();

        /* Set up Server Keys before initializing Security Module */
        try {
            BundleSecurity.initializeKeyPaths(ApplicationContext.getResources(),
                                              ApplicationContext.getApplicationInfo().dataDir);
        } catch (IOException e) {
            logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys", e);
        }

        //Initialize bundle transmission
        try {
            bundleTransmission =
                    new BundleTransmission(Paths.get(getApplicationContext().getApplicationInfo().dataDir));
            clientWindow = bundleTransmission.getBundleSecurity().getClientWindow();
            logger.log(WARNING, "{MC} - got clientwindow " + clientWindow);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to initialize bundle transmission", e);

        }
    }

    @Override
    protected void onStart() {
        super.onStart();


    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    //Method to connect to transport
    public void connectTransport() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            fragment.setConnectButtonEnabled(false);
            fragment.updateWifiDirectResponse("Starting connection.....\n");
        }
    }

    public void exchangeMessage() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            fragment.setExchangeButtonEnabled(false);
        }
        logger.log(INFO, "connection complete!");
        new GrpcReceiveTask(this).executeInBackground("192.168.49.1", "7777");
    }

    // Helper method to get the MainPageFragment instance
    private MainPageFragment getMainPageFragment() {
        return (MainPageFragment) getSupportFragmentManager().findFragmentByTag("f0");
    }

    // ViewPagerAdapter class for managing fragments in the ViewPager
    class ViewPagerAdapter extends FragmentStateAdapter {
        public ViewPagerAdapter(@NonNull BundleClientActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return tabs.get(position).fragment;
        }

        @Override
        public int getItemCount() {
            return tabs.size();
        }

    }
}
