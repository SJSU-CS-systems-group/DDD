package net.discdd.bundletransport.utils;

import android.os.Build;

import com.ddd.bundletransport.service.BundleDownloadResponse;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class FileUtils {

    private static final Logger logger = Logger.getLogger(FileUtils.class.getName());

    public static String getFilesList(String directory) {
        String fileList = "";
        File dir = new File(directory);
        File[] Files = dir.listFiles();
        if (Files != null && Files.length > 0) {
            for (File file : Files) {
                fileList = fileList + "," + file.getName();
            }
        }
        return fileList;
    }

    public static OutputStream getFilePath(BundleDownloadResponse response, String Receive_Directory) throws IOException {
        String fileName = response.getMetadata().getBid();
        File directoryReceive = new File(Receive_Directory + File.separator + response.getMetadata().getTransportId());
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Files.newOutputStream(Paths.get(
                    Receive_Directory + File.separator + response.getMetadata().getTransportId() + File.separator +
                            fileName), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            File file = new File(
                    Receive_Directory + File.separator + response.getMetadata().getTransportId() + File.separator +
                            fileName);
            FileOutputStream outputStream = new FileOutputStream(file);
            return outputStream;
        }
    }

    public static void writeFile(OutputStream writer, ByteString content) throws IOException {
        try {
            writer.write(content.toByteArray());
            writer.flush();
        } catch (Exception e) {
            logger.log(WARNING, "writeFile: " + e.getMessage());
        }

    }

    public static void closeFile(OutputStream writer) {
        try {
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void deleteBundles(String directory) {
        File deleteDir = new File(directory);
        if (deleteDir.listFiles() != null) {
            for (File bundle : Objects.requireNonNull(deleteDir.listFiles())) {
                boolean result = bundle.delete();
                logger.log(INFO, bundle.getName() + "deleted:" + result);
            }
        }
    }
}
