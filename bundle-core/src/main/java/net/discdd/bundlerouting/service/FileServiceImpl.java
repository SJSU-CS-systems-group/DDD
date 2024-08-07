package net.discdd.bundlerouting.service;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.bundletransport.service.BundleSender;
import net.discdd.bundletransport.service.Bytes;
import net.discdd.bundletransport.service.FileServiceGrpc;
import net.discdd.bundletransport.service.FileUploadRequest;
import net.discdd.bundletransport.service.FileUploadResponse;
import net.discdd.bundletransport.service.ReqFilePath;
import net.discdd.bundletransport.service.Status;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class FileServiceImpl extends FileServiceGrpc.FileServiceImplBase {
    public enum FileServiceEvent {
        UPLOAD_STARTED, DOWNLOAD_STARTED, UPLOAD_FINISHED, DOWNLOAD_FINISHED
    }

    public interface FileServiceEventListener {
        void onFileServiceEvent(FileServiceEvent event);
    }

    private static final Logger logger = Logger.getLogger(FileServiceImpl.class.getName());

    protected Path SERVER_BASE_PATH;
    protected BundleSender sender;
    protected String uploadingTo;
    protected String downloadingFrom;
    protected String bundleToDownload;
    protected BundleProcessingInterface processBundle;
    protected BundleProcessingInterface generateBundle;
    protected FileServiceEventListener listener;

    public FileServiceImpl(File externalFilesDir, BundleSender sender) {
        this(externalFilesDir, sender, null);
    }

    public FileServiceImpl(File externalFilesDir, BundleSender sender, FileServiceEventListener listener) {
        this.listener = listener;
        this.SERVER_BASE_PATH = Paths.get(externalFilesDir + "/BundleTransmission");
        this.sender = sender;
        this.processBundle = null;
        File toServer = new File(String.valueOf(SERVER_BASE_PATH.resolve("server")));
        toServer.mkdirs();

        File toClient = new File(String.valueOf(SERVER_BASE_PATH.resolve("client")));
        toClient.mkdirs();

        downloadingFrom = "client";
        uploadingTo = "server";
    }

    @Override
    public StreamObserver<FileUploadRequest> uploadFile(StreamObserver<FileUploadResponse> responseObserver) {
        if (listener != null) listener.onFileServiceEvent(FileServiceEvent.UPLOAD_STARTED);
        return new StreamObserver<FileUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;

            @Override
            public void onNext(FileUploadRequest fileUploadRequest) {
                logger.log(INFO, "Received request to write file to: " + bundleSenderToString(sender));
                try {
                    if (fileUploadRequest.hasMetadata()) {
                        writer = getFilePath(fileUploadRequest);
                    } else {
                        writeFile(writer, fileUploadRequest.getFile().getContent());
                    }
                } catch (IOException e) {
                    this.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(SEVERE, "Error" + throwable.toString());
                status = Status.FAILED;
                this.onCompleted();
            }

            @Override
            public void onCompleted() {
                logger.log(INFO, "File Upload Complete");
                closeFile(writer);
                status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
                FileUploadResponse response = FileUploadResponse.newBuilder().setStatus(status).build();
                if (null != processBundle) {
                    processBundle.execute();
                }
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                if (listener != null) listener.onFileServiceEvent(FileServiceEvent.UPLOAD_FINISHED);
            }
        };
    }

    private OutputStream getFilePath(FileUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getName() + "." + request.getMetadata().getType();
        return Files.newOutputStream(SERVER_BASE_PATH.resolve(uploadingTo).resolve(fileName), StandardOpenOption.CREATE,
                                     StandardOpenOption.APPEND);
    }

    private void writeFile(OutputStream writer, ByteString content) throws IOException {
        writer.write(content.toByteArray());
        writer.flush();
    }

    private void closeFile(OutputStream writer) {
        try {
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void downloadFile(ReqFilePath request, StreamObserver<Bytes> responseObserver) {
        logger.log(INFO, "Received request to download file from: " + bundleSenderToString(sender));
        if (null != generateBundle) {
            this.bundleToDownload = request.getValue();
            generateBundle.execute();
        }

        String requestedPath = String.valueOf(SERVER_BASE_PATH.resolve(downloadingFrom).resolve(request.getValue()));
        logger.log(INFO, "Bundle generation completed, now starting to download from path: " + requestedPath);
        logger.log(FINE, "Downloading " + requestedPath);
        if (listener != null) listener.onFileServiceEvent(FileServiceEvent.DOWNLOAD_STARTED);
        File file = new File(requestedPath);
        InputStream in;
        try {
            in = new FileInputStream(file);
        } catch (Exception ex) {
            responseObserver.onError(ex);
            if (listener != null) listener.onFileServiceEvent(FileServiceEvent.DOWNLOAD_FINISHED);
            return;
        }
        StreamHandler handler = new StreamHandler(in);
        Exception ex = handler.handle(bytes -> {
            responseObserver.onNext(
                    Bytes.newBuilder().setValue(bytes).setSender(sender).build());
        });
        if (ex != null) ex.printStackTrace();

        responseObserver.onCompleted();
        logger.log(INFO, "Complete " + requestedPath);
        if (listener != null) listener.onFileServiceEvent(FileServiceEvent.DOWNLOAD_FINISHED);
    }

    protected void setProcessBundle(BundleProcessingInterface bundleProcessingImpl) {
        this.processBundle = bundleProcessingImpl;
    }

    protected void setGenerateBundle(BundleProcessingInterface bundleProcessingImpl) {
        this.generateBundle = bundleProcessingImpl;
    }

    public static String bundleSenderToString(BundleSender sender) {
        return switch(sender.getType()) {
            case CLIENT -> "client";
            case SERVER -> "server";
            case TRANSPORT -> "transport: " + sender.getId();
            default -> "unknown:" + sender.getId();
        };
    }
}
