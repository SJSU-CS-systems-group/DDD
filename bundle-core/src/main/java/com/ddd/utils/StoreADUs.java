package com.ddd.utils;

import com.ddd.model.ADU;
import com.ddd.model.Metadata;
import com.google.gson.Gson;

import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.*;
import static java.util.logging.Level.INFO;

public class StoreADUs {
    public String RootFolder;
    private String appFolder;
    private static final Logger logger = Logger.getLogger(StoreADUs.class.getName());


    public StoreADUs(String rootFolder) {
        logger.log(FINE, "bundelclient", "rootFolder: " + rootFolder);
        RootFolder = rootFolder;
    }
    public StoreADUs(String rootFolder, String appFolder) {
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
    public static String getStringFromFile(String filePath) throws IOException {
        File fl = new File(filePath);
        System.out.println(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }
    public Metadata getMetadata(String folder) throws IOException {
        try {
            String data = getStringFromFile(RootFolder + File.separator + folder + File.separator + "metadata.json");
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(SEVERE, "[FileStoreHelper] metadata not found at " + folder + ". create a new one.");
            Metadata metadata = new Metadata(1, 0, 0, 0);
            setMetadata(folder, metadata);
            return metadata;
        }
    }
    public void setMetadata(String folder, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        File file = new File(RootFolder + "/" + folder + "/metadata.json");
        file.getParentFile().mkdirs();
        file.createNewFile();
        FileOutputStream oFile = new FileOutputStream(file, false);
        oFile.write(metadataString.getBytes());
        oFile.close();
    }
    public Metadata getIfNotCreateMetadata(String folder) throws IOException {
        try {
            return getMetadata(folder);
        } catch (FileNotFoundException e) {
            setMetadata(folder, new Metadata(0, 0, 0, 0));
            return getMetadata(folder);
        }
    }
    public byte[] getDataFromFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] res = new byte[fis.available()];
        fis.read(res);
        fis.close();
        return res;
    }
    public byte[] readFile(String file) throws IOException {
        File f = new File(file);
        return getDataFromFile(f);
    }
    public List<ADU> getAppData(String appId, String clientId) throws IOException {
        List<ADU> appDataList = new ArrayList<>();
        String folder = clientId + "/" + appId;
        Metadata metadata = getMetadata(folder);
        for (long i = metadata.lastProcessedMessageId + 1; i <= metadata.lastReceivedMessageId; i++) {
            appDataList.add(new ADU(new File(RootFolder + "/" + folder + "/" + i + ".txt"), appId, i, 0, clientId));
        }
        return appDataList;
    }
    public List<byte[]> getAllAppData(String appId) throws IOException {
        List<byte[]> dataList = new ArrayList<>();
        Metadata metadata = getIfNotCreateMetadata(appId);
        String folder = RootFolder + File.separator + appId;
        for (long i = 1; i <= metadata.lastReceivedMessageId; i++) {
            byte[] data = readFile(folder + File.separator + i + ".txt");
            logger.log(FINE, "bundleclient", data.toString());
            dataList.add(data);
        }

        return dataList;
    }

    private void deleteFile(String fileName) {
        File file = new File(RootFolder + File.separator + fileName);
        file.delete();
    }
    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) throws IOException {
        //check if there are enough files
        var folder = clientId == null? Paths.get(appId) : Paths.get(clientId, appId);
        Metadata metadata = getIfNotCreateMetadata(String.valueOf(folder));
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
    public byte[] getADU(String clientId, String appId, String aduId) throws IOException {
        //TODO: will update client usages to pass null for clientID
        var adu = clientId == null? readFile(RootFolder + File.separator + appId + File.separator + aduId + ".txt") : readFile(RootFolder + File.separator + clientId + File.separator + appId + File.separator + aduId + ".txt");
        return adu;
    }
    public File getADUFile(String clientId, String appId, String aduId) throws IOException {
        //TODO: will update client usages to pass null for clientID
        var aduFile = clientId == null? new File(RootFolder + File.separator + appId + File.separator + aduId + ".txt") : new File(RootFolder + File.separator + clientId + File.separator + appId + File.separator + aduId + ".txt");
        return aduFile;
    }

    //TODO: will update server usages to pass clientId + "/" + appId for folder
    //TODO: will update server usages to camelCase instead of PascalCase calls
    /** question: is isClient too hacky? Should i use String UserRole,
     * role, or context instead? Should i approach this entirely diff?*/
    public File addFile(String folder, byte data[], boolean isClient) throws IOException {
        File f = new File(RootFolder + File.separator + folder);
        int numFile = f.list().length;
        //server filepath is: root, client, app, num, txt
        //client filepath is: root, num, txt
        var file = isClient? new File(RootFolder + "/" + folder + "/" + numFile + ".txt") : new File(f.getPath() + File.separator + numFile + ".txt");
        Metadata metadata = getIfNotCreateMetadata(folder);
        metadata.lastReceivedMessageId++;
        setMetadata(folder, metadata);
        file.createNewFile();
        FileOutputStream oFile = new FileOutputStream(file, false);
        oFile.write(data);
        oFile.close();
        return file;
    }
    public long getLastADUIdReceived(String folder) throws IOException {
        Metadata metadata = getMetadata(folder);
        return metadata.lastReceivedMessageId;
    }
}
