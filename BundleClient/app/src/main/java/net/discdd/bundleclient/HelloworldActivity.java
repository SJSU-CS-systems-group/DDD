package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.ddd.bundleclient.R;
import net.discdd.client.bundlerouting.ClientWindow;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class HelloworldActivity extends AppCompatActivity implements WifiDirectStateListener {

    // Wifi Direct set up
    private WifiDirectManager wifiDirectManager;
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    private static final int WRITE_EXTERNAL_STORAGE = 1002;
    // gRPC set up
    private Button connectButton;
    private Button exchangeButton;
    private Button detectTransportButton;
    private Button receiveFromTransportButton;
    private FileChooserFragment fragment;
    private TextView resultText;
    private TextView connectedDevicesText;
    private TextView wifiDirectResponseText;
    private static String RECEIVE_PATH = "/Shared/received-bundles";
    //  private BundleDeliveryAgent agent;
    // context
    public static Context ApplicationContext;

    // instantiate window for bundles
    public static ClientWindow clientWindow;

    private ExecutorService wifiDirectExecutor = Executors.newFixedThreadPool(1);

    private int WINDOW_LENGTH = 3;
    // bundle transmitter set up
    BundleTransmission bundleTransmission;

    String currentTransportId;
    String BundleExtension = ".bundle";

    public static final String TAG = "bundleclient";

    private static final Logger logger = Logger.getLogger(HelloworldActivity.class.getName());

    /**
     * check for location permissions manually, will give a prompt
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        logger.log(INFO, "checking permissions" + grantResults.length);
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION:
                if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    logger.log(SEVERE, "Find location is not granted!");
                    finish();
                }
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // set up view
        setContentView(R.layout.activity_helloworld);
        connectButton = findViewById(R.id.connect_button);
        exchangeButton = findViewById(R.id.exchange_button);
        resultText = findViewById(R.id.grpc_response_text);
        connectedDevicesText = findViewById(R.id.connected_device_address);
        wifiDirectResponseText = findViewById(R.id.wifidirect_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());

        // set up wifi direct
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle(), this,
                                                  this.getString(R.string.tansport_host));

        ApplicationContext = getApplicationContext();

        /* Set up Server Keys before initializing Security Module */
        try {
            BundleSecurity.initializeKeyPaths(ApplicationContext.getResources(),
                                              ApplicationContext.getApplicationInfo().dataDir);
        } catch (IOException e) {
            logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys");
            e.printStackTrace();
        }

        try {
            bundleTransmission =
                    new BundleTransmission(Paths.get(getApplicationContext().getApplicationInfo().dataDir));
            clientWindow = bundleTransmission.getBundleSecurity().getClientWindow();
            logger.log(WARNING, "{MC} - got clientwindow " + clientWindow);
        } catch (Exception e) {
            e.printStackTrace();
        }

        connectButton.setOnClickListener(v -> {
            connectTransport();
        });

        exchangeButton.setOnClickListener(v -> {
            if (wifiDirectManager.getDevicesFound().isEmpty()) {
                resultText.append("Not connected to any devices\n");
                return;
            }

            exchangeMessage();
        });
    }

    public void exchangeMessage() {
        // connect to transport
        exchangeButton.setEnabled(false);
        //Log.d(TAG, "connection complete");
        logger.log(INFO, "connection complete");
        new GrpcReceiveTask(this).executeInBackground("192.168.49.1", "7777");
        //changed from execute to executeInBackground
    }

    public void connectTransport() {
        connectButton.setEnabled(false);
        wifiDirectResponseText.setText("Starting connection...\n");
        logger.log(FINE, "connecting to transport");
        // we need to check and request for necessary permissions
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logger.log(FINE, "requesting permission");
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                               HelloworldActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
            logger.log(WARNING, "Permission granted");
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            logger.log(FINE, "requesting permission");
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                               HelloworldActivity.WRITE_EXTERNAL_STORAGE);
        }

        wifiDirectExecutor.execute(wifiDirectManager);
    }

    private void updateConnectedDevices() {
        connectedDevicesText.setText("");
        wifiDirectManager.getDevicesFound().stream().forEach(device -> {
            connectedDevicesText.append(device + "\n");
        });
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
        runOnUiThread(() -> {
            if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED == action) {
                wifiDirectResponseText.append("Manager initialization failed\n");
                logger.log(WARNING, "Manager initialization failed\n");
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL == action) {
                logger.log(FINER, "Manager initialization successful\n");
                connectTransport();
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL == action) {
                wifiDirectResponseText.append("Discovery initiation successful\n");
                logger.log(FINER, "Discovery initiation successful\n");
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_FAILED == action) {
                wifiDirectResponseText.append("Discovery initiation failed\n");
                logger.log(WARNING, "Discovery initiation failed\n");
                connectButton.setEnabled(true);
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_PEERS_CHANGED == action) {
                wifiDirectResponseText.append("Peers changed\n");
                logger.log(WARNING, "Peers changed\n");
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED ==
                    action) {
                wifiDirectResponseText.append("Device connection initiation failed\n");
                logger.log(WARNING, "Device connection initiation failed\n");
                connectButton.setEnabled(true);
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL ==
                    action) {
                wifiDirectResponseText.append("Device connection initiation successful\n");
                logger.log(FINER, "Device connection initiation successful\n");
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL ==
                    action) {
                wifiDirectResponseText.append("Device connected to transport\n");
                logger.log(FINER, "Device connected to transport\n");
                updateConnectedDevices();
                connectButton.setEnabled(true);
                exchangeMessage();
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED == action) {
                wifiDirectResponseText.append("Device failed to connect to transport\n");
                logger.log(WARNING, "Device failed to connect to transport\n");
                connectButton.setEnabled(true);
            }
        });
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
    }
}

