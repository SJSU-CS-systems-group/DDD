
package com.ddd.bundleclient;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.ddd.client.bundledeliveryagent.BundleDeliveryAgent;
import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.BundleWrapper;
import com.ddd.wifidirect.WifiDirectManager;
import com.google.protobuf.ByteString;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
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
  private FileChooserFragment fragment;
  private TextView resultText;


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
    detectTransportButton = (Button) findViewById(R.id.detect_transport_button);
    resultText = (TextView) findViewById(R.id.grpc_response_text);
    resultText.setMovementMethod(new ScrollingMovementMethod());

    FragmentManager fragmentManager = this.getSupportFragmentManager();
    this.fragment = (FileChooserFragment) fragmentManager.findFragmentById(R.id.fragment_fileChooser);

    // set up wifi direct
    wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());

    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        try {
          connectButton.setEnabled(true);
          exchangeMessage(wifiDirectManager);
        } catch (ExecutionException | InterruptedException e) {
          e.printStackTrace();
        }
      }
    });

    detectTransportButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        BundleDeliveryAgent agent = new BundleDeliveryAgent(getApplicationContext().getApplicationInfo().dataDir);
        agent.send();
      }
    });
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
        // receive task

        //send task
        new GrpcSendTask(this)
                .execute(
                        "192.168.49.1",
                        "7777");
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
        ArrayList<WifiP2pDevice> devices = wifiDirectManager.getPeerList();
        Log.d(TAG, "Logging Devices: \n");
        if(devices.isEmpty()) {
          Log.d(TAG,"No devices found yet");
        }
        for(WifiP2pDevice d: devices) {
//          Log.d(TAG, d.toString());
          if(d.deviceName.contains(SJSUHostDeviceName))
            Log.d(TAG,"Trying to make connection with "+d.toString());
          wifiDirectManager.connect(wifiDirectManager.makeConfig(
                  d, false));
        }
      }
      return b;
    });
    String message = "I tried to find some peers!: ";
    Log.d(TAG, message);
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
        BundleTransmission bundleTransmission = new BundleTransmission(getApplicationContext().getApplicationInfo().dataDir);
        BundleWrapper toSend = bundleTransmission.generateBundleForTransmission();
        System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
        Date current = Calendar.getInstance().getTime();
        FileUploadRequest metadata = FileUploadRequest.newBuilder()
                .setMetadata(MetaData.newBuilder()
                        .setName(toSend.getBundleId())
//                        .setName("hello")
                        .setType("jar").build())
                .build();
        streamObserver.onNext(metadata);

//      upload file as chunk
        current = Calendar.getInstance().getTime();
        Log.d(TAG,"Started file transfer");
        FileInputStream inputStream = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//        inputStream = getResources().openRawResource(R.raw.payload);
          inputStream = new FileInputStream(toSend.getSource());
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
