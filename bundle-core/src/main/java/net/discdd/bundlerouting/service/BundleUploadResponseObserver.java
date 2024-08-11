package net.discdd.bundlerouting.service;

import io.grpc.stub.StreamObserver;
import net.discdd.grpc.BundleUploadResponse;

import java.util.Calendar;
import java.util.Date;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class BundleUploadResponseObserver implements StreamObserver<BundleUploadResponse> {
    private static final Logger logger = Logger.getLogger(BundleUploadResponseObserver.class.getName());
    public BundleUploadResponse bundleUploadResponse;
    public Throwable throwable;
    public boolean completed = false;

    @Override
    public void onNext(BundleUploadResponse bundleUploadResponse) {
        this.bundleUploadResponse = bundleUploadResponse;
    }

    @Override
    public void onError(Throwable throwable) {
        synchronized (this) {
            this.throwable = throwable;
            this.notifyAll();
        }
    }

    @Override
    public void onCompleted() {
        synchronized (this) {
            completed = true;
            this.notifyAll();
        }

        Date current = Calendar.getInstance().getTime();
        logger.log(INFO, "Upload ended at: " + current.toString());
    }

    public boolean waitForCompletion(long timeout) {
        try {
            synchronized (this) {
                while (!completed && throwable == null) {
                    this.wait(timeout);
                }
            }
        } catch (InterruptedException e) {
            logger.log(WARNING, "Timeout waiting for upload completion");
        }
        return completed;
    }

}
