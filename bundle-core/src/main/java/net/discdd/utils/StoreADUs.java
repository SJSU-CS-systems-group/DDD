package net.discdd.utils;

import com.google.protobuf.MapEntry;
import net.discdd.model.ADU;
import net.discdd.model.Metadata;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
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
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

    public Metadata getMetadata(String clientId, String appId) throws IOException {
        Path metadataPath = getAppFolder(clientId, appId).resolve("metadata.json");
        try {
            String data = new String(Files.readAllBytes(metadataPath));
            logger.log(INFO, "metadata path " + metadataPath);
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(SEVERE, "[FileStoreHelper] metadata not found at " + metadataPath + ". create a new one.");
            Metadata metadata = new Metadata(0, 0, 0, 0);
            setMetadata(clientId, appId, metadata);
            return metadata;
        }
    }

    private void setMetadata(String clientId, String appId, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        Path folder = getAppFolder(clientId, appId);
        File file = folder.resolve("metadata.json").toFile();

        logger.log(INFO, "[Set] metadata path " + file);

        file.getParentFile().mkdirs();
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(metadataString.getBytes());
        oFile.close();
    }

    private Metadata getIfNotCreateMetadata(String clientId, String appId) throws IOException {
        try {
            return getMetadata(clientId, appId);
        } catch (FileNotFoundException e) {
            setMetadata(clientId, appId, new Metadata(0, 0, 0, 0));
            return getMetadata(clientId, appId);
        }
    }

    public List<ADU> getAppData(String clientId, String appId) throws IOException {
        List<ADU> appDataList = new ArrayList<>();
        var folder = new File(clientId, appId);
        Metadata metadata = getMetadata(clientId, appId);
        for (long i = metadata.lastProcessedMessageId + 1; i <= metadata.lastReceivedMessageId; i++) {
            appDataList.add(new ADU(new File(rootFolder + "/" + folder + "/" + i + ".txt"), appId, i, 0, clientId));
        }
        return appDataList;
    }

    public record ClientApp(String clientId, String appId) {}
    public Stream<ClientApp> getAllClientApps() {
        return Stream.of(rootFolder.listFiles())
                .filter(File::isDirectory)
                .map(File::getName)
                .flatMap(clientId -> Stream.of(new File(rootFolder, clientId).listFiles())
                        .filter(File::isDirectory)
                        .map(File::getName)
                        .map(appId -> new ClientApp(clientId, appId)));
    }

    public List<byte[]> getAllAppData(String appId) throws IOException {
        List<byte[]> dataList = new ArrayList<>();
        Metadata metadata = getIfNotCreateMetadata(null, appId);
        var folder = new File(rootFolder, appId);
        for (long i = 1; i <= metadata.lastReceivedMessageId; i++) {
            byte[] data = Files.readAllBytes(new File(folder, i + ".txt").toPath());
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
        var folder = clientId == null ? Paths.get(appId) : Paths.get(clientId, appId);
        Metadata metadata = getIfNotCreateMetadata(clientId, appId);
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

    public byte[] getADU(String clientId, String appId, Long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        var adu = Files.readAllBytes(appFolder.resolve(aduId + ".txt"));
        return adu;
    }

    private Path getAppFolder(String clientId, String appId) {
        return clientId == null ? rootFolder.toPath().resolve(appId) :
                rootFolder.toPath().resolve(Paths.get(clientId, appId));
    }

    public File getADUFile(String clientId, String appId, String aduId) {
        var appFolder = getAppFolder(clientId, appId);
        return appFolder.resolve(aduId + ".txt").toFile();
    }

    /**
     * @param clientId
     * @param appId
     * @param data
     * @param aduId    if -1 we will set to next ID
     * @return
     * @throws IOException
     */
    public File addADU(String clientId, String appId, byte[] data, long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        var folder = appFolder.toFile();
        Metadata metadata = getIfNotCreateMetadata(clientId, appId);
        var lastAduId = forSending ? metadata.lastAddedMessageId : metadata.lastReceivedMessageId;
        if (aduId == -1L) {
            aduId = ++lastAduId;
        } else if (aduId <= lastAduId) {
            return null;
        }
        if (forSending) {
            metadata.lastAddedMessageId = aduId;
        } else {
            metadata.lastReceivedMessageId = aduId;
        }
        setMetadata(clientId, appId, metadata);
        var file = new File(folder, aduId + ".txt");
        Files.write(file.toPath(), data);
        return file;
    }

    public long getLastADUIdReceived(String clientId, String appId) throws IOException {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastReceivedMessageId;
    }
    public long getLastADUIdSent(String clientId, String appId) throws IOException {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastSentMessageId;
    }
}
