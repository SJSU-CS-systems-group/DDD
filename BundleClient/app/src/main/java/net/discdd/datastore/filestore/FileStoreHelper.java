package net.discdd.datastore.filestore;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import com.ddd.model.Metadata;
import com.ddd.utils.StoreADUs;

import net.discdd.client.applicationdatamanager.ApplicationDataManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

public class FileStoreHelper {
    private static final Logger logger = Logger.getLogger(FileStoreHelper.class.getName());
    private Path rootFolder;
    private Path appFolder;
    private StoreADUs ADUsStorage;

    public FileStoreHelper(String rootFolder) {
        logger.log(FINE, "bundelclient", "rootFolder: " + rootFolder);
        this.rootFolder = Paths.get(rootFolder);
    }

    public FileStoreHelper(String rootFolder, String appFolder) {
        this.rootFolder = Paths.get(rootFolder);
        this.appFolder = Paths.get(appFolder);
    }

    /**
     * will be abstract due to use of app-specific adp
     *
     * @param folder
     * @return
     * @throws IOException
     */
    public byte[] getNextAppData(String folder) throws IOException {
        Metadata metadata = ADUsStorage.getIfNotCreateMetadata(new File(folder));
        long nextMessageId = metadata.lastProcessedMessageId + 1;
        if (nextMessageId > metadata.lastReceivedMessageId) {
            logger.log(INFO, "bundleclient", "no data to show");
            if (nextMessageId > 1) {
                nextMessageId--;
            } else {
                return null;
            }
        }
        byte[] appData = Files.readAllBytes(rootFolder.resolve(Paths.get(folder, nextMessageId + ".txt")));
        metadata.lastProcessedMessageId = nextMessageId;
        ADUsStorage.setMetadata(new File(folder), metadata);
        return appData;
    }

    /**
     * will be abstract due to use of app-specific adp
     * @param appId
     * @throws IOException
     */
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
    public void createAppIdDirIfNotExists(String appId) throws IOException {
        File f = new File(rootFolder + File.separator + appId);
        if (f.isFile()) {
            f.delete();
        }
        if (!f.exists()) {
            f.mkdirs();
            registerAppId(appId);
        }

        ADUsStorage.getIfNotCreateMetadata(new File(appId));
    }
}
