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

}
