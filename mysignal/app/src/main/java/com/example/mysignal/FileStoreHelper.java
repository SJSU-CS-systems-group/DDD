package com.example.mysignal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FileStoreHelper {
    String RootFolder = "";

    private final static String MESSAGES_DIR = "messages";

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

    private byte[] readFile(String file) throws IOException {
        File f = new File(file);

        FileInputStream fis = new FileInputStream(f);
        byte[] res = new byte[fis.available()];
        fis.read(res);
        return res;
    }

    public List<byte[]> getAppData() throws IOException {
        List<byte[]> appDataList = new ArrayList<>();
        String folder = RootFolder;

        Log.d(MainActivity.TAG, "File path: " + RootFolder);
        File f = new File(RootFolder + File.separator + MESSAGES_DIR);
        if (!f.exists()) {
            Log.d(MainActivity.TAG, "Creating new folder: " + f.getPath());
            f.mkdirs();
        }

        String[] fileList = f.list();

        for (long i = 1; i <= fileList.length; i++) {
            appDataList.add(readFile(folder + "/" + i + ".adu"));

        }
        return appDataList;
    }

    public byte[] getADU(String appId, String aduId) throws IOException {
        return readFile(RootFolder + "/" + appId + "/" + aduId + ".adu");
    }

    public File getADUFile(String appId, String aduId) {
        return new File(RootFolder + "/" + appId + "/" + aduId + ".adu");
    }

    public void AddFile(String folder, byte data[]) throws IOException {
        File f = new File(RootFolder + File.separator + MESSAGES_DIR);

        if (f.isFile()) {
            throw new IOException(f.getPath() + " is a file");
        }

        File dataFile;
        if (!f.isDirectory()) {
            f.mkdirs();
            dataFile = new File(RootFolder + "/" + folder + "/1.adu");
        } else {
            Log.d("deepak", RootFolder + "/" + folder + " is a directory");
            int noOfMessages = f.list().length + 1;
            Log.d("deepak", "noOfMessages-" + noOfMessages);
            dataFile = new File(RootFolder + "/" + folder + "/" + noOfMessages + ".adu");
        }

        dataFile.createNewFile();
        FileOutputStream oFile = new FileOutputStream(dataFile, false);
        oFile.write(data);
        oFile.close();
    }

    public void deleteFile(String fileName) {
        File file = new File(RootFolder + "/" + fileName);
        file.delete();
    }
}

