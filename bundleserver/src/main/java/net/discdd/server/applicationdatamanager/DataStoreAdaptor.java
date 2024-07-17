package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.server.AppData;
import net.discdd.server.api.ServiceAdapterClient;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Autowired;
import net.discdd.server.repository.RegisteredAppAdapterRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/*
 * SendFileStoreHelper - store data that we get from adapter servers
 * ReceiveFileStoreHelper - store data that we get from transport
 * */

public class DataStoreAdaptor {
    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository;
    private static final Logger logger = Logger.getLogger(DataStoreAdaptor.class.getName());
    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    public DataStoreAdaptor(String appRootDataDirectory) {
        this.sendADUsStorage = new StoreADUs(new File(appRootDataDirectory, "send").toPath(), true);
        this.receiveADUsStorage = new StoreADUs(new File(appRootDataDirectory, "receive").toPath(), false);
    }

    public void deleteADUs(String clientId, String appId, Long aduIdEnd) throws IOException {
        this.sendADUsStorage.deleteAllFilesUpTo(clientId, appId, aduIdEnd);
        logger.log(INFO, "[DataStoreAdaptor] Deleted ADUs for application " + appId + " with id upto " + aduIdEnd);
    }

    public void prepareData(String appId, String clientId) {
        String appAdapterAddress = getAppAdapterAddress(appId);
        logger.log(INFO, "[DataStoreAdaptor.prepareData] " + appAdapterAddress);
        if (appAdapterAddress == null || appAdapterAddress.isEmpty()) {
            logger.log(WARNING, "[DataStoreAdaptor.prepareData] appAdapterAddress not valid");
        }
        String ipAddress = appAdapterAddress.split(":")[0];
        int port = Integer.parseInt(appAdapterAddress.split(":")[1]);
        ServiceAdapterClient client = new ServiceAdapterClient(ipAddress, port);
        client.PrepareData(clientId);
    }

    // get IP address and port for application adaptor server from database
    private String getAppAdapterAddress(String appId) {
        RegisteredAppAdapter registeredAppAdapter = registeredAppAdapterRepository.findByAppId(appId).orElse(null);
        if (registeredAppAdapter == null) {
            return "";
        } else return registeredAppAdapter.getAddress();
    }

    // store all data for one app received from transport and send to app adapter
    public void persistADUsForServer(String clientId, String appId, List<ADU> adus) throws IOException {
        for (int i = 0; i < adus.size(); i++) {
            ADU adu = adus.get(i);
            this.receiveADUsStorage.addADU(clientId, adu.getAppId(), Files.readAllBytes(adu.getSource().toPath()),
                                           adu.getADUId());
        }
        List<ADU> dataList = receiveADUsStorage.getAppData(appId, clientId);
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
        logger.log(WARNING, "[DataStoreAdaptor] Stored ADUs for application " + appId + " for client " + clientId +
                ". number of ADUs - " + data.getDataListCount());
    }

    public ADU fetchADU(String clientId, String appId, long aduId) {
        try {
            File file = this.sendADUsStorage.getADUFile(clientId, appId, aduId + "");
            FileInputStream fis = new FileInputStream(file);
            int fileSize = fis.available();
            ADU adu = new ADU(file, appId, aduId, fileSize, clientId);
            return adu;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    // check if there is adapter
    // create GRPC connection to adapter and ask for data for the client
    public void saveDataFromAdaptor(String clientId, String appId, AppData appData) {
        try {
            for (int i = 0; i < appData.getDataListCount(); i++) {
                this.sendADUsStorage.addADU(clientId, appId, appData.getDataList(i).getData().toByteArray(),
                                            appData.getDataList(i).getAduId());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public List<ADU> fetchADUs(String clientId, String appId, Long aduIdStart) {
        ADU adu;
        List<ADU> ret = new ArrayList<>();
        long aduId = aduIdStart;
        while ((adu = this.fetchADU(clientId, appId, aduId)) != null) {
            ret.add(adu);
            aduId++;
        }
        return ret;
    }
}
