package net.discdd.bundleclient;

import static java.util.logging.Level.FINER;
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

import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class BundleClientActivity extends AppCompatActivity implements WifiDirectStateListener {

    // Wifi Direct set up
    private WifiDirectManager wifiDirectManager;
    private ExecutorService wifiDirectExecutor = Executors.newFixedThreadPool(1);

    //constant
    public static final String TAG = "bundleclient";
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_NEARBY_WIFI_DEVICES = 1002;
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
    private LinkedList<String> logRecords;
    private Consumer<String> logConsumer;

    // gRPC set up moved to -- MainPageFragment -- //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (logRecords == null) {
            logRecords = new LinkedList<>();
            Logger.getLogger("").addHandler(new Handler() {
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
            final String[] labels = { "Home", "Permissions", "Logs" };
            tab.setText(labels[position]);
        }).attach();

        // set up wifi direct
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle(), this,
                                                  this.getString(R.string.tansport_host));

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
    public void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
        runOnUiThread(() -> handleWifiDirectAction(action));
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceiver(wifiDirectManager.createReceiver(), wifiDirectManager.getIntentFilter());
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            unregisterReceiver(wifiDirectManager.getReceiver());
        } catch (IllegalArgumentException e) {
            logger.log(WARNING, "WifiDirect receiver unregistered before registered");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        wifiDirectExecutor.shutdown();
//        wifiDirectExecutor.shutdown();
//        unregisterReceiver(mUsbReceiver);
    }

    //Method with cases to handle Wifi Direct actions
    private void handleWifiDirectAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            switch (action) {
                case WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED:
                    fragment.updateWifiDirectResponse("Manager initialization failed\n");
                    logger.log(WARNING, "Manager initialization failed\n");
                    break;
                case WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL:
                    logger.log(FINER, "Manager initialization successful\n");
                    connectTransport();
                    break;
                case WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL:
                    fragment.updateWifiDirectResponse("Discovery initiation successful\n");
                    logger.log(FINER, "Discovery initiation successful\n");
                    break;
                case WIFI_DIRECT_MANAGER_DISCOVERY_FAILED:
                    fragment.updateWifiDirectResponse("Discovery initiation failed\n");
                    logger.log(WARNING, "Discovery initiation failed\n");
                    fragment.setConnectButtonEnabled(true);
                    break;
                case WIFI_DIRECT_MANAGER_PEERS_CHANGED:
                    fragment.updateWifiDirectResponse("Peers changed\n");
                    logger.log(WARNING, "Peers changed\n");
                    updateConnectedDevices();
                    break;
                case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED:
                    fragment.updateWifiDirectResponse("Device connection initiation failed\n");
                    logger.log(WARNING, "Device connection initiation failed\n");
                    fragment.setConnectButtonEnabled(true);
                    break;
                case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL:
                    fragment.updateWifiDirectResponse("Device connection initiation successful\n");
                    logger.log(FINER, "Device connection initiation successful\n");
                    break;
                case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL:
                    fragment.updateWifiDirectResponse("Device connected to transport\n");
                    logger.log(FINER, "Device connected to transport\n");
                    updateConnectedDevices();
                    fragment.setConnectButtonEnabled(true);
                    exchangeMessage();
                    break;
                case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED:
                    fragment.updateWifiDirectResponse("Device failed to connect to transport\n");
                    logger.log(WARNING, "Device failed to connect to transport\n");
                    fragment.setConnectButtonEnabled(true);
                    break;
            }
        }
    }

    //Method to connect to transport
    public void connectTransport() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            fragment.setConnectButtonEnabled(false);
            fragment.updateWifiDirectResponse("Starting connection.....\n");
        }
        logger.log(INFO, "Connecting to transport");

        wifiDirectExecutor.execute(wifiDirectManager);

    }

    public void exchangeMessage() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            fragment.setExchangeButtonEnabled(false);
        }
        logger.log(INFO, "connection complete!");
        new GrpcReceiveTask(this).executeInBackground("192.168.49.1", "7777");
    }

    // Method to update connected devices text
    private void updateConnectedDevices() {
        MainPageFragment fragment = getMainPageFragment();
        if (fragment != null) {
            Set<String> devicesFound = wifiDirectManager.getDevicesFound();
            List<String> devicesList = new ArrayList<>(devicesFound);
            fragment.updateConnectedDevicesText(devicesList);
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

    // ViewPagerAdapter class for managing fragments in the ViewPager
    private static class ViewPagerAdapter extends FragmentStateAdapter {
        private final BundleClientActivity bundleClientActivity;
        private LogFragment logFragment;
        private PermissionsFragment permissionsFragment;

        public ViewPagerAdapter(@NonNull BundleClientActivity fragmentActivity) {
            super(fragmentActivity);
            this.bundleClientActivity = fragmentActivity;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return switch (position) {
                case 0 -> new MainPageFragment();
                case 1 -> new PermissionsFragment();
                default -> logFragment = new LogFragment(bundleClientActivity);
            };
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }
}
