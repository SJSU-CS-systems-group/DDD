package com.ddd.server.applicationdatamanager;

import com.ddd.model.ADU;
import com.ddd.server.api.DTNAdapterClient;
import com.ddd.server.storage.MySQLConnection;
import com.ddd.utils.ADUUtils;
import com.ddd.utils.FileStoreHelper;
import edu.sjsu.dtn.adapter.communicationservice.AppData;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.FileSystems;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DataStoreAdaptor {
    private FileStoreHelper sendFileStoreHelper;
    private FileStoreHelper receiveFileStoreHelper;

    public DataStoreAdaptor(String appRootDataDirectory){
        sendFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/send");
        receiveFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/receive");
    }

    public void deleteADUs(String clientId, String appId, Long aduIdEnd) {
        sendFileStoreHelper.deleteAllFilesUpTo(clientId, appId, aduIdEnd);
        System.out.println("[DSA] Deleted ADUs for application " + appId + " with id upto " + aduIdEnd);
    }

    //get IP address and port for application adaptor server from database
    private String getAppAdapterAddress(String appId){
        try{
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();

            ResultSet rs = stmt.executeQuery("select address from registered_app_adapter_table where app_id='"+appId+"';");
            String adapterAddress="";
            while(rs.next()) {
                System.out.println("max value for app- "+rs.getString(1) );
                adapterAddress = rs.getString(1);
            }
            con.close();
            return  adapterAddress;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return "";
    }

    //store all data for one app received from transport and send to app adapter
    public void persistADUsForServer(String clientId, String appId, List<ADU> adus) {
        List<byte[]> dataList=new ArrayList<>();
        for(int i=0;i<adus.size();i++) {
            receiveFileStoreHelper.AddFile(adus.get(i).getAppId(), clientId, receiveFileStoreHelper.getDataFromFile(adus.get(i).getSource()));
            dataList.add(receiveFileStoreHelper.getDataFromFile(adus.get(i).getSource()));
        }
        String appAdapterAddress = getAppAdapterAddress(appId);
        System.out.println("[DataStoreAdaptor.persistADUForServer] "+appAdapterAddress);
        String ipAddress = appAdapterAddress.split(":")[0];
        int port = Integer.parseInt(appAdapterAddress.split(":")[1]);
        DTNAdapterClient client = new DTNAdapterClient(ipAddress, port);
        AppData data = client.SendData(clientId, dataList);
        saveDataFromAdaptor(clientId, appId, data);
        System.out.println(
                "[DSA] Stored ADUs for application "
                        + appId
                        + " for client "
                        + clientId);
    }

    private ADU fetchADU(String clientId, String appId, long aduId) {
        try {
            File file = sendFileStoreHelper.getADUFile(clientId, appId, aduId + "");
            FileInputStream fis = new FileInputStream(file);
            int fileSize = fis.available();
            ADU adu = new ADU(file, appId, aduId, fileSize, clientId);
            return adu;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    //check if there is adapter
    //create GRPC connection to adapter and ask for data for the client
    public void saveDataFromAdaptor(String clientId, String appId, AppData appData){
        try {
            for(int i=0;i<appData.getDataCount();i++){
                receiveFileStoreHelper.AddFile(appId, clientId, appData.getData(i).toByteArray());
            }
        } catch (Exception ex){
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
