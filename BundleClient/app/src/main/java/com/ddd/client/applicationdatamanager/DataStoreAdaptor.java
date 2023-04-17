package com.ddd.client.applicationdatamanager;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.ddd.bundleclient.HelloworldActivity;
import com.ddd.datastore.filestore.FileStoreHelper;
import com.ddd.datastore.providers.MessageProvider;
import com.ddd.model.ADU;
import com.ddd.model.UncompressedPayload;
import com.ddd.wifidirect.WifiDirectBroadcastReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DataStoreAdaptor {

    private FileStoreHelper sendFileStoreHelper;
    private FileStoreHelper receiveFileStoreHelper;
    private Context applicationContext;
    //ContentResolver contentResolver;
/*
    public DataStoreAdaptor(ContentResolver contentResolver){
        this.contentResolver = contentResolver;
    }*/

    public DataStoreAdaptor(String appRootDataDirectory){
        sendFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/send");
        receiveFileStoreHelper = new FileStoreHelper(appRootDataDirectory + "/receive");
    }

    private void sendDataToApp(ADU adu){
        //notify app that someone sent data for the app
        Intent intent = new Intent("android.intent.dtn.SEND_DATA");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        Log.d(HelloworldActivity.TAG,new String(receiveFileStoreHelper.getDataFromFile(adu.getSource()))+", Source:"+adu.getSource());
        intent.putExtra(Intent.EXTRA_TEXT, receiveFileStoreHelper.getDataFromFile(adu.getSource()));
        if(applicationContext==null) applicationContext = HelloworldActivity.ApplicationContext;
        applicationContext.startService(intent);
    }

    public void persistADU(ADU adu) {
        Log.d(HelloworldActivity.TAG,"Persisting ADUs: " + adu.getADUId() +","+ adu.getSource());
        receiveFileStoreHelper.AddFile(adu.getAppId(), receiveFileStoreHelper.getDataFromFile(adu.getSource()));
        sendDataToApp(adu);
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
            Log.d(HelloworldActivity.TAG, "size:"+fileSize);
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
            Log.d(HelloworldActivity.TAG,adu.getADUId() + adu.getAppId());
            ret.add(adu);
            aduId++;
        }
        return ret;
    }
}