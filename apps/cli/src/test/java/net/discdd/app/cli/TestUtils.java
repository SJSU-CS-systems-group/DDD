package net.discdd.app.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

public class TestUtils {
    /**
     * ├── BundleSecurity
     * ├── Keys/Server/
     * ├── Server_Keys copy
     * ├── (6 expected keys)
     * ├── Clients
     * ├── 1EKo0fp8EKHarKiWehyAXCihSqs=
     * ├── clientBase.pub
     * ├── clientIdentity.pub
     * ├── Session.store
     */
    public static String makeEncryptsTempDirs() throws URISyntaxException {
        Path tempDir;
        try {
            // Create a temporary base directory
            tempDir = Files.createTempDirectory("encryptsTempDir");
            System.out.println("Created temporary base directory: " + tempDir);

            // Define subdirectories relative to the temporary base directory
            Path serverKeysDir = tempDir.resolve("BundleSecurity/Keys/Server/Server_Keys");
            Path clientDir = tempDir.resolve("BundleSecurity/Keys/Server/Clients/1EKo0fp8EKHarKiWehyAXCihSqs=");

            // Create subdirectories
            Files.createDirectories(serverKeysDir);
            Files.createDirectories(clientDir);
            System.out.println("Created subdirectories.");

            // Copy Server_Keys resources
            copyResourceFiles("Server_Keys", serverKeysDir.toString());

            // Copy 1EKo0fp8EKHarKiWehyAXCihSqs= resources
            copyResourceFiles("1EKo0fp8EKHarKiWehyAXCihSqs=", clientDir.toString());

            // Return the temporary directory path as a string
            return tempDir.toString();
        } catch (IOException e) {
            System.err.println("Failed to create or manipulate temporary directories.");
            e.printStackTrace();
            throw new RuntimeException(e); // Or handle the exception as needed
        }
    }

    /**
     * Creates temporary directories with the following structure:
     * ├──BundleTransmission/(for getReceivedProcessingDirectory)
     * │
     * ├──BundleSecurity
     * ├──Keys/Server/
     * ├── Server_Keys copy
     * ├──(6 expected keys)
     *
     * @return String baseDirPath the mock bundle-server.bundle-store-root
     */
    public static Path makeDecryptsTempDirs() {
        try {
            // Create a temporary directory
            Path tempDir = Files.createTempDirectory("tempDir_");
            System.out.println("Temporary directory created: " + tempDir);

            // Define relative paths
            Path bundleSecurityPath = tempDir.resolve("BundleSecurity/Keys/Server/Server_Keys");
            Files.createDirectories(bundleSecurityPath);

            // Load resource directory and copy files
            ClassLoader cl = TestUtils.class.getClassLoader();
            URL resource = cl.getResource("Server_Keys");

            if (resource != null) {
                File resourceDir = new File(resource.toURI());
                File[] files = resourceDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        Path destFilePath = bundleSecurityPath.resolve(file.getName());
                        try (InputStream fileIs = cl.getResourceAsStream("Server_Keys/" + file.getName())) {
                            if (fileIs == null) {
                                System.err.println("Resource file not found: " + file.getName());
                                continue;
                            }
                            Files.copy(fileIs, destFilePath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("Copied file to: " + destFilePath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    System.err.println("No files found in directory: " + resourceDir.getAbsolutePath());
                }
            } else {
                System.err.println("Resource not found: Server_Keys");
            }

            // Optionally return the base directory path if needed
            String baseDirPath = tempDir.toAbsolutePath().toString();
            System.out.println("Base directory path: " + baseDirPath);
            return Path.of(baseDirPath);

        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * get resource file from resources
     *
     * @return String resource
     */
    public static String getFromResources(String res) throws URISyntaxException {
        ClassLoader cl = TestUtils.class.getClassLoader();
        URL resource = cl.getResource(res);
        if (resource == null) {
            throw new IllegalArgumentException("file not found! " + res);
        } else {
            res = String.valueOf(new File(resource.toURI()));
        }
        return res;
    }

    /**
     * Creates a temporary application properties file with the location of temporary directories
     *
     * @param content
     * @return filePath
     * @throws IOException
     */
    public static String createResource(String content) throws IOException {
        Path tempFile = Files.createTempFile("test-properties", ".yml");
        Files.write(tempFile, content.getBytes(), StandardOpenOption.WRITE);
        String filePath = String.valueOf(tempFile.toAbsolutePath());
        return filePath;
    }

    /**
     * Copies files over from resources directory
     *
     * @param resourceName
     * @param targetDir
     * @throws URISyntaxException
     */
    private static void copyResourceFiles(String resourceName, String targetDir) throws URISyntaxException {
        Path sourceDir = Paths.get(TestUtils.class.getResource("/" + resourceName).toURI());
        Path targetDirPath = Paths.get(targetDir);

        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.forEach(source -> {
                Path destination = targetDirPath.resolve(sourceDir.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        if (!Files.exists(destination)) {
                            Files.createDirectory(destination);
                        }
                    } else {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String trimMessage(String log) {
        String s = "";
        if (log.contains("Encrypting bundle") && log.contains("Finished encrypting")) {
            s = "Encrypting bundle" + "\n" + "Finished encrypting" + "\n";
        } else {
            String regex = "(\\w{3} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} (AM|PM) )|(/[^\\s]+)";
            s = log.replaceAll(regex, "");
        }
        return s;
    }
}
