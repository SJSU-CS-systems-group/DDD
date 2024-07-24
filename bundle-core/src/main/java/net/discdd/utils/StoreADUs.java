package net.discdd.utils;

import com.google.gson.Gson;
import net.discdd.model.ADU;
import net.discdd.model.Metadata;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class StoreADUs {
    public Path rootFolder;
    private static final Logger logger = Logger.getLogger(StoreADUs.class.getName());
    public StoreADUs(Path rootFolder) {
        logger.log(FINE, "bundlecore", "rootFolder: " + rootFolder);
        this.rootFolder = rootFolder;
    }

    public Metadata getMetadata(String clientId, String appId) throws IOException {
        Path metadataPath = getAppFolder(clientId, appId).resolve("metadata.json");
        try {
            String data = new String(Files.readAllBytes(metadataPath));
            logger.log(INFO, "metadata path " + metadataPath);
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(SEVERE, "[FileStoreHelper] metadata not found at " + metadataPath + ". create a new one.");
            Metadata metadata = new Metadata();
            setMetadata(clientId, appId, metadata);
            return metadata;
        }
    }

    private void setMetadata(String clientId, String appId, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        Path folder = getAppFolder(clientId, appId);
        File file = folder.resolve("metadata.json").toFile();

        logger.log(INFO, "[Set] metadata path " + file);

        file.getParentFile().mkdirs();
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(metadataString.getBytes());
        oFile.close();
    }

    private Metadata getIfNotCreateMetadata(String clientId, String appId) throws IOException {
        try {
            return getMetadata(clientId, appId);
        } catch (FileNotFoundException e) {
            setMetadata(clientId, appId, new Metadata());
            return getMetadata(clientId, appId);
        }
    }

    public List<ADU> getAppData(String clientId, String appId) throws IOException {
        var folder = getAppFolder(clientId, appId);
        try (var fileList = Files.list(folder)) {
            return fileList.map(path -> new ADU(path.toFile(), appId, Long.parseLong(path.toFile().getName().split("\\.")[0]),
                                        path.toFile().length(), clientId)).collect(Collectors.toList());
        }
    }

    public record ClientApp(String clientId, String appId) {}

    public Stream<ClientApp> getAllClientApps() {
        try {
            var topPaths = Files.list(rootFolder);
            return topPaths.filter(p -> p.toFile().isDirectory()).flatMap(clientIdPath -> {
                try {
                    var bottomPaths = Files.list(clientIdPath);
                    return bottomPaths.map(Path::toFile).filter(File::isDirectory).map(File::getName)
                            .map(appId -> new ClientApp(clientIdPath.toFile().getName(), appId));
                } catch (IOException e) {
                    return Stream.empty();
                }
            });
        } catch (IOException e) {
            logger.log(WARNING, "Nothing found in rootFolder: " + rootFolder);
            return Stream.empty();
        }
    }

    public List<byte[]> getAllAppData(String appId) throws IOException {
        var folder = rootFolder.resolve(appId);
        return Files.list(folder).map(path -> {
                try {
                    return Files.readAllBytes(path);
                } catch (IOException e) {
                    logger.log(SEVERE, "Failed to read file " + path, e);
                    return new byte[0];
                }
            }).collect(Collectors.toList());
    }

    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) throws IOException {
        //check if there are enough files
        var folder = getAppFolder(clientId, appId);
        Files.list(folder).filter(p -> p.toFile().getName().endsWith(".adu"))
                .filter(p -> Long.parseLong(p.toFile().getName().split("\\.")[0]) <= aduId)
                .peek(p -> logger.log(INFO, "Deleting file " + p)).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        logger.log(SEVERE, "Failed to delete file " + p, e);
                    }
                });
        var metadata = getMetadata(clientId, appId);
        if (metadata.lastAduDeleted < aduId) {
            metadata.lastAduDeleted = aduId;
            setMetadata(clientId, appId, metadata);
        }
    }

    public byte[] getADU(String clientId, String appId, Long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        var adu = Files.readAllBytes(appFolder.resolve(aduId + ".adu"));
        return adu;
    }

    private Path getAppFolder(String clientId, String appId) {
        return clientId == null ? rootFolder.resolve(appId) : rootFolder.resolve(Paths.get(clientId, appId));
    }

    public File getADUFile(String clientId, String appId, String aduId) {
        var appFolder = getAppFolder(clientId, appId);
        return appFolder.resolve(aduId + ".adu").toFile();
    }

    public long getLastADUIdAdded(String clientId, String appId) throws IOException {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastAduAdded;
    }

    public long getLastADUIdDeleted(String clientId, String appId) throws IOException {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastAduDeleted;
    }

    /**
     * @param clientId
     * @param appId
     * @param data
     * @param aduId    if -1 we will set to next ID
     * @return
     * @throws IOException
     */
    public File addADU(String clientId, String appId, byte[] data, long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        var folder = appFolder.toFile();

        Metadata metadata = getIfNotCreateMetadata(clientId, appId);
        var lastAduId = metadata.lastAduAdded;
        if (aduId == -1L) {
            aduId = ++lastAduId;
        } else if (aduId <= lastAduId) {
            return null;
        }

        if (metadata.lastAduAdded < lastAduId) {
            metadata.lastAduAdded = aduId;
        }

        setMetadata(clientId, appId, metadata);
        var file = new File(folder, aduId + ".adu");
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(data);
        oFile.close();

        return file;
    }
}
