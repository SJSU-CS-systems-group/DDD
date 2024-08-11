package net.discdd.utils;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
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

    public static void deleteBundlesFromDirectory(Path directory) {
        File deleteDir = directory.toFile();
        if (deleteDir.listFiles() != null) {
            for (File bundle : Objects.requireNonNull(deleteDir.listFiles())) {
                boolean result = bundle.delete();
                logger.log(INFO, bundle.getName() + "deleted:" + result);
            }
        }
    }

    public static void recursiveDelete(Path path) {
        try (var walk = Files.walk(path)) {
            walk.map(Path::toFile).sorted(Comparator.reverseOrder()).forEach(File::delete);
        } catch (IOException e) {
            logger.log(SEVERE, "Error deleting directory: " + path, e);
        }
    }
}
