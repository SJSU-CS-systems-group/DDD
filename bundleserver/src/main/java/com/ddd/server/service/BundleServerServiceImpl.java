package com.ddd.server.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.bundletransmission.BundleTransmission;
import com.google.protobuf.ByteString;
import edu.sjsu.ddd.bundleserver.service.BundleDownloadRequest;
import edu.sjsu.ddd.bundleserver.service.BundleDownloadResponse;
import edu.sjsu.ddd.bundleserver.service.BundleList;
import edu.sjsu.ddd.bundleserver.service.BundleMetaData;
import edu.sjsu.ddd.bundleserver.service.BundleServiceGrpc.BundleServiceImplBase;
import edu.sjsu.ddd.bundleserver.service.BundleUploadRequest;
import edu.sjsu.ddd.bundleserver.service.BundleUploadResponse;
import edu.sjsu.ddd.bundleserver.service.Status;
import io.grpc.stub.StreamObserver;

@Service
public class BundleServerServiceImpl extends BundleServiceImplBase {
    private String ReceiveDir;
    private String SendDir;
    
    @Autowired
    private BundleTransmission bundleTransmission;
    
    // public BundleServerServiceImpl() {
    //     java.io.File directoryReceive = new java.io.File(ReceiveDir);
    //     if (!directoryReceive.exists()) {
    //         directoryReceive.mkdirs();
    //     }

    //     java.io.File directorySend = new java.io.File(SendDir);
    //     if (!directorySend.exists()) {
    //         directorySend.mkdirs();
    //     }
    // }
    
    @PostConstruct
    private void init() {
        java.io.File directoryReceive = new java.io.File(ReceiveDir);
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }

        java.io.File directorySend = new java.io.File(SendDir);
        if (!directorySend.exists()) {
            directorySend.mkdirs();
        }
    }

    @Value("${bundle-server.bundle-store-shared}")
    public void setDir(String bundleDir) {
        ReceiveDir = bundleDir + java.io.File.separator + "receive";
        SendDir = bundleDir+java.io.File.separator+"send";
    }

    @Override
    public StreamObserver<BundleUploadRequest> uploadBundle(StreamObserver<BundleUploadResponse> responseObserver){
        return new StreamObserver<BundleUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;
            String transportID;

            @Override
            public void onNext(BundleUploadRequest BundleUploadRequest) {
                try{
                    if(BundleUploadRequest.hasMetadata()){
                        transportID = BundleUploadRequest.getMetadata().getTransportId();
                        writer = getFilePath(BundleUploadRequest);
                    } else {
                        writeFile(writer, BundleUploadRequest.getFile().getContent());
                    }
                }catch (IOException e){
                    this.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                System.out.println("Error"+throwable.toString());
                status = Status.FAILED;
                this.onCompleted();
            }

            @Override
            public void onCompleted() {
                System.out.println("Complete");
                try {
                    closeFile(writer);
                } catch (IOException e) {
                    System.out.println("BundleServerServiceImpl.uploadBundle error: "+e.getMessage());
                }
                status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
                BundleUploadResponse response = BundleUploadResponse.newBuilder()
                        .setStatus(status)
                        .build();
                responseObserver.onNext(response);
                bundleTransmission.processReceivedBundles(transportID);
                responseObserver.onCompleted();           
            }
        };
    }
    private OutputStream getFilePath(BundleUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getBid();        
        java.io.File directoryReceive = new java.io.File(ReceiveDir+java.io.File.separator+request.getMetadata().getTransportId());
        if (! directoryReceive.exists()){
            directoryReceive.mkdirs();
        }
        return Files.newOutputStream(Paths.get(ReceiveDir).resolve(request.getMetadata().getTransportId()).resolve(fileName), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeFile(OutputStream writer, ByteString content) throws IOException {
        writer.write(content.toByteArray());
        writer.flush();
    }

    private void closeFile(OutputStream writer) throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    @Override
    public void downloadBundle(BundleDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        String transportFiles = request.getBundleList();
        System.out.println("[BDA] bundles on transport"+transportFiles);
        System.out.println("[BDA]Request from Transport id :"+request.getTransportId());
        String[] filesOnTransport = null;
        Set<String> filesOnTransportSet = Collections.<String>emptySet();
        if (!transportFiles.isEmpty()){
            filesOnTransport = transportFiles.split(",");
            List<String> filesOnTransportList = Arrays.asList(filesOnTransport);
            filesOnTransportSet = new HashSet<String>( filesOnTransportList );
        }
        
        List<File> bundlesList = bundleTransmission.getBundlesForTransmission(request.getTransportId());
        System.out.println(bundleTransmission);
        if ( bundlesList.isEmpty() ){
            BundleTransferDTO bundleTransferDTO = bundleTransmission.generateBundlesForTransmission(request.getTransportId(), filesOnTransportSet);
            if(bundleTransferDTO.getBundles().isEmpty()) {
            	responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
            }
            else {
            	 BundleDownloadResponse response = BundleDownloadResponse.newBuilder()
                         .setBundleList( BundleList.newBuilder().setBundleList(String.join(", ", bundleTransferDTO.getDeletionSet())))
                         .build();
System.out.println("[BDA] Sending "+String.join(", ", bundleTransferDTO.getDeletionSet())+" to delete on Transport id :"+request.getTransportId());
responseObserver.onNext(response);
            }
            responseObserver.onCompleted();                      
        } else {

            for( File bundle : bundlesList ){
                if( !filesOnTransportSet.contains(bundle.getName()) ) {                    
                    System.out.println("[BDA]Downloading "+bundle.getName()+" to Transport id :"+request.getTransportId());
                    BundleMetaData bundleMetaData = BundleMetaData.newBuilder().setBid(bundle.getName()).build();
                    responseObserver.onNext(BundleDownloadResponse.newBuilder().setMetadata(bundleMetaData).build());
                    InputStream in;                 
                    try {
                        in = new FileInputStream(bundle);
                    } catch (Exception ex) {
                        responseObserver.onError(ex);
                        return;
                    }
                    StreamHandler handler = new StreamHandler(in);
                    Exception ex = handler.handle(bytes -> {
                        responseObserver.onNext(BundleDownloadResponse.newBuilder().setFile(edu.sjsu.ddd.bundleserver.service.File.newBuilder().setContent(bytes)).build());
                    });
                    if (ex != null) ex.printStackTrace();
                    responseObserver.onCompleted();
                }
            }
            System.out.println("[BDA] All bundles were transferred completing status success");
            responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
            responseObserver.onCompleted();
        }               
    }
  }