package net.discdd.client.applicationdatamanager;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import net.discdd.model.ADU;
import net.discdd.utils.StoreADUs;
import net.discdd.bundleclient.BundleClientActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class DataStoreAdaptor {

    private static final Logger logger = Logger.getLogger(DataStoreAdaptor.class.getName());

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;
    private Context applicationContext;

    public DataStoreAdaptor(Path appRootDataDirectory) {
        sendADUsStorage = new StoreADUs(appRootDataDirectory.resolve("send"), true);
        receiveADUsStorage = new StoreADUs(appRootDataDirectory.resolve("receive"), false);
    }

    private void sendDataToApp(ADU adu) throws IOException {
        //notify app that someone sent data for the app
        Intent intent = new Intent("android.intent.dtn.SEND_DATA");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        byte[] data = Files.readAllBytes(adu.getSource().toPath());
        logger.log(FINE, new String(data) + ", Source:" + adu.getSource());
        intent.putExtra(Intent.EXTRA_TEXT, data);
        applicationContext = BundleClientActivity.ApplicationContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent);
        } else {
            logger.log(SEVERE, "[Failed] to send to application. Upgrade Android SDK to 26 or greater");
        }
    }

    public void persistADU(ADU adu) throws IOException {
        logger.log(INFO, "Persisting ADUs: " + adu.getADUId() + "," + adu.getSource());
        receiveADUsStorage.addADU(null, adu.getAppId(), Files.readAllBytes(adu.getSource().toPath()), adu.getADUId());
        sendDataToApp(adu);
        logger.log(INFO,
                   "[ADM-DSA] Persisting inbound ADU " + adu.getAppId() + "-" + adu.getADUId() + " to the Data Store");

    }

    public void deleteADUs(String appId, long aduIdEnd) throws IOException {

        sendADUsStorage.deleteAllFilesUpTo(null, appId, aduIdEnd);
        logger.log(INFO, "[DSA] Deleted ADUs for application " + appId + " with id upto " + aduIdEnd);
        logger.log(INFO, "[ADM-DSA] Deleting outbound ADUs of application " + appId + " upto id " + aduIdEnd);
    }

    private ADU fetchADU(String appId, long aduId) {
        try {
            File file = sendADUsStorage.getADUFile(null, appId, aduId + "");
            FileInputStream fis = new FileInputStream(file);
            int fileSize = fis.available();
            logger.log(FINER, "Size:" + fileSize);
            ADU adu = new ADU(file, appId, aduId, fileSize);
            return adu;
        } catch (Exception ex) {
            return null;
        }
    }

    public List<ADU> fetchADUs(String appId, long aduIdStart) {
        ADU adu = null;
        List<ADU> ret = new ArrayList<>();
        long aduId = aduIdStart;
        while ((adu = this.fetchADU(appId, aduId)) != null) {
            logger.log(FINE, adu.getADUId() + adu.getAppId());
            ret.add(adu);
            aduId++;
        }
        logger.log(FINE, "Completed fetching ADUs");
        return ret;
    }
}