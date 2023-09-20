package com.example.mysignal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FileStoreHelper {
    String RootFolder="";

    public FileStoreHelper(String rootFolder){
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

    public static String getStringFromFile (String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    public byte[] getDataFromFile(File file){
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    private byte[] readFile(String file){
        try {
            File f = new File(file);
            FileInputStream fis = new FileInputStream(f);
            byte[] res = new byte[fis.available()];
            fis.read(res);
            return res;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return null;
    }

    public List<byte[]> getAppData(){
        List<byte[]> appDataList = new ArrayList<>();
        String folder = RootFolder;
        try {
            File f = new File(RootFolder + "/" + folder);
            String[] fileList = f.list();
            for (long i = 1; i <= fileList.length; i++) {
                appDataList.add(readFile(folder + "/" + i + ".txt"));
            }
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return appDataList;
    }

    public byte[] getADU(String appId, String aduId){
        return readFile(RootFolder+"/"+ appId+"/"+aduId+".txt");
    }

    public File getADUFile(String appId, String aduId){
        return new File(RootFolder+"/"+ appId+"/"+aduId+".txt");
    }

    public void AddFile(String folder, byte data[]){
        File f = new File(RootFolder+"/"+folder);
        if(f.isDirectory()){
            Log.d("deepak", RootFolder+"/"+folder+" is a directory");
            int noOfMessages = f.list().length+1;
            Log.d("deepak", "noOfMessages-"+noOfMessages);
            File dataFile = new File(RootFolder+"/"+folder+"/"+noOfMessages+".txt");
            FileOutputStream oFile = null;
            try {
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }else{
            f.mkdirs();
            try {
                new File(RootFolder +"/"+ folder).mkdirs();
                File dataFile = new File(RootFolder +"/"+ folder +"/1.txt");
                dataFile.createNewFile();
                FileOutputStream oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();
            }catch(Exception ex){
                Log.d("deepak", "error"+ex.getMessage());
                ex.printStackTrace();
            }

        }
    }

    public void deleteFile(String fileName){
        File file = new File(RootFolder+"/"+fileName);
        file.delete();
    }
}

