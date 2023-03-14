
package com.ddd.bundleclient;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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
import com.ddd.wifidirect.WifiDirectManager;
import com.google.protobuf.ByteString;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
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
  private Button generateBundleButton;
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
    generateBundleButton = (Button) findViewById(R.id.generate_bundle_button);
    resultText = (TextView) findViewById(R.id.grpc_response_text);
    resultText.setMovementMethod(new ScrollingMovementMethod());

    FragmentManager fragmentManager = this.getSupportFragmentManager();
    this.fragment = (FileChooserFragment) fragmentManager.findFragmentById(R.id.fragment_fileChooser);

    // set up wifi direct
    wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());

    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        sendMessage(wifiDirectManager);
      }
    });

    generateBundleButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        BundleDeliveryAgent agent = new BundleDeliveryAgent(getApplicationContext().getApplicationInfo().dataDir);
        agent.start();
      }
    });
  }


  public void sendMessage(WifiDirectManager wifiDirectManager) {
    // connect to transport
    connectTransport(wifiDirectManager);
    // check if connection successful, using intents?

    //start grpc task

//    sendButton.setEnabled(false);
//    resultText.setText("");
//    new GrpcTask(this)
//        .execute(
//                "192.168.49.1",
//                "7777",
//                this.fragment.getPath());
  }

  private class GrpcTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;

    private GrpcTask(Activity activity) {
      this.activityReference = new WeakReference<Activity>(activity);
    }

    @Override
    protected String doInBackground(String... params) {
      String host = params[0];
      String portStr = params[2];
      String pathStr = params[3];
      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
      try {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
        StreamObserver<FileUploadRequest> streamObserver = stub.uploadFile(new FileUploadObserver());

// input file for testing
        Path path = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          path = Paths.get(pathStr);
        }
//        Log.d("wDebug",path.toString());
// build metadata
        Date current = Calendar.getInstance().getTime();
        FileUploadRequest metadata = FileUploadRequest.newBuilder()
                .setMetadata(MetaData.newBuilder()
                        .setName("payload_"+current.getTime())
                        .setType("zip").build())
                .build();
        streamObserver.onNext(metadata);

// upload file as chunk
        current = Calendar.getInstance().getTime();
        Log.d("wDebug","Started file transfer");
        InputStream inputStream = null;
//        ZipInputStream inputStream = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//          inputStream = Files.newInputStream(path);
          inputStream = getResources().openRawResource(R.raw.payload);
//            inputStream = new java.io.File(R.raw.sampleaudio);

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
      Button sendButton = (Button) activity.findViewById(R.id.connect_button);
      resultText.setText(result);
      sendButton.setEnabled(true);
    }
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
      Toast.makeText(HelloworldActivity.this, "Did DiscoverPeers succeed?: " + b, Toast.LENGTH_SHORT).show();
      if( b ){
        ArrayList<WifiP2pDevice> devices = wifiDirectManager.getPeerList();
        Log.d(TAG, "Logging Devices: \n");
        if(devices.isEmpty()) {
          Log.d(TAG,"No devices found yet");
        }
        for(WifiP2pDevice d: devices) {
          Log.d(TAG, d.toString());
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

}
