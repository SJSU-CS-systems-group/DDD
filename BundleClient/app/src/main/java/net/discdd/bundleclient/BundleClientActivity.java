package net.discdd.bundleclient;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // bundle transmitter set up
    BundleTransmission bundleTransmission;

    // instantiate window for bundles
    public static ClientWindow clientWindow;
    private int WINDOW_LENGTH = 3;

    // gRPC set up moved to -- MainPageFragment -- //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //set up view
        setContentView(R.layout.activity_bundle_client);

        //Set up ViewPager and TabLayout
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        ViewPageAdapter adapter = new ViewPageAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText("Home");
            } else if (position == 1) {
                tab.setText("Permissions");
            }
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
            logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys");
            e.printStackTrace();
        }

        //Initialize bundle transmission
        try {
            bundleTransmission = new BundleTransmission(
                    Paths.get(getApplicationContext().getApplicationInfo().dataDir));
            clientWindow = bundleTransmission.getBundleSecurity().getClientWindow();
            logger.log(WARNING, "{MC} - got clientwindow " + clientWindow);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to initialize bundle transmission", e);
        }

    }

//    private static final String usbDirName = "DDD_transport";
//    public static boolean usbConnected = false;
//    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
//        @Override
//        public void onReceive(Context context, Intent intent) {
//            String action = intent.getAction();
//            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
//                usbExchangeButton.setEnabled(false);
//                usbConnectionText.setText("No USB connection detected\n");
//                usbConnectionText.setTextColor(Color.RED);
//                usbConnected = false;
//                showUsbDetachedToast();
//            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
//                if (usbDirExists()) {
//                    usbExchangeButton.setEnabled(true);
//                    usbConnectionText.setText("USB connection detected\n");
//                    usbConnectionText.setTextColor(Color.GREEN);
//                    usbConnected = true;
//                    showUsbAttachedToast();
//                } else {
//                    usbExchangeButton.setEnabled(false);
//                    usbConnectionText.setText("USB was connected, but /DDD_transport directory was not detected\n");
//                    usbConnectionText.setTextColor(Color.RED);
//                    usbConnected = false;
//                    showUsbAttachedToast();
//                }
//            }
//        }
//    };

    /**
     * check for location permissions manually, will give a prompt
     */

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        logger.log(INFO, "checking permissions...." + grantResults.length);
        if (requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                logger.log(SEVERE, "Find location is not granted....");
                finish();
            }
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

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            logger.log(INFO, "requesting fine location permission....");
            requestPermissions(new String[] { Manifest.permission.NEARBY_WIFI_DEVICES },
                               PERMISSIONS_REQUEST_CODE_ACCESS_NEARBY_WIFI_DEVICES);
        }

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
            fragment.updateConnectedDevicesText(wifiDirectManager.getDevicesFound());
        }
    }

    // Helper method to get the MainPageFragment instance
    private MainPageFragment getMainPageFragment() {
        return (MainPageFragment) getSupportFragmentManager().findFragmentByTag("f0");
    }

}
    //        connectButton.setEnabled(true);
//        wifiDirectResponseText.setText("Starting connection...\n");
//        logger.log(INFO, "connecting to transport");
//        // we need to check and request for necessary permissions
//        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//            logger.log(INFO, "requesting fine location permission");
//            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
//                               BundleClientActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
//            logger.log(INFO, "Permission granted");
//        }
//        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
//            logger.log(INFO, "requesting nearby wifi devices permission");
//            requestPermissions(new String[] { Manifest.permission.NEARBY_WIFI_DEVICES },
//                               BundleClientActivity.PERMISSIONS_REQUEST_CODE_ACCESS_NEARBY_WIFI_DEVICES);
//            logger.log(INFO, "Permission granted");
//        }
//
//        wifiDirectExecutor.execute(wifiDirectManager);

//    @Override
//    public void onRequestPermissionsResult(int requestCode,
//                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//
//        logger.log(INFO, "checking permissions" + grantResults.length);
//        switch (requestCode) {
//            case PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION:
//                if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
//                    logger.log(SEVERE, "Find location is not granted!");
//                    finish();
//                }
//                break;
//        }
//    }

