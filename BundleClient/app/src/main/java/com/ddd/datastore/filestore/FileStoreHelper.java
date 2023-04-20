package com.ddd.datastore.filestore;

import android.util.Log;

import com.ddd.client.applicationdatamanager.ApplicationDataManager;
import com.ddd.datastore.model.Metadata;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FileStoreHelper {
    private String RootFolder="";
    private String appFolder="";
    public FileStoreHelper(String rootFolder){
        RootFolder = rootFolder;
    }

    public FileStoreHelper(String rootFolder, String appFolder){
        RootFolder = rootFolder;
        this.appFolder = appFolder;
    }

    public static String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    public static String getStringFromFile (String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    public Metadata getMetadata(String folder){
        try {
            String data = getStringFromFile(RootFolder + "/" + folder + "/metadata.json");
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void setMetadata(String folder, Metadata metadata){
        try {
            File metadataFile = new File(RootFolder + "/" + folder + "/metadata.json");
            metadataFile.createNewFile();
            Gson gson = new Gson();
            String metadataString = gson.toJson(metadata);
            FileOutputStream oFile = new FileOutputStream(metadataFile, false);
            oFile.write(metadataString.getBytes());
            oFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public byte[] getDataFromFile(File file){
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    private byte[] readFile(String file){
        try {
            File f = new File(file);
            FileInputStream fis = new FileInputStream(f);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    public List<byte[]> getAppData(String appId){
        List<byte[]> appDataList = new ArrayList<>();
        String folder = RootFolder+"/"+appId;
        Metadata metadata = getMetadata(folder);
        for(long i=metadata.lastProcessedMessageId+1;i <= metadata.lastReceivedMessageId;i++){
            appDataList.add(readFile(folder+"/"+i+".txt"));
        }
        //metadata.lastProcessedMessageId= metadata.lastReceivedMessageId;
        //setMetadata(folder, metadata);
        return appDataList;
    }

    public byte[] getADU(String appId, String aduId){
        return readFile(RootFolder+"/"+ appId+"/"+aduId+".txt");
    }

    public File getADUFile(String appId, String aduId){
        return new File(RootFolder+"/"+ appId+"/"+aduId+".txt");
    }

    public byte[] getNextAppData(String folder){
        Metadata metadata = getMetadata(folder);
        if(metadata==null){
            metadata = new Metadata(1, 0,0,0);
            setMetadata(folder, metadata);
        }
        long nextMessageId = metadata.lastProcessedMessageId+1;
        if(nextMessageId> metadata.lastReceivedMessageId){
            Log.d("deepak","no data to show");
            if(nextMessageId>1){
                nextMessageId--;
            }else
                return null;
        }
        byte[] appData = readFile(RootFolder + "/" + folder+"/"+nextMessageId+".txt");
        metadata.lastProcessedMessageId = nextMessageId;
        setMetadata(folder, metadata);
        return appData;
    }

    private void registerAppId(String appId){
        ApplicationDataManager adm = new ApplicationDataManager(appFolder);
        List<String> appIds = adm.getRegisteredAppIds();

        //check if appId already exists
        for(int i=0;i<appIds.size();i++) {
            if(appIds.get(i).equals(appId)){
                return;
            }
        }
        adm.registerAppId(appId);
    }

    public void AddFile(String folder, byte data[]){
        File f = new File(RootFolder+"/"+folder);
        if(f.isDirectory()){
            Log.d("deepak", RootFolder+"/"+folder+" is a directory");
            int noOfMessages = f.list().length;
            Log.d("deepak", "noOfMessages-"+noOfMessages);
            File dataFile = new File(RootFolder+"/"+folder+"/"+noOfMessages+".txt");
            FileOutputStream oFile = null;
            try {
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();

                Metadata metadata = getMetadata(folder);
                metadata.lastReceivedMessageId++;
                setMetadata(folder, metadata);
            } catch (Exception e) {
                Log.d("deepak", "Error: "+e.getMessage());
                e.printStackTrace();
            }
        }else{
            //first ADU for an application
            registerAppId(folder);
            f.mkdirs();
            File metadataFile = new File(RootFolder +"/"+ folder + "/metadata.json");
            try {
                new File(RootFolder +"/"+ folder).mkdirs();
                metadataFile.createNewFile();
                Gson gson = new Gson();
                Metadata metadata = new Metadata(0, 0,1,0);
                String metadataString = gson.toJson(metadata);
                FileOutputStream oFile = new FileOutputStream(metadataFile, false);
                oFile.write(metadataString.getBytes());
                oFile.close();

                File dataFile = new File(RootFolder +"/"+ folder +"/1.txt");
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();
            }catch(Exception ex){
                Log.d("deepak", "error"+ex.getMessage());
                ex.printStackTrace();
            }

        }
    }

    public void deleteAllFilesUpTo(String appId, long aduId){
        //check if there are enough files
        String folder = RootFolder+"/"+appId;
        Metadata metadata = getMetadata(folder);
        if(metadata.lastSentMessageId >= aduId){
            System.out.println("[FileStoreHelper.deleteAllFilesUpTo] Data already deleted.");
            return;
        }
        for(long i = metadata.lastSentMessageId+1 ; i<=aduId;i++){
            deleteFile(i+".txt");
            System.out.println(i+".txt deleted");
        }

        metadata.lastSentMessageId = aduId;
    }

    public void deleteFile(String fileName){
        File file = new File(RootFolder+"/"+fileName);
        file.delete();
    }
}
