package com.ddd.bundleclient;

import android.util.Log;

import java.util.Calendar;
import java.util.Date;

import com.ddd.bundleclient.FileUploadResponse;
import io.grpc.stub.StreamObserver;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

class FileUploadObserver implements StreamObserver<FileUploadResponse> {

    private static final Logger logger = Logger.getLogger(FileUploadObserver.class.getName());

    @Override
    public void onNext(FileUploadResponse fileUploadResponse) {
        logger.log(FINE, "grpcDebug", "File upload status :: " + fileUploadResponse.getStatus());
    }

    @Override
    public void onError(Throwable throwable) {
        logger.log(FINE, "grpcDebug", "ERROR :: " + throwable.toString());
    }

    @Override
    public void onCompleted() {
        Date current = Calendar.getInstance().getTime();
        logger.log(FINE, "grpcDebug", "Started file transfer ended at: " + current.toString());
    }

}
