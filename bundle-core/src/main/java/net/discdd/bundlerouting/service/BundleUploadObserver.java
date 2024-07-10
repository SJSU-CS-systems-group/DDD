package net.discdd.bundlerouting.service;

import io.grpc.stub.StreamObserver;
import net.discdd.bundletransport.service.BundleUploadResponse;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import java.util.Calendar;
import java.util.Date;
public class BundleUploadObserver implements StreamObserver<BundleUploadResponse> {

    private static final Logger logger = Logger.getLogger(BundleUploadObserver.class.getName());

    @Override
    public void onNext(BundleUploadResponse bundleUploadResponse) {
        logger.log(INFO, "File upload status :: " + bundleUploadResponse.getStatus());
    }

    @Override
    public void onError(Throwable throwable) {
        logger.log(SEVERE, "ERROR :: " + throwable.toString());
    }

    @Override
    public void onCompleted() {
        Date current = Calendar.getInstance().getTime();
        logger.log(INFO, "Started file transfer ended at: " + current.toString());
    }

}
