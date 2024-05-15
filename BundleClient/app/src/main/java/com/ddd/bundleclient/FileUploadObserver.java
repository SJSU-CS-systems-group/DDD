package com.ddd.bundleclient;

import android.util.Log;

import java.util.Calendar;
import java.util.Date;

import com.ddd.bundleclient.FileUploadResponse;
import io.grpc.stub.StreamObserver;

class FileUploadObserver implements StreamObserver<FileUploadResponse> {

    @Override
    public void onNext(FileUploadResponse fileUploadResponse) {
        Log.d("grpcDebug", "File upload status :: " + fileUploadResponse.getStatus());
    }

    @Override
    public void onError(Throwable throwable) {
        Log.d("grpcDebug", "ERROR :: " + throwable.toString());
    }

    @Override
    public void onCompleted() {
        Date current = Calendar.getInstance().getTime();
        Log.d("grpcDebug", "Started file transfer ended at: " + current.toString());
    }

}
