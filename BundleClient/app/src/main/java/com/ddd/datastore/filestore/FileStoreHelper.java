package com.ddd.datastore.filestore;

import android.net.Uri;
import android.util.Log;

import com.ddd.client.applicationdatamanager.ApplicationDataManager;
import com.ddd.datastore.model.Metadata;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
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

    public static String convertStreamToString(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    public static String getStringFromFile (String filePath) throws IOException {
        File fl = new File(filePath);
        System.out.println(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    private Metadata getMetadata(String folder) throws IOException{
        String data = getStringFromFile(RootFolder + File.separator + folder + File.separator + "metadata.json");
        Gson gson = new Gson();
        return gson.fromJson(data, Metadata.class);
    }

    private Metadata getIfNotCreateMetadata(String folder) throws IOException {
        try {
            return getMetadata(folder);
        } catch (FileNotFoundException e) {
            setMetadata(folder, new Metadata(0, 0,0,0));
            return getMetadata(folder);
        }
    }

    private void setMetadata(String folder, Metadata metadata) throws IOException{
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        writeFile(RootFolder + File.separator + folder + File.separator + "metadata.json", metadataString.getBytes());
    }

    public byte[] getDataFromFile(File file){
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            fis.close();
            return res;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    private byte[] readFile(String file){
        try {
            File f = new File(file);
            return getDataFromFile(f);
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    private void writeFile(File file, byte[] data) throws IOException {
        FileOutputStream oFile = new FileOutputStream(file, false);
        oFile.write(data);
        oFile.close();
    }

    private void writeFile(String filePath, byte[] data) throws IOException {
        File file = new File(filePath);
        file.createNewFile();
        writeFile(file, data);
    }

    public List<byte[]> getAllAppData(String appId) throws IOException{
        List<byte[]> appDataList = new ArrayList<>();
        String folder = RootFolder+File.separator+appId;
        Metadata metadata = getIfNotCreateMetadata(folder);
        for(long i=metadata.lastProcessedMessageId+1;i <= metadata.lastReceivedMessageId;i++){
            appDataList.add(readFile(folder+File.separator+i+".txt"));
        }
        //metadata.lastProcessedMessageId= metadata.lastReceivedMessageId;
        //setMetadata(folder, metadata);
        return appDataList;
    }

    public byte[] getADU(String appId, String aduId){
        return readFile(RootFolder+File.separator+ appId+File.separator+aduId+".txt");
    }

    public File getADUFile(String appId, String aduId){
        return new File(RootFolder+File.separator+ appId+File.separator+aduId+".txt");
    }

    public byte[] getNextAppData(String folder) throws IOException{
        Metadata metadata = getIfNotCreateMetadata(folder);
        long nextMessageId = metadata.lastProcessedMessageId+1;
        if(nextMessageId > metadata.lastReceivedMessageId){
            Log.d("bundleclient","no data to show");
            if(nextMessageId>1){
                nextMessageId--;
            }else {
                return null;
            }
        }
        byte[] appData = readFile(RootFolder + File.separator + folder+File.separator+nextMessageId+".txt");
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

    public Uri addFile(String folder, byte data[]) throws IOException {
        File f = new File(RootFolder+File.separator+folder);

        String messagePath;
        if(f.isDirectory()){
            Log.d("bundleclient", RootFolder+File.separator+folder+" is a directory");
            int noOfMessages = f.list().length;
            Log.d("bundleclient", "noOfMessages-"+noOfMessages);
            messagePath = f.getPath()+File.separator+(noOfMessages+1)+".txt";
        }else{
            //first ADU for an application
            registerAppId(folder);
            f.mkdirs();
            messagePath = f.getPath() +File.separator+ "1.txt";
        }

        Metadata metadata = getIfNotCreateMetadata(folder);
        metadata.lastReceivedMessageId++;
        setMetadata(folder, metadata);
        writeFile(messagePath, data);
        return Uri.parse(messagePath);
    }

    public void deleteAllFilesUpTo(String appId, long aduId) throws IOException{
        //check if there are enough files
        String folder = appId;
        Metadata metadata = getIfNotCreateMetadata(folder);
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
        File file = new File(RootFolder+File.separator+fileName);
        file.delete();
    }
}
