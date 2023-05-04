package com.ddd.server.api;

import com.ddd.model.ADU;
import com.ddd.utils.FileStoreHelper;
import com.google.protobuf.ByteString;
import edu.sjsu.dtn.adapter.communicationservice.*;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.List;

public class DTNAdapterClient {
    public String ipAddress;
    public int port;
    private DTNAdapterGrpc.DTNAdapterBlockingStub blockingStub;
    private DTNAdapterGrpc.DTNAdapterStub asyncStub;
    public DTNAdapterClient(String ipAddress, int port){
        this.ipAddress = ipAddress;
        this.port = port;
        Channel channel = ManagedChannelBuilder.forAddress(ipAddress, port)
                .usePlaintext()
                .build();
        blockingStub = DTNAdapterGrpc.newBlockingStub(channel);
        asyncStub = DTNAdapterGrpc.newStub(channel);
    }

    public AppData SendData(String clientId, List<ADU> dataList, long lastADUIdReceived){
        DTNAdapterClient client = new DTNAdapterClient(ipAddress, port);
        try {
            List<AppDataUnit> dataListConverted = new ArrayList<>();
            for (int i=0;i<dataList.size();i++){
                try {
                    byte[] data = FileStoreHelper.getStringFromFile(dataList.get(i).getSource().getAbsolutePath()).getBytes();
                    dataListConverted.add(AppDataUnit.newBuilder()
                            .setData(ByteString.copyFrom(data))
                            .setAduId(dataList.get(i).getADUId())
                            .build());
                }catch (Exception ex){
                    ex.printStackTrace();
                }
            }
            AppData data = AppData.newBuilder()
                    .setClientId(clientId)
                    .addAllDataList(dataListConverted)
                    .setLastADUIdReceived(lastADUIdReceived)
                    .build();
            AppData appData = client.blockingStub.saveData(data);
            System.out.println("[DTNAdapterClient.SendData] response: appData.getDataCount()- " + appData.getDataListCount());
            return appData;
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void PrepareData(String clientId){
        DTNAdapterClient client = new DTNAdapterClient(ipAddress, port);
        try {
            ClientData data = ClientData.newBuilder()
                    .setClientId(clientId)
                    .build();
            PrepareResponse response = client.blockingStub.prepareData(data);
            System.out.println("[DTNAdapterClient.PrepareData] response: "+response);
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
    }
}
