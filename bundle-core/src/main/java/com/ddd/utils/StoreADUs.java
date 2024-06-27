package com.ddd.utils;

import com.ddd.model.ADU;
import com.ddd.model.Metadata;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.*;
import static java.util.logging.Level.INFO;

public class StoreADUs {
    public File rootFolder;
    private static final Logger logger = Logger.getLogger(StoreADUs.class.getName());
    private boolean forSending;

    public StoreADUs(File rootFolder, boolean forSending) {
        logger.log(FINE, "bundlecore", "rootFolder: " + rootFolder);
        this.rootFolder = rootFolder;
        this.forSending = forSending;
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

    public Metadata getMetadata(File folder) throws IOException {
        try {
            String data = Files.readString(rootFolder.toPath().resolve(folder.toPath().resolve( "metadata.json")));
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(SEVERE, "[FileStoreHelper] metadata not found at " + folder + ". create a new one.");
            Metadata metadata = new Metadata(1, 0, 0, 0);
            setMetadata(folder, metadata);
            return metadata;
        }
    }
    public void setMetadata(File folder, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        File file = new File(rootFolder, folder + "/metadata.json");
        file.getParentFile().mkdirs();
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(metadataString.getBytes());
        oFile.close();
    }
    public Metadata getIfNotCreateMetadata(File folder) throws IOException {
        try {
            return getMetadata(folder);
        } catch (FileNotFoundException e) {
            setMetadata(folder, new Metadata(0, 0, 0, 0));
            return getMetadata(folder);
        }
    }
    public List<ADU> getAppData(String appId, String clientId) throws IOException {
        List<ADU> appDataList = new ArrayList<>();
        var folder = new File(clientId, appId);
        Metadata metadata = getMetadata(folder);
        for (long i = metadata.lastProcessedMessageId + 1; i <= metadata.lastReceivedMessageId; i++) {
            appDataList.add(new ADU(new File(rootFolder + "/" + folder + "/" + i + ".txt"), appId, i, 0, clientId));
        }
        return appDataList;
    }
    public List<byte[]> getAllAppData(String appId) throws IOException {
        List<byte[]> dataList = new ArrayList<>();
        Metadata metadata = getIfNotCreateMetadata(new File (appId));
        var folder = new File(rootFolder, appId);
        for (long i = 1; i <= metadata.lastReceivedMessageId; i++) {
            byte[] data = Files.readAllBytes(new File (folder, i + ".txt").toPath());
            logger.log(FINE, "bundleclient", data.toString());
            dataList.add(data);
        }

        return dataList;
    }

    private void deleteFile(String fileName) {
        File file = new File(rootFolder + File.separator + fileName);
        file.delete();
    }
    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) throws IOException {
        //check if there are enough files
        var folder = clientId == null? Paths.get(appId) : Paths.get(clientId, appId);
        Metadata metadata = getIfNotCreateMetadata(folder.toFile());
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
        var appFolder = getAppFolder(clientId, appId);
        var adu = Files.readAllBytes(appFolder.resolve(aduId + ".txt"));
        return adu;
    }

    private Path getAppFolder(String clientId, String appId) {
        return clientId == null? rootFolder.toPath().resolve(appId) : rootFolder.toPath().resolve(Paths.get(clientId, appId));
    }

    public File getADUFile(String clientId, String appId, String aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        return appFolder.resolve(aduId + ".txt").toFile();
    }

    /**
     * @param clientId
     * @param appId
     * @param data
     * @param aduId if -1 we will set to next ID
     * @return
     * @throws IOException
     */
    public File addADU(String clientId, String appId, byte[] data, long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        var folder = appFolder.toFile();
        Metadata metadata = getIfNotCreateMetadata(folder);
        var lastAduId = forSending ? metadata.lastSentMessageId : metadata.lastReceivedMessageId;
        if (aduId != -1) {
            aduId = ++lastAduId;
        }
        else if (aduId <= lastAduId) {
            return null;
        }
        if (forSending) {
            metadata.lastSentMessageId = aduId;
        } else {
            metadata.lastReceivedMessageId = aduId;
        }
        setMetadata(folder, metadata);
        var file = new File(folder, aduId + ".txt");
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(data);
        oFile.close();
        return file;
    }
    public long getLastADUIdReceived(String clientId, String appId) throws IOException {
        Metadata metadata = getMetadata(clientId == null? new File(appId) : new File(clientId, appId));
        return metadata.lastReceivedMessageId;
    }
}
