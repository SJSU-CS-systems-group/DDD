package net.discdd.utils;

import com.google.protobuf.ByteString;
import net.discdd.bundletransport.service.BundleDownloadResponse;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class FileUtils {
    private static final Logger logger = Logger.getLogger(FileUtils.class.getName());

    public static void createFileWithDefaultIfNeeded(Path path, byte[] bytes) throws IOException {
        if (path.toFile().exists()) return;
        var parent = path.getParent().toFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + path.getParent());
        }
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    public static void createEmptyFileIfNeeded(Path path) throws IOException {
        createFileWithDefaultIfNeeded(path, new byte[0]);
    }

    public static OutputStream getFilePath(BundleDownloadResponse response, String receive_Directory) throws IOException {
        String fileName = response.getMetadata().getBid();
        File directoryReceive = new File(receive_Directory + File.separator + response.getMetadata().getSenderId());
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }

        return Files.newOutputStream(Paths.get(receive_Directory, response.getMetadata().getSenderId(), fileName),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    public static void deleteBundlesFromDirectory(String directory) {
        File deleteDir = new File(directory);
        if (deleteDir.listFiles() != null) {
            for (File bundle : Objects.requireNonNull(deleteDir.listFiles())) {
                boolean result = bundle.delete();
                logger.log(INFO, bundle.getName() + "deleted:" + result);
            }
        }
    }
}
