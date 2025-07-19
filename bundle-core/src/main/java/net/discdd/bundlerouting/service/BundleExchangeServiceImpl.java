package net.discdd.bundlerouting.service;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.grpc.Status;
import net.discdd.utils.BundleUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public abstract class BundleExchangeServiceImpl extends BundleExchangeServiceGrpc.BundleExchangeServiceImplBase {
    private static final Logger logger = Logger.getLogger(BundleExchangeServiceImpl.class.getName());

    private static final int DOWNLOAD_BUFFER_SIZE = 4096;

    @Override
    public StreamObserver<BundleUploadRequest> uploadBundle(StreamObserver<BundleUploadResponse> responseObserver) {
        onBundleExchangeEvent(BundleExchangeEvent.UPLOAD_STARTED);
        return new BundleUploadRequestStreamObserver(responseObserver);
    }

    protected abstract void onBundleExchangeEvent(BundleExchangeEvent event);

    @Override
    public void downloadBundle(BundleDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_STARTED);
        BundleUtils.checkIdClean(request.getBundleId().getEncryptedId());

        var bundleExchangeName = new BundleExchangeName(request.getBundleId().getEncryptedId(), true);

        try {
            Path downloadPath = request.hasPublicKeyMap() ?
                                pathProducer(bundleExchangeName, request.getSenderType(), request.getPublicKeyMap()) :
                                pathProducer(bundleExchangeName, request.getSenderType(), null);

            if (downloadPath == null) {
                logger.log(SEVERE,
                           "Could not produce a path for {0} with map {1}",
                           new Object[] { bundleExchangeName.encryptedBundleId, request.getPublicKeyMap() });
                responseObserver.onError(io.grpc.Status.NOT_FOUND.withDescription("Bundle not found").asException());
                return;
            }

            try (InputStream is = Files.newInputStream(downloadPath, StandardOpenOption.READ)) {
                transferToStream(is,
                                 bytes -> responseObserver.onNext(BundleDownloadResponse.newBuilder()
                                                                          .setChunk(BundleChunk.newBuilder()
                                                                                            .setChunk(bytes)
                                                                                            .build())
                                                                          .build()));
            }
            logger.log(INFO, "Complete " + request.getBundleId().getEncryptedId());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.log(SEVERE, "Error downloading bundle: " + request.getBundleId().getEncryptedId(), e);
            var status = (e instanceof SecurityException) ? io.grpc.Status.UNAUTHENTICATED : io.grpc.Status.INTERNAL;
            responseObserver.onError(status.withDescription(e.getMessage()).asException());
        } finally {
            onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
        }
        ;
    }

    public void transferToStream(InputStream in, Consumer<ByteString> callback) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(DOWNLOAD_BUFFER_SIZE);
        try (ReadableByteChannel channel = Channels.newChannel(in)) {
            int n;
            while ((n = channel.read(buffer)) > 0) {
                buffer.flip();
                callback.accept(ByteString.copyFrom(buffer));
                buffer.clear();
            }
        }
    }

    protected abstract Path pathProducer(BundleExchangeName bundleExchangeName,
                                         BundleSenderType senderType,
                                         PublicKeyMap publicKeyMap);

    protected abstract void bundleCompletion(BundleExchangeName bundleExchangeName,
                                             BundleSenderType senderType,
                                             Path path);

    public enum BundleExchangeEvent {
        UPLOAD_STARTED, DOWNLOAD_STARTED, UPLOAD_FINISHED, DOWNLOAD_FINISHED
    }

    public interface BundleExchangeEventListener {
        void onBundleExchangeEvent(BundleExchangeEvent event);
    }

    public record BundleExchangeName(String encryptedBundleId, boolean isDownload) {}

    private class BundleUploadRequestStreamObserver implements StreamObserver<BundleUploadRequest> {
        private final StreamObserver<BundleUploadResponse> responseObserver;
        // upload context variables
        OutputStream writer;
        Path path;
        Status status;
        BundleExchangeName bundleExchangeName;
        BundleSenderType bundleSenderType;

        public BundleUploadRequestStreamObserver(StreamObserver<BundleUploadResponse> responseObserver) {
            this.responseObserver = responseObserver;
            status = Status.SUCCESS;
        }

        @Override
        public void onNext(BundleUploadRequest bundleUploadRequest) {
            try {
                if (bundleUploadRequest.hasBundleId()) {
                    logger.log(INFO,
                               "Received request to upload file to: " +
                                       bundleUploadRequest.getBundleId().getEncryptedId());
                    bundleExchangeName =
                            new BundleExchangeName(bundleUploadRequest.getBundleId().getEncryptedId(), false);

                    path = pathProducer(bundleExchangeName, bundleSenderType, null);
                    try {
                        if (path == null) {
                            throw new IOException(
                                    "Could not produce a path for " + bundleExchangeName.encryptedBundleId);
                        }
                        writer = Files.newOutputStream(path,
                                                       StandardOpenOption.CREATE,
                                                       StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        logger.log(SEVERE, "Error creating file " + path, e);
                        this.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asException());
                    }
                } else if (bundleUploadRequest.hasSenderType()) {
                    bundleSenderType = bundleUploadRequest.getSenderType();
                } else {
                    writeFile(writer, bundleUploadRequest.getChunk().getChunk());
                }
            } catch (Exception e) {
                this.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asException());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            logger.log(SEVERE, "Error" + throwable.toString());
            status = Status.FAILED;
            // TODO we should probably convey that the upload failed. We'll figure it out later, but it
            //      would be nice to indicate early on.

            if (bundleExchangeName != null && bundleSenderType != null) {
                bundleCompletion(bundleExchangeName, bundleSenderType, path);
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

            if (bundleExchangeName != null && bundleSenderType != null) {
                bundleCompletion(bundleExchangeName, bundleSenderType, path);
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
}
