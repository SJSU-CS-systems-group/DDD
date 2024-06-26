package com.ddd.server.api;

import com.ddd.model.ADU;
import com.ddd.utils.StoreADUs;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import net.discdd.server.AppData;
import net.discdd.server.AppDataUnit;
import net.discdd.server.ClientData;
import net.discdd.server.PrepareResponse;
import net.discdd.server.ServiceAdapterGrpc;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

public class ServiceAdapterClient {
    public String ipAddress;
    public int port;
    private static final Logger logger = Logger.getLogger(ServiceAdapterClient.class.getName());
    private ServiceAdapterGrpc.ServiceAdapterBlockingStub blockingStub;
    private ServiceAdapterGrpc.ServiceAdapterStub asyncStub;
    private StoreADUs ADUsStorage;

    public ServiceAdapterClient(String ipAddress, int port) {
        this.ipAddress = ipAddress;
        this.port = port;
        Channel channel = ManagedChannelBuilder.forAddress(ipAddress, port).usePlaintext().build();
        blockingStub = ServiceAdapterGrpc.newBlockingStub(channel);
        asyncStub = ServiceAdapterGrpc.newStub(channel);
    }

    public AppData SendData(String clientId, List<ADU> dataList, long lastADUIdReceived) {
        ServiceAdapterClient client = new ServiceAdapterClient(ipAddress, port);
        try {
            List<AppDataUnit> dataListConverted = new ArrayList<>();
            for (int i = 0; i < dataList.size(); i++) {
                try {
                    byte[] data =
                            ADUsStorage.getStringFromFile(dataList.get(i).getSource().getAbsolutePath()).getBytes();
                    dataListConverted.add(AppDataUnit.newBuilder().setData(ByteString.copyFrom(data))
                                                  .setAduId(dataList.get(i).getADUId()).build());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            AppData data = AppData.newBuilder().setClientId(clientId).addAllDataList(dataListConverted)
                    .setLastADUIdReceived(lastADUIdReceived).build();
            AppData appData = client.blockingStub.saveData(data);
            logger.log(INFO,
                       "[DTNAdapterClient.SendData] response: appData.getDataCount()- " + appData.getDataListCount());
            return appData;
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void PrepareData(String clientId) {
        ServiceAdapterClient client = new ServiceAdapterClient(ipAddress, port);
        try {
            ClientData data = ClientData.newBuilder().setClientId(clientId).build();
            PrepareResponse response = client.blockingStub.prepareData(data);
            logger.log(INFO, "[DTNAdapterClient.PrepareData] response: " + response);
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
    }
}
