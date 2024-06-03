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

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

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
}