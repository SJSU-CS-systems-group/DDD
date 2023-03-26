package com.ddd.bundletransport.service;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ddd.bundletransport.MainActivity;
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

import io.grpc.stub.StreamObserver;

public class FileServiceImpl extends FileServiceGrpc.FileServiceImplBase {

    private android.content.Context context;
    private Path SERVER_BASE_PATH;

    public FileServiceImpl(Context context) {
        this.context = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.SERVER_BASE_PATH = Paths.get(context.getApplicationInfo().dataDir + "/BundleTransmission");
        }
        File toServer = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            toServer = new File(String.valueOf(SERVER_BASE_PATH.resolve("server")));
            toServer.mkdirs();
        }

        File toClient = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            toClient = new File(String.valueOf(SERVER_BASE_PATH.resolve("client")));
            toClient.mkdirs();
        }
    }

    @Override
    public StreamObserver<FileUploadRequest> uploadFile(StreamObserver<FileUploadResponse> responseObserver) {
        return new StreamObserver<FileUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;

            @Override
            public void onNext(FileUploadRequest fileUploadRequest) {
                try{
                    if(fileUploadRequest.hasMetadata()){
                        writer = getFilePath(fileUploadRequest);
                    }else{
                        writeFile(writer, fileUploadRequest.getFile().getContent());
                    }
                }catch (IOException e){
                    this.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                Log.d(MainActivity.TAG,"Error"+throwable.toString());
                status = Status.FAILED;
                this.onCompleted();
            }

            @Override
            public void onCompleted() {
                Log.d(MainActivity.TAG,"Complete");
                closeFile(writer);
                status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
                FileUploadResponse response = FileUploadResponse.newBuilder()
                        .setStatus(status)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        };
    }

    private OutputStream getFilePath(FileUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getName() + "." + request.getMetadata().getType();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Files.newOutputStream(SERVER_BASE_PATH.resolve("server").resolve(fileName), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return null;
    }

    private void writeFile(OutputStream writer, ByteString content) throws IOException {
        writer.write(content.toByteArray());
        writer.flush();
    }

    private void closeFile(OutputStream writer){
        try {
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void downloadFile(ReqFilePath request, StreamObserver<Bytes> responseObserver) {
        String requestedPath = null ;if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            requestedPath = String.valueOf(SERVER_BASE_PATH.resolve("client").resolve(request.getValue()));
        }
        Log.d(MainActivity.TAG, "Downloading "+requestedPath);
        assert requestedPath != null;
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
            responseObserver.onNext(Bytes.newBuilder().setValue(bytes).build());
        });
        if (ex != null) ex.printStackTrace();

        responseObserver.onCompleted();
    }
}