//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        // set up view
//        setContentView(R.layout.activity_helloworld);
//        connectButton = findViewById(R.id.connect_button);
//        exchangeButton = findViewById(R.id.exchange_button);
//        usbExchangeButton = findViewById(R.id.usb_exchange_button);
//        resultText = findViewById(R.id.grpc_response_text);
//        connectedDevicesText = findViewById(R.id.connected_device_address);
//        wifiDirectResponseText = findViewById(R.id.wifidirect_response_text);
//        usbConnectionText = findViewById(R.id.usbconnection_response_text);
//        resultText.setMovementMethod(new ScrollingMovementMethod());
//
//
//        /* Set up Server Keys before initializing Security Module */
//        try {
//            BundleSecurity.initializeKeyPaths(ApplicationContext.getResources(),
//                                              ApplicationContext.getApplicationInfo().dataDir);
//        } catch (IOException e) {
//            logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys");
//            e.printStackTrace();
//        }
//
//        try {
//            bundleTransmission =
//                    new BundleTransmission(Paths.get(getApplicationContext().getApplicationInfo().dataDir));
//            clientWindow = bundleTransmission.getBundleSecurity().getClientWindow();
//            logger.log(WARNING, "{MC} - got clientwindow " + clientWindow);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        connectButton.setOnClickListener(v -> {
//            connectTransport();
//        });
//
//        exchangeButton.setOnClickListener(v -> {
//            if (wifiDirectManager.getDevicesFound().isEmpty()) {
//                resultText.append("Not connected to any devices\n");
//                return;
//            }
//
//            exchangeMessage();
//        });
//
//        //Registers USB receiver for device attachment and detachment
//        IntentFilter filter = new IntentFilter();
//        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
//        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
//        registerReceiver(mUsbReceiver, filter);
//        checkUsbConnection();//used to check if usb connected before app has been started.
//    }


//    @Override
//    public void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
//        runOnUiThread(() -> {
//            if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED == action) {
//                wifiDirectResponseText.append("Manager initialization failed\n");
//                logger.log(WARNING, "Manager initialization failed\n");
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL == action) {
//                logger.log(FINER, "Manager initialization successful\n");
//                connectTransport();
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL == action) {
//                wifiDirectResponseText.append("Discovery initiation successful\n");
//                logger.log(FINER, "Discovery initiation successful\n");
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_FAILED == action) {
//                wifiDirectResponseText.append("Discovery initiation failed\n");
//                logger.log(WARNING, "Discovery initiation failed\n");
//                connectButton.setEnabled(true);
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_PEERS_CHANGED == action) {
//                wifiDirectResponseText.append("Peers changed\n");
//                wifiDirectResponseText.append("Peers changed\n");
//                logger.log(WARNING, "Peers changed\n");
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED ==
//                    action) {
//                wifiDirectResponseText.append("Device connection initiation failed\n");
//                logger.log(WARNING, "Device connection initiation failed\n");
//                connectButton.setEnabled(true);
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL ==
//                    action) {
//                wifiDirectResponseText.append("Device connection initiation successful\n");
//                logger.log(FINER, "Device connection initiation successful\n");
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL ==
//                    action) {
//                wifiDirectResponseText.append("Device connected to transport\n");
//                logger.log(FINER, "Device connected to transport\n");
//                updateConnectedDevices();
//                connectButton.setEnabled(true);
//                exchangeMessage();
//            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED == action) {
//                wifiDirectResponseText.append("Device failed to connect to transport\n");
//                logger.log(WARNING, "Device failed to connect to transport\n");
//                connectButton.setEnabled(true);
//            }
//        });
//    }


//    //Usb connection methods
//
//    /**
//     * Method to show a toast message indicating USB device detachment
//     */
//    private void showUsbDetachedToast() {
//        Toast.makeText(this, "USB device detached", Toast.LENGTH_SHORT).show();
//    }
//
//    /**
//     * Method to show a toast message indicating USB device attachment
//     */
//    private void showUsbAttachedToast() {
//        Toast.makeText(this, "USB device attached", Toast.LENGTH_SHORT).show();
//    }
//
//    /**
//     * Checks if the /DDD_transport root directory exists in the connected usb fob.
//     */
//    private boolean usbDirExists() {
//        //"/mnt/media/[uuid](getSerialNumber()?)/DDD_transport
//        return false;
//    }
//
//    /**
//     * Method checks that a USB device is connected before app starts.
//     */
//    private void checkUsbConnection() {
//        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
//        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
//        if (!deviceList.isEmpty()) {
//            //resultText.append(usbDevice.getDeviceName());
//            if (usbDirExists()) {
//                usbConnected = true;
//                usbExchangeButton.setEnabled(true);
//                usbConnectionText.setText("USB connection detected\n");
//                usbConnectionText.setTextColor(Color.GREEN);
//                showUsbAttachedToast();
//            } else {
//                usbConnected = false;
//                usbExchangeButton.setEnabled(false);
//                usbConnectionText.setText("USB was connected, but /DDD_transport directory was not detected\n");
//                usbConnectionText.setTextColor(Color.RED);
//                showUsbAttachedToast();
//            }
//        } else {
//            usbExchangeButton.setEnabled(false);
//            usbConnectionText.setText("Usb device not connected\n");
//            usbConnectionText.setTextColor(Color.RED);
//        }
//    }
//}

