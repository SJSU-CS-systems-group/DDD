package net.discdd.server.applicationdatamanager;

import net.discdd.server.api.ServiceAdapterClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class BundleServerAduDeliveredListener implements ApplicationDataManager.AduDeliveredListener {
    HashSet
    @Override
    public void onAduDelivered(String clientId, Set<String> appIds) {

    }
    String appAdapterAddress = this.getAppAdapterAddress(appId);
        logger.log(INFO, "[DataStoreAdaptor.persistADUForServer] " + appAdapterAddress);
    String ipAddress = appAdapterAddress.split(":")[0];
    int port = Integer.parseInt(appAdapterAddress.split(":")[1]);
    var client = new ServiceAdapterClient(ipAddress, port);
    var data = client.SendData(clientId, dataList, this.sendADUsStorage.getLastADUIdReceived(clientId, appId));

        if (data != null && dataList.size() > 0) {
        long lastAduIdSent = dataList.get(dataList.size() - 1).getADUId();
        receiveADUsStorage.deleteAllFilesUpTo(clientId, appId, lastAduIdSent);
    }

        this.saveDataFromAdaptor(clientId, appId, data);
}
