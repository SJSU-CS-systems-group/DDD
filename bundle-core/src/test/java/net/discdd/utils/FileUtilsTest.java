package net.discdd.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtilsTest {
    @Test
    void testRecursiveDelete(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("a/a/c"));
        Files.createDirectories(tempDir.resolve("a/b/c"));
        Files.createDirectories(tempDir.resolve("a/c/c"));
        Files.createFile(tempDir.resolve("a/a/c/a.txt"));
        Files.createFile(tempDir.resolve("a/b/a.txt"));
        Files.createFile(tempDir.resolve("a/b/c/a.txt"));
        Files.createFile(tempDir.resolve("a/c/a.txt"));
        Files.createFile(tempDir.resolve("a/c/c/a.txt"));

        // make sure the creates happen
        assert Files.exists(tempDir.resolve("a/c/c/a.txt"));
        assert Files.exists(tempDir.resolve("a"));

        // delete everything
        FileUtils.recursiveDelete(tempDir.resolve("a"));

        // make sure everything is gone
        assert Files.notExists(tempDir.resolve("a"));
    }
}
