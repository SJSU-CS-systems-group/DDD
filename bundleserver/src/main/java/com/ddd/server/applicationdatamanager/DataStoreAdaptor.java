package com.ddd.server.applicationdatamanager;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.ddd.model.ADU;
import com.ddd.server.api.DTNAdapterClient;
import com.ddd.server.storage.MySQLConnection;
import com.ddd.utils.FileStoreHelper;
import edu.sjsu.dtn.adapter.communicationservice.AppData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static java.util.logging.Level.*;

/*
 * SendFileStoreHelper - store data that we get from adapter servers
 * ReceiveFileStoreHelper - store data that we get from transport
 * */

public class DataStoreAdaptor {
    private static final Logger logger = Logger.getLogger(DataStoreAdaptor.class.getName());
    private FileStoreHelper sendFileStoreHelper;
    private FileStoreHelper receiveFileStoreHelper;

    public DataStoreAdaptor(String appRootDataDirectory) {
        this.sendFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/send");
        this.receiveFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/receive");
    }

    public void deleteADUs(String clientId, String appId, Long aduIdEnd) {
        this.sendFileStoreHelper.deleteAllFilesUpTo(clientId, appId, aduIdEnd);
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
        DTNAdapterClient client = new DTNAdapterClient(ipAddress, port);
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
    public void persistADUsForServer(String clientId, String appId, List<ADU> adus) {
        for (int i = 0; i < adus.size(); i++) {
            this.receiveFileStoreHelper.AddFile(adus.get(i).getAppId(), clientId,
                                                this.receiveFileStoreHelper.getDataFromFile(adus.get(i).getSource()));
        }
        List<ADU> dataList = receiveFileStoreHelper.getAppData(appId, clientId);
        String appAdapterAddress = this.getAppAdapterAddress(appId);
        logger.log(INFO, "[DataStoreAdaptor.persistADUForServer] " + appAdapterAddress);
        String ipAddress = appAdapterAddress.split(":")[0];
        int port = Integer.parseInt(appAdapterAddress.split(":")[1]);
        DTNAdapterClient client = new DTNAdapterClient(ipAddress, port);
        AppData data = client.SendData(clientId, dataList,
                                       this.sendFileStoreHelper.getLastADUIdReceived(clientId + "/" + appId));

        if (data != null && dataList.size() > 0) {
            long lastAduIdSent = dataList.get(dataList.size() - 1).getADUId();
            receiveFileStoreHelper.deleteAllFilesUpTo(clientId, appId, lastAduIdSent);
        }

        this.saveDataFromAdaptor(clientId, appId, data);
        logger.log(WARNING,
                   "[DataStoreAdaptor] Stored ADUs for application " + appId + " for client " + clientId + ". number of ADUs - " +
                           data.getDataListCount());
    }

    public ADU fetchADU(String clientId, String appId, long aduId) {
        try {
            File file = this.sendFileStoreHelper.getADUFile(clientId, appId, aduId + "");
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
                this.sendFileStoreHelper.AddFile(appId, clientId, appData.getDataList(i).getData().toByteArray());
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
