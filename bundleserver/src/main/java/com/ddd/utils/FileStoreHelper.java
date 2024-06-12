package com.ddd.utils;

import com.ddd.model.ADU;
import com.ddd.model.Metadata;
import com.ddd.server.applicationdatamanager.ApplicationDataManager;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/*
 * File structure:
 * ----Send
 * ---------ClientID 1
 * ------------AppID A
 * ----------------A.1.txt
 * ----------------metadata.json
 * ------------App ID B
 * ----------------A.1.txt
 * ----------------metadata.json
 * --------ClientID 1
 * ------------AppID B
 * ----------------B.1.txt
 * ----------------metadata.json
 * ------------AppID D
 * ----------------B.1.txt
 * ----------------metadata.json
 * ----Receive
 * ---------ClientID 1
 * ------------AppID A
 * ----------------A.1.txt
 * ----------------metadata.json
 * ------------App ID B
 * ----------------A.1.txt
 * ----------------metadata.json
 * --------ClientID 1
 * ------------AppID B
 * ----------------B.1.txt
 * ----------------metadata.json
 * ------------AppID D
 * ----------------B.1.txt
 * ----------------metadata.json
 * */

public class FileStoreHelper {
    String RootFolder = "";
    private static final Logger logger = Logger.getLogger(FileStoreHelper.class.getName());


    public FileStoreHelper(String rootFolder) {
        RootFolder = rootFolder;
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

    public static String getStringFromFile(String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    public Metadata getMetadata(String folder) {
        try {
            String data = getStringFromFile(RootFolder + "/" + folder + "/metadata.json");
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(SEVERE, "[FileStoreHelper] metadata not found at " + folder + ". create a new one.");
            Metadata metadata = new Metadata(1, 0, 0, 0);
            setMetadata(folder, metadata);
            return metadata;
        }
    }

    public void setMetadata(String folder, Metadata metadata) {
        try {
            File metadataFile = new File(RootFolder + "/" + folder + "/metadata.json");
            metadataFile.getParentFile().mkdirs();
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

    private void registerAppId(String appId) {
        ApplicationDataManager adm = new ApplicationDataManager();
        List<String> appIds = adm.getRegisteredAppIds();

        //check if appId already exists
        for (int i = 0; i < appIds.size(); i++) {
            if (appIds.get(i).equals(appId)) {
                return;
            }
        }
        adm.registerAppId(appId);
    }

    public byte[] getDataFromFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private byte[] readFile(String file) {
        try {
            File f = new File(file);
            FileInputStream fis = new FileInputStream(f);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public List<ADU> getAppData(String appId, String clientId) {
        List<ADU> appDataList = new ArrayList<>();
        String folder = clientId + "/" + appId;
        Metadata metadata = getMetadata(folder);
        for (long i = metadata.lastProcessedMessageId + 1; i <= metadata.lastReceivedMessageId; i++) {
            appDataList.add(new ADU(new File(RootFolder + "/" + folder + "/" + i + ".txt"), appId, i, 0, clientId));
        }
        //metadata.lastProcessedMessageId= metadata.lastReceivedMessageId;
        //setMetadata(folder, metadata);
        return appDataList;
    }

    public long getLastADUIdReceived(String folder) {
        Metadata metadata = getMetadata(folder);
        return metadata.lastReceivedMessageId;
    }

    public byte[] getADU(String clientId, String appId, String aduId) {
        return readFile(RootFolder + "/" + clientId + "/" + appId + "/" + aduId + ".txt");
    }

    public File getADUFile(String clientId, String appId, String aduId) {
        return new File(RootFolder + "/" + clientId + "/" + appId + "/" + aduId + ".txt");
    }

    public byte[] getNextAppData(String folder) {
        Metadata metadata = getMetadata(folder);
        long nextMessageId = metadata.lastProcessedMessageId + 1;
        if (nextMessageId > metadata.lastReceivedMessageId) {
            logger.log(INFO, "no data to show");
            if (nextMessageId > 1) {
                nextMessageId--;
            } else return null;
        }
        byte[] appData = readFile(RootFolder + "/" + folder + "/" + nextMessageId + ".txt");
        metadata.lastProcessedMessageId = nextMessageId;
        setMetadata(folder, metadata);
        return appData;
    }

    public void AddFile(String appId, String clientId, byte data[]) {
        String folder = clientId + "/" + appId;
        File f = new File(RootFolder + "/" + folder);
        if (f.isDirectory()) {
            logger.log(WARNING, "[FileStoreHelper.Addfile] data being added-" + new String(data));
            int noOfMessages = f.list().length;
            logger.log(INFO, "noOfMessages-" + noOfMessages);
            File dataFile = new File(RootFolder + "/" + folder + "/" + noOfMessages + ".txt");
            FileOutputStream oFile;
            try {
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();

                Metadata metadata = getMetadata(folder);
                metadata.lastReceivedMessageId++;
                setMetadata(folder, metadata);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            registerAppId(appId);
            f.mkdirs();
            File metadataFile = new File(RootFolder + "/" + folder + "/metadata.json");
            try {
                new File(RootFolder + "/" + folder).mkdirs();
                metadataFile.createNewFile();
                Gson gson = new Gson();
                Metadata metadata = new Metadata(0, 0, 1, 0);
                String metadataString = gson.toJson(metadata);
                FileOutputStream oFile = new FileOutputStream(metadataFile, false);
                oFile.write(metadataString.getBytes());
                oFile.close();

                File dataFile = new File(RootFolder + "/" + folder + "/1.txt");
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();
            } catch (Exception ex) {
                logger.log(SEVERE, "error" + ex.getMessage());
                ex.printStackTrace();
            }

        }
    }

    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) {
        //check if there are enough files
        String folder = clientId + "/" + appId;
        Metadata metadata = getMetadata(folder);
        if (metadata.lastSentMessageId >= aduId) {
            logger.log(INFO, "[FileStoreHelper.deleteAllFilesUpTo] Data already deleted.");
            return;
        }
        for (long i = metadata.lastSentMessageId + 1; i <= aduId; i++) {
            deleteFile(i + ".txt");
            logger.log(INFO, i + ".txt deleted");
        }

        metadata.lastSentMessageId = aduId;
    }

    public void deleteFile(String fileName) {
        File file = new File(RootFolder + "/" + fileName);
        file.delete();
    }
}
