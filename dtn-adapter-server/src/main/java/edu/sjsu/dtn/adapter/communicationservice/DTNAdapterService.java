package edu.sjsu.dtn.adapter.communicationservice;

import com.google.protobuf.ByteString;
import edu.sjsu.dtn.storage.FileStoreHelper;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

public class DTNAdapterService extends DTNAdapterGrpc.DTNAdapterImplBase{
    private static final String ROOT_DIRECTORY = "C:\\Users\\dmuna\\Documents\\java\\DTN-bundle-server-adapter\\FileStore";
    @Override
    public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
        FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");
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

        FileStoreHelper helper = new FileStoreHelper(ROOT_DIRECTORY + "/receive");
        helper.AddFile(request.getClientId(), request.getData(0).toByteArray());
        responseObserver.onNext(appData);
        responseObserver.onCompleted();
    }

    @Override
    public void prepareData(ClientData request, StreamObserver<Status> responseObserver) {
        responseObserver.onNext(Status.newBuilder().setCode(StatusCode.SUCCESS).build());
        responseObserver.onCompleted();
    }
}
