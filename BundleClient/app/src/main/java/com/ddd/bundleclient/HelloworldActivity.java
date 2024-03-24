
package com.ddd.bundleclient;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.ddd.client.bundledeliveryagent.BundleDeliveryAgent;
import com.ddd.client.bundlerouting.ClientWindow;
import com.ddd.client.bundlerouting.WindowUtils.WindowExceptions;
import com.ddd.client.bundlesecurity.BundleSecurity;
import com.ddd.client.bundlesecurity.SecurityExceptions;
import com.ddd.client.bundlesecurity.SecurityUtils;
import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.BundleDTO;
import com.ddd.model.BundleWrapper;
import com.ddd.wifidirect.WifiDirectManager;
import com.ddd.wifidirect.WifiDirectStateListener;
import com.google.protobuf.ByteString;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class HelloworldActivity extends AppCompatActivity implements WifiDirectStateListener {
  // tag used for testing in logcat
  public static final String TAG = "bundleclient";
  // Wifi Direct set up
  private com.ddd.wifidirect.WifiDirectManager wifiDirectManager;
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
  /** check for location permissions manually, will give a prompt*/
  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Log.d(TAG, "chcking permissions");
    switch (requestCode) {
      case PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION:
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
          Log.e(TAG, "Fine location permission is not granted!");
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
    wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle(), this, this.getString(R.string.tansport_host));

    ApplicationContext = getApplicationContext();

    /* Set up Server Keys before initializing Security Module */
    try {
      BundleSecurity.initializeKeyPaths(ApplicationContext.getResources(), ApplicationContext.getApplicationInfo().dataDir);
    } catch (IOException e) {
      Log.d(TAG, "[SEC]: Failed to initialize Server Keys");
      e.printStackTrace();
    }

    bundleTransmission = new BundleTransmission(getApplicationContext().getApplicationInfo().dataDir);

    try {
      clientWindow = bundleTransmission.getBundleSecurity().getClientWindow();
      Log.d(TAG, "{MC} - got clientwindow "+clientWindow);
    } catch (Exception e) {
      e.printStackTrace();
    }

    connectButton.setOnClickListener(v -> {
      connectButton.setEnabled(false);
      wifiDirectResponseText.setText("Starting connection...\n");
      connectTransport(wifiDirectManager);
    });

    exchangeButton.setOnClickListener(v -> {
      if (wifiDirectManager.getDevicesFound().isEmpty()){
        resultText.append("Not connected to any devices\n");
        return;
      }

      exchangeMessage();
    });
  }


  public void exchangeMessage() {
    // connect to transport

    Log.d(TAG,"connection complete");
    new GrpcReceiveTask(this).execute("192.168.49.1", "1778");
  }

  public void connectTransport(WifiDirectManager wifiDirectManager){
    Log.d(TAG, "connecting to transport");
    // we need to check and request for necessary permissions
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      Log.d(TAG, "requesting permission");
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
              HelloworldActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
      Log.d(TAG, "Permission granted");
    }
    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      Log.d(TAG, "requesting permission");
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
              HelloworldActivity.WRITE_EXTERNAL_STORAGE);
    }

    wifiDirectExecutor.execute(wifiDirectManager);
  }

  private void updateConnectedDevices() {
    connectedDevicesText.setText("");
    wifiDirectManager.getDevicesFound().stream().forEach(device -> {
      connectedDevicesText.append(device+"\n");
    });
  }

  @Override
  public void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
    runOnUiThread(() -> {
      if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED == action) {
        wifiDirectResponseText.append("Manager initialization failed\n");
        Log.d(TAG, "Manager initialization failed\n");
      } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL == action){
        wifiDirectResponseText.append("Discovery initiation successful\n");
        Log.d(TAG, "Discovery initiation successful\n");
      } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_FAILED == action) {
        wifiDirectResponseText.append("Discovery initiation failed\n");
        Log.d(TAG,"Discovery initiation failed\n");
        connectButton.setEnabled(true);
      } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_PEERS_CHANGED == action ){
        wifiDirectResponseText.append("Peers changed\n");
        Log.d(TAG,"Peers changed\n");
      } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED == action ){
        wifiDirectResponseText.append("Device connection initiation failed\n");
        Log.d(TAG,"Device connection initiation failed\n");
        connectButton.setEnabled(true);
      }else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL == action ){
        wifiDirectResponseText.append("Device connection initiation successful\n");
        Log.d(TAG,"Device connection initiation successful\n");
      }else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL == action ){
        wifiDirectResponseText.append("Device connected to transport\n");
        Log.d(TAG,"Device connected to transport\n");
        updateConnectedDevices();
        connectButton.setEnabled(true);
      }else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED == action ){
        wifiDirectResponseText.append("Device failed to connect to transport\n");
        Log.d(TAG,"Device failed to connect to transport\n");
        connectButton.setEnabled(true);
      }
    });
  }

  private class GrpcReceiveTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;

    private final TextView resultText;

    private GrpcReceiveTask(Activity activity) {
      this.activityReference = new WeakReference<Activity>(activity);
      this.resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
    }

    @Override
    protected String doInBackground(String... params) {
      ApplicationContext = getApplicationContext();
      String host = params[0];
      String portStr = params[1];
      String FILE_PATH = getApplicationContext().getApplicationInfo().dataDir + "/Shared/received-bundles";
      java.io.File file = new java.io.File(FILE_PATH);
      file.mkdirs();
      int port = Integer.parseInt(portStr);
      channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
      FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
      List<String> bundleRequests = null;


      Log.d(TAG, "Starting File Receive");
      resultText.append("Starting File Receive...\n");

      try {
        bundleRequests = clientWindow.getWindow(bundleTransmission.getBundleSecurity().getClientSecurity());
      } catch (SecurityExceptions.BundleIDCryptographyException e) {
        Log.d(TAG, "{BR}: Failed to get Window: " + e);
        e.printStackTrace();
      }catch(Exception e){
        Log.d(TAG, "{BR}: Failed to get Window: " + e);
        e.printStackTrace();
      }

      if (bundleRequests == null) {
        Log.d(TAG, "BUNDLE REQuests is NUll / ");
        return "Incomplete";
      } else if (bundleRequests.size() == 0) {
        Log.d(TAG, "BUNDLE REQuests has size 0 / ");
      }

      for(String bundle: bundleRequests){
//        String testBundleName = "client0-"+bundleName+".jar";
        // String testBundleName = "client0-1.jar";
        String bundleName = bundle+BundleExtension;
        ReqFilePath request = ReqFilePath.newBuilder()
                .setValue(bundleName)
                .build();
        Log.d(TAG, "Downloading file: " + bundleName);

        StreamObserver<Bytes> downloadObserver = new StreamObserver<Bytes>() {
          FileOutputStream fileOutputStream = null;

          @Override
          public void onNext(Bytes fileContent) {
            try {
              if (fileOutputStream == null) {
                fileOutputStream = new FileOutputStream(FILE_PATH+"/"+bundleName);
              }
              // Write the downloaded data to the file
              fileOutputStream.write(fileContent.getValue().toByteArray());
              //give anirudh transport ID
              currentTransportId = fileContent.getTransportId();
            } catch (IOException e) {
              onError(e);
            }
          }

          @Override
          public void onError(Throwable t) {
            Log.d(TAG, "Error downloading file: " + t.getMessage(), t);
            if (fileOutputStream != null) {
              try {
                fileOutputStream.close();
              } catch (IOException e) {
                Log.d(TAG, "Error closing output stream", e);
              }
            }
          }

          @Override
          public void onCompleted() {
            try {
              fileOutputStream.flush();
              fileOutputStream.close();
            } catch (IOException e) {
              Log.d(HelloworldActivity.TAG, "Error closing output stream", e);
            }
            Log.d(TAG, "File download complete");
          }
        };

        stub.downloadFile(request, downloadObserver);
        break;
      }

      return "Complete";
    }

    @Override
    protected void onPostExecute(String result) {
      if(result.equals("Incomplete")){
        resultText.append(result+"\n");
        return;
      }

      try {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      new GrpcSendTask(HelloworldActivity.this)
              .execute(
                      "192.168.49.1",
                      "1778");

      String FILE_PATH = getApplicationContext().getApplicationInfo().dataDir + "/Shared/received-bundles";
      BundleTransmission bundleTransmission = new BundleTransmission(getApplicationContext().getApplicationInfo().dataDir);
      bundleTransmission.processReceivedBundles(currentTransportId, FILE_PATH);


      Activity activity = activityReference.get();
      if (activity == null) {
        return;
      }
    }
  }

  private class GrpcSendTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;

    private GrpcSendTask(Activity activity) {
      this.activityReference = new WeakReference<Activity>(activity);
    }

    @Override
    protected String doInBackground(String... params) {
      String host = params[0];
      String portStr = params[1];
      int port =Integer.parseInt(portStr);
      try {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
        StreamObserver<FileUploadRequest> streamObserver = stub.uploadFile(new FileUploadObserver());
        BundleDTO toSend = bundleTransmission.generateBundleForTransmission();
        System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
        FileUploadRequest metadata = FileUploadRequest.newBuilder()
                .setMetadata(MetaData.newBuilder()
                        .setName(toSend.getBundleId())
                        .setType("bundle").build())
                .build();
        streamObserver.onNext(metadata);

//      upload file as chunk
        Log.d(TAG,"Started file transfer");
        FileInputStream inputStream = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          inputStream = new FileInputStream(toSend.getBundle().getSource());
        }
        int chunkSize = 1000*1000*4;
        byte[] bytes = new byte[chunkSize];
        int size = 0;
        while ((size = inputStream.read(bytes)) != -1){
          FileUploadRequest uploadRequest = FileUploadRequest.newBuilder()
                  .setFile(File.newBuilder().setContent(ByteString.copyFrom(bytes, 0 , size)).build())
                  .build();
          streamObserver.onNext(uploadRequest);
        }

        // close the stream
        inputStream.close();
        streamObserver.onCompleted();
//        bundleTransmission.notifyBundleSent(toSend);
        Log.d(TAG,"Completed file transfer");
        return "Complete";
      } catch (Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        return String.format("Failed... : %n%s", sw);
      }
    }

    @Override
    protected void onPostExecute(String result) {
      try {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Activity activity = activityReference.get();
      if (activity == null) {
        return;
      }
      TextView resultText = activity.findViewById(R.id.grpc_response_text);
      Button exchangeButton = activity.findViewById(R.id.exchange_button);
      resultText.setText(result);
      exchangeButton.setEnabled(true);
    }
  }

  @Override
  public void onResume(){
    super.onResume();
    registerReceiver(wifiDirectManager.createReceiver(), wifiDirectManager.getIntentFilter());
  }

  @Override
  public void onPause(){
    super.onPause();
    unregisterReceiver(wifiDirectManager.getReceiver());
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    wifiDirectExecutor.shutdown();
  }
}
