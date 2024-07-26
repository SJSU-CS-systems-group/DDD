package net.discdd.bundlerouting.service;

import net.discdd.bundlerouting.BundleSender;
import com.google.protobuf.ByteString;

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

import io.grpc.stub.StreamObserver;
import net.discdd.bundletransport.service.Bytes;
import net.discdd.bundletransport.service.FileServiceGrpc;
import net.discdd.bundletransport.service.FileUploadRequest;
import net.discdd.bundletransport.service.FileUploadResponse;
import net.discdd.bundletransport.service.ReqFilePath;
import net.discdd.bundletransport.service.Status;

public class FileServiceImpl extends FileServiceGrpc.FileServiceImplBase {

    private static final Logger logger = Logger.getLogger(FileServiceImpl.class.getName());

    protected Path SERVER_BASE_PATH;
    protected BundleSender sender;
    protected String senderId;
    protected String uploadingTo;
    protected String downloadingTo;
    protected BundleProcessingInterface processBundle;

    public FileServiceImpl(File externalFilesDir, BundleSender sender, String senderId) {
        this.SERVER_BASE_PATH = Paths.get(externalFilesDir + "/BundleTransmission");
        this.sender = sender;
        this.senderId = senderId;
        this.processBundle = null;
        File toServer = new File(String.valueOf(SERVER_BASE_PATH.resolve("server")));
        toServer.mkdirs();

        File toClient = new File(String.valueOf(SERVER_BASE_PATH.resolve("client")));
        toClient.mkdirs();

        downloadingTo = "client";
        uploadingTo = "server";
    }

    @Override
    public StreamObserver<FileUploadRequest> uploadFile(StreamObserver<FileUploadResponse> responseObserver) {
        return new StreamObserver<FileUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;

            @Override
            public void onNext(FileUploadRequest fileUploadRequest) {
                logger.log(INFO, "Received request to write file to: " + sender.name());
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
                if (null != processBundle){
                    processBundle.execute();
                }
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        };
    }

    private OutputStream getFilePath(FileUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getName() + "." + request.getMetadata().getType();
        return Files.newOutputStream(SERVER_BASE_PATH.resolve(uploadingTo).resolve(fileName),
                                         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
        String requestedPath = String.valueOf(SERVER_BASE_PATH.resolve(downloadingTo).resolve(request.getValue()));
        logger.log(FINE, "Downloading " + requestedPath);
        File file = new File(requestedPath);
        InputStream in;
        try {
            in = new FileInputStream(file);
        } catch (Exception ex) {
            responseObserver.onError(ex);
            return;
        }
        StreamHandler handler = new StreamHandler(in);
        Exception ex = handler.handle(bytes -> {
            responseObserver.onNext(
                    Bytes.newBuilder().setValue(bytes).setSenderId(senderId).setSender(sender.name()).build());
        });
        if (ex != null) ex.printStackTrace();

        responseObserver.onCompleted();
        logger.log(INFO, "Complete " + requestedPath);
    }

    protected void setProcessBundle(BundleProcessingInterface bundleProcessingImpl){
        this.processBundle = bundleProcessingImpl;
    }
}
