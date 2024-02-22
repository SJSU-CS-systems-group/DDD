
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
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class HelloworldActivity extends AppCompatActivity {
  // tag used for testing in logcat
  public static final String TAG = "dddDebug";
  // Wifi Direct set up
  private com.ddd.wifidirect.WifiDirectManager wifiDirectManager;
  public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
  private static final int WRITE_EXTERNAL_STORAGE = 1002;
  // gRPC set up
  private Button connectButton;
  private Button detectTransportButton;
  private Button receiveFromTransportButton;
  private FileChooserFragment fragment;
  private TextView resultText;
  private static String RECEIVE_PATH = "/Shared/received-bundles";
//  private BundleDeliveryAgent agent;
  // context
  public static Context ApplicationContext;

  // instantiate window for bundles
  public static ClientWindow clientWindow;

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
    connectButton = (Button) findViewById(R.id.connect_button);
//    detectTransportButton = (Button) findViewById(R.id.detect_transport_button);
//    receiveFromTransportButton = (Button) findViewById(R.id.receive_from_transport_button);
    resultText = (TextView) findViewById(R.id.grpc_response_text);
    resultText.setMovementMethod(new ScrollingMovementMethod());
//    FragmentManager fragmentManager = this.getSupportFragmentManager();
//    this.fragment = (FileChooserFragment) fragmentManager.findFragmentById(R.id.fragment_fileChooser);
//    this.agent = new BundleDeliveryAgent(getApplicationContext().getApplicationInfo().dataDir);

    // set up wifi direct
    wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());

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

    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        try {
          connectButton.setEnabled(true);

          resultText.append("Starting connection...\n");
          exchangeMessage(wifiDirectManager);
        } catch (ExecutionException | InterruptedException e) {
          e.printStackTrace();
        }
      }
    });

//    detectTransportButton.setOnClickListener(new View.OnClickListener() {
//      @Override
//      public void onClick(View view) {
//        bundleTransmission.generateBundleForTransmission();
//      }
//    });
//    receiveFromTransportButton.setOnClickListener(new View.OnClickListener() {
//      @Override
//      public void onClick(View view) {
//        List<String> bundleRequests = null;
//        try {
//          bundleRequests = clientWindow.getWindow(bundleTransmission.getBundleSecurity().getClientSecurity());
//        } catch (SecurityExceptions.BundleIDCryptographyException e) {
//          Log.d(TAG, "[BR]: Failed to get Window: " + e);
//          e.printStackTrace();
//        }
//        Set<String> windowBundleIds = new HashSet<>(bundleRequests);
//        java.io.File[] receivedBundles = new java.io.File(getApplicationContext().getApplicationInfo().dataDir + RECEIVE_PATH).listFiles();
//        if (receivedBundles != null) {
//          for (java.io.File bundleFile : receivedBundles) {
//            String bundleName = bundleFile.getName();
//            if (!windowBundleIds.contains(bundleName.substring(0, bundleName.lastIndexOf('.')))) {
//              Log.d(TAG, "[HWA] Skipping received bundle => " + bundleName);
//              continue;
//            }
//            bundleTransmission.processReceivedBundles(currentTransportId, getApplicationContext().getApplicationInfo().dataDir + RECEIVE_PATH);
//          }
//        }
//      }
//    });
  }


  public void exchangeMessage(WifiDirectManager wifiDirectManager) throws ExecutionException, InterruptedException {
    // connect to transport
    connectTransport(wifiDirectManager);
    // check if connection successful by getting group info and checking group owner
    CompletableFuture<WifiP2pGroup> getGroup = wifiDirectManager.requestGroupInfo();
    getGroup.thenApply((group) -> {
      Log.d(TAG,group.toString());
      if (!group.isGroupOwner()){
//      start request task
        Log.d(TAG,"Connection Successful!");
        resultText.append("Connection Successful!\n\n");
        // receive task
        new GrpcReceiveTask(this).execute("192.168.49.1", "1778");

//        send task
//        new GrpcSendTask(this)
//                .execute(
//                        "192.168.49.1",
//                        "1778");
      } else {
        resultText.append("Connection Failed\n\n");
      }
      return group;
    });

  }

  public void connectTransport(WifiDirectManager wifiDirectManager){
    String SJSUHostDeviceName = this.getString(R.string.tansport_host);
    // we need to check and request for necessary permissions
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
              HelloworldActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
    }
    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
              HelloworldActivity.WRITE_EXTERNAL_STORAGE);
    }
    CompletableFuture<Boolean> completedFuture = wifiDirectManager.discoverPeers();
    completedFuture.thenApply((b) -> {
      Log.d(TAG,  "Did DiscoverPeers succeed?: " + b);
      if( b ){
        resultText.append("Discovering Peers...\n");
        ArrayList<WifiP2pDevice> devices = wifiDirectManager.getPeerList();
        Log.d(TAG, "Logging Devices: \n");
        if(devices.isEmpty()) {
          resultText.append("Failed to find any Wi-Fi direct compatible devices\n\n");
          Log.d(TAG,"No devices found yet");
        } else {
          resultText.append("Found Wi-Fi direct compatible devices\n");
        }
        for(WifiP2pDevice d: devices) {
//          Log.d(TAG, d.toString());
          if(d.deviceName.contains(SJSUHostDeviceName)) {
            Log.d(TAG, "Trying to make connection with " + d.toString());
            resultText.append("Trying to make connection with " + d.toString());
          }
          wifiDirectManager.connect(wifiDirectManager.makeConfig(
                  d, false));
        }
      }
      if( !b ) resultText.append("Failed to discover any peers...\n\n");
      return b;
    });
    String message = "I tried to find some peers!: ";
    Log.d(TAG, message);
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

      Button connectButton = (Button) activity.findViewById(R.id.connect_button);
      resultText.setText(result);
      connectButton.setEnabled(true);
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
      TextView resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
      Button connectButton = (Button) activity.findViewById(R.id.connect_button);
      resultText.setText(result);
      connectButton.setEnabled(true);
    }
  }

}
