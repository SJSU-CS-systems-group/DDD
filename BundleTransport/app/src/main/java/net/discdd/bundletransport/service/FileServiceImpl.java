package net.discdd.bundletransport.service;

import android.content.Context;

import net.discdd.bundlerouting.BundleSender;
import net.discdd.bundletransport.MainActivity;

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

public class FileServiceImpl extends FileServiceGrpc.FileServiceImplBase {

    private static final Logger logger = Logger.getLogger(FileServiceImpl.class.getName());

    private android.content.Context context;
    private Path SERVER_BASE_PATH;

    public FileServiceImpl(Context context) {
        this.context = context;
        this.SERVER_BASE_PATH = Paths.get(context.getExternalFilesDir(null) + "/BundleTransmission");
        File toServer = new File(String.valueOf(SERVER_BASE_PATH.resolve("server")));
        toServer.mkdirs();

        File toClient = new File(String.valueOf(SERVER_BASE_PATH.resolve("client")));
        toClient.mkdirs();
    }

    @Override
    public StreamObserver<FileUploadRequest> uploadFile(StreamObserver<FileUploadResponse> responseObserver) {
        return new StreamObserver<FileUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;

            @Override
            public void onNext(FileUploadRequest fileUploadRequest) {
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
                logger.log(INFO, "Complete");
                closeFile(writer);
                status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
                FileUploadResponse response = FileUploadResponse.newBuilder().setStatus(status).build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        };
    }

    private OutputStream getFilePath(FileUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getName() + "." + request.getMetadata().getType();
        return Files.newOutputStream(SERVER_BASE_PATH.resolve("server").resolve(fileName),
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
        String requestedPath = String.valueOf(SERVER_BASE_PATH.resolve("client").resolve(request.getValue()));
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
                    Bytes.newBuilder().setValue(bytes).setSenderId(MainActivity.transportID).setSender(BundleSender.Transport.name()).build());
        });
        if (ex != null) ex.printStackTrace();

        responseObserver.onCompleted();
        logger.log(INFO, "Complete " + requestedPath);
    }
}
