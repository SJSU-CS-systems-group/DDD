package net.discdd.bundleclient;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Bundle;
import android.os.Handler;

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
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class BundleClientActivity extends AppCompatActivity implements WifiDirectStateListener {

    // Wifi Direct set up
    WifiDirectManager wifiDirectManager;
    private ExecutorService wifiDirectExecutor = Executors.newFixedThreadPool(1);

    //constant
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());
    ConnectivityManager connectivityManager;

    //  private BundleDeliveryAgent agent;
    // context
    public static Context ApplicationContext;

    private static String RECEIVE_PATH = "Shared/received-bundles";

    String currentTransportId;
    String BundleExtension = ".bundle";

    // bundle transmission set up
    BundleTransmission bundleTransmission;

    // instantiate window for bundles
    public static ClientWindow clientWindow;
    private int WINDOW_LENGTH = 3;
    private LinkedList<String> logRecords;
    private Consumer<String> logConsumer;

    // gRPC set up moved to -- MainPageFragment -- //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (logRecords == null) {
            logRecords = new LinkedList<>();
            Logger.getLogger("").addHandler(new java.util.logging.Handler() {
                @Override
                public void publish(LogRecord logRecord) {
                    // get the last part of the logger name
                    var loggerNameParts = logRecord.getLoggerName().split("\\.");
                    var loggerName = loggerNameParts[loggerNameParts.length - 1];
                    if (logRecords.size() > 100) logRecords.remove(0);
                    String entry = String.format("[%s] %s", loggerName, logRecord.getMessage());
                    logRecords.add(entry);
                    if (logConsumer != null) logConsumer.accept(entry + '\n');
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() throws SecurityException {
                }
            });
        }

        //set up view
        setContentView(R.layout.activity_bundle_client);

        //Set up ViewPager and TabLayout
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            final String[] labels = { "Home", "Permissions", "Usb", "Logs" };
            tab.setText(labels[position]);
        }).attach();

        if (wifiDirectManager == null) {
            // set up wifi direct
            wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle(), this, false);
            wifiDirectManager.initialize();
        }

        refreshPeers();

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
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        wifiDirectExecutor.shutdown();
//        unregisterReceiver(mUsbReceiver);
    }

    void appendResultsMessage(String message) {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            fragment.appendResultText(message + "\n");
        }
    }

    // A list of futures waiting to connect to a transport by the device name of transport
    final HashMap<String, ArrayList<CompletableFuture<WifiP2pGroup>>> connectionWaiters = new HashMap<>();

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        var fragment = getMainPageFragment();
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZED:
                appendResultsMessage("Wifi initialized\n");
                wifiDirectManager.discoverPeers();
                break;
            case WIFI_DIRECT_MANAGER_PEERS_CHANGED:
                updateConnectedDevices();
                break;
            case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED:
                WifiP2pGroup groupInfo = wifiDirectManager.getGroupInfo();
                if (groupInfo != null && groupInfo.getOwner() != null) {
                    ArrayList<CompletableFuture<WifiP2pGroup>> list;
                    synchronized (connectionWaiters) {
                        list = connectionWaiters.remove(groupInfo.getOwner().deviceName);
                    }
                    if (list != null) {
                        for (CompletableFuture<WifiP2pGroup> future : list) {
                            future.complete(groupInfo);
                        }
                    }
                }
                if (fragment != null) {
                    fragment.updateOwnerAndGroupInfo(wifiDirectManager.getGroupOwnerAddress(),
                                                     wifiDirectManager.getGroupInfo());
                }
                break;
            case WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED:
                if (fragment != null) {
                    fragment.updateOwnerAndGroupInfo(wifiDirectManager.getGroupOwnerAddress(),
                                                     wifiDirectManager.getGroupInfo());
                }
                break;
            case WIFI_DIRECT_MANAGER_CONNECTION_CHANGED:
                refreshPeers();
                break;
        }
    }

    void runInXMs(Runnable runnable, long delayMs) {
        new Handler(getApplication().getMainLooper()).postDelayed(runnable, delayMs);
    }

    // Method to update connected devices text
    private void updateConnectedDevices() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            HashSet<WifiP2pDevice> peerList = wifiDirectManager.getPeerList();
            fragment.updateConnectedDevices(peerList);
        }
    }

    // Helper method to get the MainPageFragment instance
    private MainPageFragment getMainPageFragment() {
        return (MainPageFragment) getSupportFragmentManager().findFragmentByTag("f0");
    }

    public String subscribeToLogs(Consumer<String> logConsumer) {
        this.logConsumer = logConsumer;
        return String.join("\n", logRecords);
    }

    public void refreshPeers() {
        wifiDirectManager.discoverPeers();
    }

    // ViewPagerAdapter class for managing fragments in the ViewPager
    private static class ViewPagerAdapter extends FragmentStateAdapter {
        private final BundleClientActivity bundleClientActivity;
        private LogFragment logFragment;
        private PermissionsFragment permissionsFragment;
        private UsbFragment usbFragment;

        public ViewPagerAdapter(@NonNull BundleClientActivity fragmentActivity) {
            super(fragmentActivity);
            this.bundleClientActivity = fragmentActivity;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return switch (position) {
                case 0 -> new MainPageFragment();
                case 1 -> permissionsFragment = new PermissionsFragment();
                case 2 -> new UsbFragment();
                default -> logFragment = new LogFragment();
            };
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }
}
