package edu.sjsu.dtn.storage;

import com.google.gson.Gson;
import edu.sjsu.dtn.model.Metadata;

import java.io.*;
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

    public Metadata getMetadata(String folder){
        try {
            String data = getStringFromFile(RootFolder + "/" + folder + "/metadata.json");
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void setMetadata(String folder, Metadata metadata){
        try {
            File metadataFile = new File(RootFolder + "/" + folder + "/metadata.json");
            metadataFile.createNewFile();
            Gson gson = new Gson();
            String metadataString = gson.toJson(metadata);
            FileOutputStream oFile = new FileOutputStream(metadataFile, false);
            oFile.write(metadataString.getBytes());
            oFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    public List<byte[]> getAppData(String clientId){
        List<byte[]> appDataList = new ArrayList<>();
        String folder = clientId;
        Metadata metadata = getMetadata(folder);
        for(long i=metadata.lastProcessedMessageId+1;i <= metadata.lastReceivedMessageId;i++){
            appDataList.add(readFile(RootFolder+"/"+folder+"/"+i+".txt"));
        }
        //metadata.lastProcessedMessageId= metadata.lastReceivedMessageId;
        //setMetadata(folder, metadata);
        return appDataList;
    }

    public byte[] getADU(String clientId, String aduId){
        return readFile(RootFolder+"/"+ clientId+"/"+aduId+".txt");
    }

    public File getADUFile(String clientId, String aduId){
        return new File(RootFolder+"/"+ clientId+"/"+aduId+".txt");
    }

    public byte[] getNextAppData(String folder){
        Metadata metadata = getMetadata(folder);
        if(metadata==null){
            metadata = new Metadata(1, 0,0,0);
            setMetadata(folder, metadata);
        }
        long nextMessageId = metadata.lastProcessedMessageId+1;
        if(nextMessageId> metadata.lastReceivedMessageId){
            System.out.println("no data to show");
            if(nextMessageId>1){
                nextMessageId--;
            }else
                return null;
        }
        byte[] appData = readFile(RootFolder + "/" + folder+"/"+nextMessageId+".txt");
        metadata.lastProcessedMessageId = nextMessageId;
        setMetadata(folder, metadata);
        return appData;
    }

    public void AddFile(String clientId, byte data[]){
        String folder = clientId;
        File f = new File(RootFolder+"/"+folder);
        if(f.isDirectory()){
            System.out.println( RootFolder+"/"+clientId+" is a directory");
            int noOfMessages = f.list().length;
            System.out.println("noOfMessages-"+noOfMessages);
            File dataFile = new File(RootFolder+"/"+folder+"/"+noOfMessages+".txt");
            FileOutputStream oFile;
            try {
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();

                Metadata metadata = getMetadata(folder);
                metadata.lastReceivedMessageId++;
                setMetadata(folder, metadata);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }else{
            f.mkdirs();
            File metadataFile = new File(RootFolder +"/"+folder + "/metadata.json");
            try {
                new File(RootFolder +"/"+ folder).mkdirs();
                metadataFile.createNewFile();
                Gson gson = new Gson();
                Metadata metadata = new Metadata(0, 0,1,0);
                String metadataString = gson.toJson(metadata);
                FileOutputStream oFile = new FileOutputStream(metadataFile, false);
                oFile.write(metadataString.getBytes());
                oFile.close();

                File dataFile = new File(RootFolder +"/"+ folder +"/1.txt");
                dataFile.createNewFile();
                oFile = new FileOutputStream(dataFile, false);
                oFile.write(data);
                oFile.close();
            }catch(Exception ex){
                System.out.println("error"+ex.getMessage());
                ex.printStackTrace();
            }

        }
    }

    public void deleteAllFilesUpTo(String clientId, long aduId){
        //check if there are enough files
        String folder = RootFolder+"/"+clientId;
        Metadata metadata = getMetadata(folder);
        if(metadata.lastSentMessageId >= aduId){
            System.out.println("[FileStoreHelper.deleteAllFilesUpTo] Data already deleted.");
            return;
        }
        for(long i = metadata.lastSentMessageId+1 ; i<=aduId;i++){
            deleteFile(i+".txt");
            System.out.println(i+".txt deleted");
        }

        metadata.lastSentMessageId = aduId;
    }

    public void deleteFile(String fileName){
        File file = new File(RootFolder+"/"+fileName);
        file.delete();
    }
}
