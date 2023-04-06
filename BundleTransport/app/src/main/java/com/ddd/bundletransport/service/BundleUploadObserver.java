package com.ddd.bundletransport.service;


import android.util.Log;

import com.ddd.bundletransport.MainActivity;

import java.util.Calendar;
import java.util.Date;
import io.grpc.stub.StreamObserver;

public class BundleUploadObserver implements StreamObserver<BundleUploadResponse> {

    @Override
    public void onNext(BundleUploadResponse bundleUploadResponse) {
        Log.d(MainActivity.TAG,"File upload status :: " + bundleUploadResponse.getStatus());
    }

    @Override
    public void onError(Throwable throwable) {
        Log.d(MainActivity.TAG,"ERROR :: " + throwable.toString());
    }

    @Override
    public void onCompleted() {
        Date current = Calendar.getInstance().getTime();
        Log.d(MainActivity.TAG,"Started file transfer ended at: "+ current.toString());
    }

}
