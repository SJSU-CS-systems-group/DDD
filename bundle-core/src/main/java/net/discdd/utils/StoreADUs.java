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
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class StoreADUs {
    public static final String METADATA_FILENAME = "metadata.json";
    private static final Path METADATA_PATH = Path.of(METADATA_FILENAME);
    public Path rootFolder;
    private static final Logger logger = Logger.getLogger(StoreADUs.class.getName());

    public StoreADUs(Path rootFolder) {
        logger.log(FINE, "bundlecore", "rootFolder: " + rootFolder);
        this.rootFolder = rootFolder;
    }

    // TODO: remove this constructor after everything has been converted
    public StoreADUs(Path rootFolder, boolean ignore) {
        this(rootFolder);
    }

    private Metadata getMetadata(String clientId, String appId) throws IOException {
        Path metadataPath = getAppFolder(clientId, appId).resolve(METADATA_FILENAME);
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
        File file = folder.resolve(METADATA_PATH).toFile();

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
        return getADUs(clientId, appId).collect(Collectors.toList());

    }

    public Stream<ADU> getADUs(String clientId, String appId) throws IOException {
        getIfNotCreateMetadata(clientId, appId);
        return Files.list(getAppFolder(clientId, appId)).filter(p -> !p.endsWith(METADATA_PATH)).map(Path::toFile)
                .map(f -> new ADU(f, appId, Long.parseLong(f.getName()), f.length(), clientId))
                .sorted(Comparator.comparingLong(ADU::getADUId));
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
        getIfNotCreateMetadata(null, appId);
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

    public List<Long> getAllADUIds(String appId) throws IOException {
        getIfNotCreateMetadata(null, appId);
        var folder = rootFolder.resolve(appId);
        return Files.list(folder).map(path -> path.getFileName().toString())
                .filter(fileName -> !fileName.equals(METADATA_FILENAME)).map(Long::parseLong)
                .collect(Collectors.toList());
    }

    public byte[] getADU(String appId, Long aduId) throws IOException {
        return getADU(null, appId, aduId);
    }

    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) throws IOException {
        var metadata = getMetadata(clientId, appId);
        getADUs(clientId, appId).takeWhile(adu -> adu.getADUId() <= aduId).forEach(adu -> {
            if (!adu.getSource().delete()) logger.log(SEVERE, "Failed to delete file " + adu);
        });
        if (metadata.lastAduDeleted < aduId) {
            metadata.lastAduDeleted = aduId;
            setMetadata(clientId, appId, metadata);
        }
    }

    public byte[] getADU(String clientId, String appId, Long aduId) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        return Files.readAllBytes(appFolder.resolve(Long.toString(aduId)));
    }

    private Path getAppFolder(String clientId, String appId) {
        return clientId == null ? rootFolder.resolve(appId) : rootFolder.resolve(Paths.get(clientId, appId));
    }

    public File getADUFile(String clientId, String appId, long aduId) {
        var appFolder = getAppFolder(clientId, appId);
        return appFolder.resolve(Long.toString(aduId)).toFile();
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

        Metadata metadata = getIfNotCreateMetadata(clientId, appId);
        var lastAduDeleted = metadata.lastAduDeleted;
        var lastAduAdded = metadata.lastAduAdded;
        if (aduId == -1L) {
            aduId = lastAduAdded + 1;
        } else if (aduId <= lastAduDeleted) {
            return null;
        }

        if (metadata.lastAduAdded < aduId) {
            metadata.lastAduAdded = aduId;
            setMetadata(clientId, appId, metadata);
        }

        Path aduPath = appFolder.resolve(Long.toString(aduId));
        Files.write(aduPath, data);
        return aduPath.toFile();
    }
}
