package net.discdd.bundlerouting.service;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public abstract class BundleExchangeServiceImpl extends BundleExchangeServiceGrpc.BundleExchangeServiceImplBase {
    private static final Logger logger = Logger.getLogger(BundleExchangeServiceImpl.class.getName());
    public record BundleExchangeName(String encryptedBundleId, boolean isDownload) {}

    @Override
    public StreamObserver<BundleUploadRequest> uploadBundle(StreamObserver<BundleUploadResponse> responseObserver) {
        onBundleExchangeEvent(BundleExchangeEvent.UPLOAD_STARTED);
        return new BundleUploadRequestStreamObserver(responseObserver);
    }

    protected abstract void onBundleExchangeEvent(BundleExchangeEvent event);

    @Override
    public void downloadBundle(BundleDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        var bundleExchangeName = new BundleExchangeName(request.getBundleId().getEncryptedId(), true);
        Path downloadPath = pathProducer(bundleExchangeName, request.getSender());
        if (downloadPath == null) {
            responseObserver.onError(new IOException("Bundle not found"));
            return;
        }

        InputStream is;
        try {
            is = Files.newInputStream(downloadPath, StandardOpenOption.READ);
        } catch (IOException e) {
            logger.log(SEVERE, "Error downloading bundle: " + request.getBundleId().getEncryptedId(), e);
            responseObserver.onError(e);
            responseObserver.onCompleted();
            return;
        }

        StreamHandler handler = new StreamHandler(is);
        Exception ex = handler.handle(bytes -> responseObserver.onNext(BundleDownloadResponse.newBuilder()
                                        .setChunk(
                                                BundleChunk.newBuilder().setChunk(bytes).build()).build()));
        if (ex != null) {
            logger.log(SEVERE, "Error downloading bundle: " + request.getBundleId().getEncryptedId(), ex);
            responseObserver.onError(ex);
        }

        responseObserver.onCompleted();
        logger.log(INFO, "Complete " + request.getBundleId().getEncryptedId());
        onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
    }

    protected abstract Path pathProducer(BundleExchangeName bundleExchangeName, BundleSender sender);

    public enum BundleExchangeEvent {
        UPLOAD_STARTED, DOWNLOAD_STARTED, UPLOAD_FINISHED, DOWNLOAD_FINISHED
    }

    public interface BundleExchangeEventListener {
        void onBundleExchangeEvent(BundleExchangeEvent event);
    }

    private class BundleUploadRequestStreamObserver implements StreamObserver<BundleUploadRequest> {
        private final StreamObserver<BundleUploadResponse> responseObserver;
        // upload context variables
        OutputStream writer;
        Path path;
        Status status;
        BundleExchangeName bundleExchangeName;

        public BundleUploadRequestStreamObserver(StreamObserver<BundleUploadResponse> responseObserver) {
            this.responseObserver = responseObserver;
            status = Status.SUCCESS;
        }

        @Override
        public void onNext(BundleUploadRequest bundleUploadRequest) {
            try {
                if (bundleUploadRequest.hasBundleId()) {
                    logger.log(INFO, "Received request to upload file to: " +
                            bundleUploadRequest.getBundleId().getEncryptedId());
                    bundleExchangeName = new BundleExchangeName(bundleUploadRequest.getBundleId().getEncryptedId(), false);
                    path = pathProducer(bundleExchangeName, null);
                    try {
                        if (path == null) throw new IOException("Could not produce a path for " + bundleExchangeName.encryptedBundleId);
                        writer = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                     } catch (IOException e) {
                        logger.log(SEVERE, "Error creating file " + path, e);
                        this.onError(e);
                    }
                } else {
                    writeFile(writer, bundleUploadRequest.getChunk().getChunk());
                }
            } catch (IOException e) {
                this.onError(e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            logger.log(SEVERE, "Error" + throwable.toString());
            status = Status.FAILED;
            // TODO we should probably convey that the upload failed. We'll figure it out later, but it
            //      would be nice to indicate early on.
            if (bundleExchangeName != null) {
                bundleCompletion(bundleExchangeName);
            }
            status = Status.FAILED;
            this.onCompleted();
        }

        @Override
        public void onCompleted() {
            logger.log(INFO, "File Upload Complete for " + path);
            try {
                if (writer != null) writer.close();
            } catch (Exception e) {
                logger.log(SEVERE, "Problem closing bundle", e);
            }
            if (bundleExchangeName != null) {
                bundleCompletion(bundleExchangeName);
            }
            responseObserver.onNext(BundleUploadResponse.newBuilder().setStatus(status).build());
            responseObserver.onCompleted();
            onBundleExchangeEvent(BundleExchangeEvent.UPLOAD_FINISHED);
        }

        private void writeFile(OutputStream writer, ByteString content) throws IOException {
            writer.write(content.toByteArray());
            writer.flush();
        }

    }

    protected abstract void bundleCompletion(BundleExchangeName bundleExchangeName);
}
