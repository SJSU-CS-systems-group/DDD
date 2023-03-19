package com.ddd.client.applicationdatamanager;

import android.content.ContentValues;
import android.net.Uri;

import com.ddd.datastore.filestore.FileStoreHelper;
import com.ddd.datastore.providers.MessageProvider;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DataStoreAdaptor {

    private FileStoreHelper sendFileStoreHelper;
    private FileStoreHelper receiveFileStoreHelper;
    //ContentResolver contentResolver;
/*
    public DataStoreAdaptor(ContentResolver contentResolver){
        this.contentResolver = contentResolver;
    }*/

    public DataStoreAdaptor(String appRootDataDirectory){
        sendFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/send");
        receiveFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/receive");
    }

    public void persistADU(ADU adu) {
        ContentValues values=new ContentValues();
        values.put(MessageProvider.message, adu.getSource().toString());
        receiveFileStoreHelper.AddFile(adu.getAppId(), receiveFileStoreHelper.getDataFromFile(adu.getSource()));
        System.out.println(
                "[ADM-DSA] Persisting inbound ADU "
                        + adu.getAppId()
                        + "-"
                        + adu.getADUId()
                        + " to the Data Store");


    }

    /*public void deleteADU(long aduId){
        receiveFileStoreHelper.deleteFile(aduId+"");
    }*/

    public void deleteADUs(String appId, long aduIdEnd) {

        sendFileStoreHelper.deleteAllFilesUpTo(appId, aduIdEnd);
        System.out.println("[DSA] Deleted ADUs for application " + appId + " with id upto " + aduIdEnd);
        System.out.println(
                "[ADM-DSA] Deleting outbound ADUs of application " + appId + " upto id " + aduIdEnd);
    }

    private ADU fetchADU(String appId, long aduId) {
        try {
            File file = sendFileStoreHelper.getADUFile(appId, aduId + "");
            FileInputStream fis = new FileInputStream(file);
            int fileSize = fis.available();
            ADU adu = new ADU(file, appId, aduId, fileSize);
            return adu;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    public List<ADU> fetchADUs(String appId, long aduIdStart) {
        ADU adu = null;
        List<ADU> ret = new ArrayList<>();
        long aduId = aduIdStart;
        while ((adu = this.fetchADU(appId, aduId)) != null) {
            ret.add(adu);
            aduId++;
        }
        return ret;
    }
}