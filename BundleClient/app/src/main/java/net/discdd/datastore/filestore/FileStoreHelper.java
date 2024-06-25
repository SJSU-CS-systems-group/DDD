package net.discdd.datastore.filestore;

import android.net.Uri;

import net.discdd.client.applicationdatamanager.ApplicationDataManager;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

public class FileStoreHelper {
    private static final Logger logger = Logger.getLogger(FileStoreHelper.class.getName());
    private Path rootFolder;
    private Path appFolder;

    public FileStoreHelper(String rootFolder) {
        logger.log(FINE, "bundelclient", "rootFolder: " + rootFolder);
        this.rootFolder = Paths.get(rootFolder);
    }

    public FileStoreHelper(String rootFolder, String appFolder) {
        this.rootFolder = Paths.get(rootFolder);
        this.appFolder = Paths.get(appFolder);
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

    public void createAppIdDirIfNotExists(String appId) throws IOException {
        File f = new File(rootFolder + File.separator + appId);
        if (f.isFile()) {
            f.delete();
        }
        if (!f.exists()) {
            f.mkdirs();
            registerAppId(appId);
        }

        getIfNotCreateMetadata(appId);
    }

    private Metadata getMetadata(String folder) throws IOException {
        String data = getStringFromFile(rootFolder + File.separator + folder + File.separator + "metadata.json");
        Gson gson = new Gson();
        return gson.fromJson(data, Metadata.class);
    }

    private Metadata getIfNotCreateMetadata(String folder) throws IOException {
        try {
            return getMetadata(folder);
        } catch (FileNotFoundException e) {
            setMetadata(folder, new Metadata(0, 0, 0, 0));
            return getMetadata(folder);
        }
    }

    private void setMetadata(String folder, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        writeFile(rootFolder + File.separator + folder + File.separator + "metadata.json", metadataString.getBytes());
    }

    public byte[] getDataFromFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] res = new byte[fis.available()];
        fis.read(res);
        fis.close();
        return res;
    }

    private byte[] readFile(String file) throws IOException {
        File f = new File(file);
        return getDataFromFile(f);
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

    public List<byte[]> getAppData(String appId) throws IOException {
        List<byte[]> appDataList = new ArrayList<>();
        Metadata metadata = getIfNotCreateMetadata(appId);
        String folder = rootFolder + File.separator + appId;

        for (long i = metadata.lastProcessedMessageId + 1; i <= metadata.lastReceivedMessageId; i++) {
            appDataList.add(readFile(folder + File.separator + i + ".txt"));
        }
        //metadata.lastProcessedMessageId= metadata.lastReceivedMessageId;
        //setMetadata(folder, metadata);
        return appDataList;
    }

    public List<byte[]> getAllAppData(String appId) throws IOException {
        List<byte[]> dataList = new ArrayList<>();
        Metadata metadata = getIfNotCreateMetadata(appId);
        String folder = rootFolder + File.separator + appId;
        for (long i = 1; i <= metadata.lastReceivedMessageId; i++) {
            byte[] data = readFile(folder + File.separator + i + ".txt");
            logger.log(FINE, "bundleclient", data.toString());
            dataList.add(data);
        }

        return dataList;
    }

    public byte[] getADU(String appId, String aduId) throws IOException {
        return readFile(rootFolder + File.separator + appId + File.separator + aduId + ".txt");
    }

    public File getADUFile(String appId, String aduId) {
        return new File(rootFolder + File.separator + appId + File.separator + aduId + ".txt");
    }

    public byte[] getNextAppData(String folder) throws IOException {
        Metadata metadata = getIfNotCreateMetadata(folder);
        long nextMessageId = metadata.lastProcessedMessageId + 1;
        if (nextMessageId > metadata.lastReceivedMessageId) {
            logger.log(INFO, "bundleclient", "no data to show");
            if (nextMessageId > 1) {
                nextMessageId--;
            } else {
                return null;
            }
        }
        byte[] appData = readFile(rootFolder + File.separator + folder + File.separator + nextMessageId + ".txt");
        metadata.lastProcessedMessageId = nextMessageId;
        setMetadata(folder, metadata);
        return appData;
    }

    private void registerAppId(String appId) throws IOException {
        ApplicationDataManager adm = new ApplicationDataManager(appFolder);
        List<String> appIds = adm.getRegisteredAppIds();

        //check if appId already exists
        for (int i = 0; i < appIds.size(); i++) {
            if (appIds.get(i).equals(appId)) {
                return;
            }
        }
        adm.registerAppId(appId);
    }

    public Uri addFile(String folder, byte data[]) throws IOException {
        Metadata metadata = getIfNotCreateMetadata(folder);
        metadata.lastReceivedMessageId++;
        setMetadata(folder, metadata);

        File f = new File(rootFolder + File.separator + folder);
        String messagePath;

        int numFile = f.list().length;
        messagePath = f.getPath() + File.separator + numFile + ".txt";

        writeFile(messagePath, data);
        return Uri.parse(messagePath);
    }

    public void deleteAllFilesUpTo(String appId, long aduId) throws IOException {
        //check if there are enough files
        String folder = appId;
        Metadata metadata = getIfNotCreateMetadata(folder);
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
        File file = new File(rootFolder + File.separator + fileName);
        file.delete();
    }
}
