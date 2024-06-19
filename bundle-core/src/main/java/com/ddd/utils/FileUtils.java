package com.ddd.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils {
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
}
