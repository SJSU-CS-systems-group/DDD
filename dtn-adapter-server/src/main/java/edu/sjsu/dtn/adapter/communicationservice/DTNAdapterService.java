package edu.sjsu.dtn.adapter.communicationservice;

import com.google.protobuf.ByteString;
import edu.sjsu.dtn.storage.FileStoreHelper;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

public class DTNAdapterService extends DTNAdapterGrpc.DTNAdapterImplBase{
    private static final String ROOT_DIRECTORY = "/Users/adityasinghania/Downloads/Data/DTN-bundle-server-adapter/FileStore";
    @Override
    public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
        FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");

        FileStoreHelper helper = new FileStoreHelper(ROOT_DIRECTORY + "/receive");
        for(int i=0;i< request.getDataCount();i++) {
            helper.AddFile(request.getClientId(), request.getData(i).toByteArray());
            sendHelper.AddFile(request.getClientId(), (new String(request.getData(i).toByteArray())+" was processed").getBytes());
        }


        List<byte[]> dataList = sendHelper.getAppData(request.getClientId());
        List<ByteString> dataListConverted = new ArrayList<>();
        System.out.println("[DTNAdapterService.saveData] data to send: ");
        for (int i=0;i<dataList.size();i++){
            System.out.println("data: "+ByteString.copyFrom(dataList.get(i)));
            dataListConverted.add(ByteString.copyFrom(dataList.get(i)));
        }
        //
        AppData appData = AppData.newBuilder()
                .addAllData(dataListConverted)
                .build();

        responseObserver.onNext(appData);
        responseObserver.onCompleted();
    }

    @Override
    public void prepareData(ClientData request, StreamObserver<PrepareResponse> responseObserver) {
        responseObserver.onNext(PrepareResponse.newBuilder().setCode(StatusCode.SUCCESS).build());
        responseObserver.onCompleted();
    }
}
