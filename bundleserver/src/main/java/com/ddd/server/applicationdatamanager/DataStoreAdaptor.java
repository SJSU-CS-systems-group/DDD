package com.ddd.server.applicationdatamanager;

import com.ddd.model.ADU;
import com.ddd.server.api.ServiceAdapterClient;
import com.ddd.server.storage.MySQLConnection;
import com.ddd.utils.StoreADUs;
import net.discdd.server.AppData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
    private static final Logger logger = Logger.getLogger(DataStoreAdaptor.class.getName());
    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    public DataStoreAdaptor(String appRootDataDirectory) {
        this.sendADUsStorage = new StoreADUs(appRootDataDirectory + "/send");
        this.receiveADUsStorage = new StoreADUs(appRootDataDirectory + "/receive");
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

//  @Autowired
//  MySQLConnection mysql;

    // get IP address and port for application adaptor server from database
    private String getAppAdapterAddress(String appId) {
        try {
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();
            logger.log(WARNING, "select address from registered_app_adapter_table where app_id='" + appId + "';");

            ResultSet rs =
                    stmt.executeQuery("select address from registered_app_adapter_table where app_id='" + appId + "';");
            String adapterAddress = "";
            while (rs.next()) {
                logger.log(INFO, "max value for app- " + rs.getString(1));
                adapterAddress = rs.getString(1);
            }
            con.close();
            return adapterAddress;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    // store all data for one app received from transport and send to app adapter
    public void persistADUsForServer(String clientId, String appId, List<ADU> adus) throws IOException {
        for (int i = 0; i < adus.size(); i++) {
            this.receiveADUsStorage.addFile(clientId + File.separator + adus.get(i).getAppId(),
                                                this.receiveADUsStorage.getDataFromFile(adus.get(i).getSource()), false);
        }
        List<ADU> dataList = receiveADUsStorage.getAppData(appId, clientId);
        String appAdapterAddress = this.getAppAdapterAddress(appId);
        logger.log(INFO, "[DataStoreAdaptor.persistADUForServer] " + appAdapterAddress);
        String ipAddress = appAdapterAddress.split(":")[0];
        int port = Integer.parseInt(appAdapterAddress.split(":")[1]);
        var client = new ServiceAdapterClient(ipAddress, port);
        var data = client.SendData(clientId, dataList,
                                   this.sendADUsStorage.getLastADUIdReceived(clientId + "/" + appId));

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
                this.sendADUsStorage.addFile(clientId + File.separator + appId, appData.getDataList(i).getData().toByteArray(), false);
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
