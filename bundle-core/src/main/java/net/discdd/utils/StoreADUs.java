package net.discdd.utils;

import com.google.gson.Gson;
import net.discdd.model.ADU;
import net.discdd.model.Metadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class StoreADUs {
    public static final String METADATA_FILENAME = "metadata.json";
    private static final Path METADATA_PATH = Paths.get(METADATA_FILENAME);
    public Path rootFolder;
    private static final Logger logger = Logger.getLogger(StoreADUs.class.getName());

    public StoreADUs(Path rootFolder) {
        logger.log(FINEST, "ADU rootFolder: " + rootFolder);
        this.rootFolder = rootFolder;
    }

    // TODO: remove this constructor after everything has been converted
    public StoreADUs(Path rootFolder, boolean ignore) {
        this(rootFolder);
    }

    private Metadata getMetadata(String clientId, String appId) {
        Path metadataPath = getAppFolder(clientId, appId).resolve(METADATA_FILENAME);
        try {
            String data = new String(Files.readAllBytes(metadataPath));
            Gson gson = new Gson();
            return gson.fromJson(data, Metadata.class);
        } catch (Exception e) {
            logger.log(FINE, "[FileStoreHelper] metadata not found at " + metadataPath + ". create a new one.");
            Metadata metadata = new Metadata();
            try {
                setMetadata(clientId, appId, metadata);
            } catch (IOException ex) {
                logger.log(SEVERE, "Failed to create metadata file. PROBLEMS IMMINENT!", ex);
            }
            return metadata;
        }
    }

    private void setMetadata(String clientId, String appId, Metadata metadata) throws IOException {
        Gson gson = new Gson();
        String metadataString = gson.toJson(metadata);
        Path folder = getAppFolder(clientId, appId);
        File file = folder.resolve(METADATA_PATH).toFile();

        file.getParentFile().mkdirs();
        FileOutputStream oFile = new FileOutputStream(file);
        oFile.write(metadataString.getBytes());
        oFile.close();
    }

    public List<ADU> getAppData(String clientId, String appId) throws IOException {
        return getADUs(clientId, appId).collect(Collectors.toList());

    }

    public Stream<ADU> getADUs(String clientId, String appId) throws IOException {
        getMetadata(clientId, appId);
        return Files.list(getAppFolder(clientId, appId))
                .filter(p -> !p.endsWith(METADATA_PATH))
                .map(Path::toFile)
                .map(f -> new ADU(f, appId, Long.parseLong(f.getName()), f.length(), clientId))
                .sorted(Comparator.comparingLong(ADU::getADUId));
    }

    public boolean hasNewADUs(String clientId, long lastBundleSentTimestamp) {
        var appAdus = clientId == null ? rootFolder : rootFolder.resolve(clientId);
        try {
            return Files.walk(appAdus)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .anyMatch(f -> f.lastModified() > lastBundleSentTimestamp);
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to check for new ADUs answering false to newADUs", e);
            return false;
        }
    }

    public record ClientApp(String clientId, String appId) {}

    public Stream<ClientApp> getAllClientApps() {
        try {
            var topPaths = Files.list(rootFolder);
            return topPaths.filter(p -> p.toFile().isDirectory()).flatMap(clientIdPath -> {
                try {
                    var bottomPaths = Files.list(clientIdPath);
                    return bottomPaths.map(Path::toFile)
                            .filter(File::isDirectory)
                            .map(File::getName)
                            .map(appId -> new ClientApp(clientIdPath.toFile().getName(), appId));
                } catch (IOException e) {
                    return Stream.empty();
                }
            });
        } catch (IOException e) {
            logger.log(FINE, "Nothing found in rootFolder: " + rootFolder);
            return Stream.empty();
        }
    }

    public record AduIdData(String id, byte[] data) {}

    public List<AduIdData> getAllAppIdAndData(String appId) throws IOException {
        getMetadata(null, appId);
        var folder = rootFolder.resolve(appId);
        return Files.list(folder).map(path -> {
            try {
                String id = path.getFileName().toString();
                return new AduIdData(id, Files.readAllBytes(path));
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to read file " + path, e);
                return null;
            }
        }).collect(Collectors.toList());
    }

    public List<byte[]> getAllAppData(String appId) throws IOException {
        getMetadata(null, appId);
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
        getMetadata(null, appId);
        var folder = rootFolder.resolve(appId);
        return Files.list(folder)
                .map(path -> path.getFileName().toString())
                .filter(fileName -> !fileName.equals(METADATA_FILENAME))
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    public byte[] getADU(String clientId, String appId, Long aduId) throws IOException {
        return getADU(clientId, appId, aduId, 0, Integer.MAX_VALUE);
    }

    public void deleteAllFilesUpTo(String clientId, String appId, long aduId) throws IOException {
        var metadata = getMetadata(clientId, appId);
        StreamExt.takeWhile(getADUs(clientId, appId), adu -> adu.getADUId() <= aduId).forEach(adu -> {
            // we don't want to delete negative aduIds since they are in process
            if (adu.getADUId() < 0) {
                logger.log(INFO, "Skipping deletion of negative ADU ID " + adu.getADUId());
            } else if (!adu.getSource().delete()) {
                logger.log(SEVERE, "Failed to delete file " + adu);
            }
        });
        if (metadata.lastAduDeleted < aduId) {
            metadata.lastAduDeleted = aduId;
            setMetadata(clientId, appId, metadata);
        }
    }

    public byte[] getADU(String clientId, String appId, long aduId, long offset, int readLimit) throws IOException {
        var appFolder = getAppFolder(clientId, appId);
        try (var fis = new FileInputStream(appFolder.resolve(Long.toString(aduId)).toFile())) {
            if (fis.skip(offset) != offset) {
                logger.log(SEVERE, "Failed to skip to offset " + offset + " in file " + appFolder.resolve(appId));
                return new byte[0];
            }
            long fileSize = fis.getChannel().size();
            if (readLimit > fileSize - offset) {
                readLimit = (int) (fileSize - offset);
            }
            byte[] data = new byte[readLimit];
            int bytesRead = fis.read(data);
            if (bytesRead < 0) {
                return new byte[0];
            }
            return data;
        }
    }

    private Path getAppFolder(String clientId, String appId) {
        return clientId == null ? rootFolder.resolve(appId) : rootFolder.resolve(Paths.get(clientId, appId));
    }

    public File getADUFile(String clientId, String appId, long aduId) {
        var appFolder = getAppFolder(clientId, appId);
        return appFolder.resolve(Long.toString(aduId)).toFile();
    }

    public long getLastADUIdAdded(String clientId, String appId) {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastAduAdded;
    }

    public long getLastADUIdDeleted(String clientId, String appId) {
        Metadata metadata = getMetadata(clientId, appId);
        return metadata.lastAduDeleted;
    }

    public File addADU(String clientId, String appId, byte[] data, long aduId) throws IOException {
        return addADU(clientId, appId, data, aduId, 0, null);
    }

    /**
     * @param clientId
     * @param appId
     * @param data
     * @param aduId    if -1 we will set to next ID
     * @return
     * @throws IOException
     */
    public File addADU(String clientId, String appId, byte[] data, long aduId, long offset, Boolean finished) throws
            IOException {
        var appFolder = getAppFolder(clientId, appId);
        Metadata metadata = getMetadata(clientId, appId);
        var lastAduDeleted = metadata.lastAduDeleted;
        var lastAduAdded = metadata.lastAduAdded;

        if (aduId >= 0 && aduId <= lastAduDeleted) {
            logger.log(INFO, "ADU ID " + aduId + " is less than last deleted ADU ID " + lastAduDeleted + " skipping");
            return null;
        }

        // write out the data to the path for the aduId (this might be a negative number, so we will fix later)
        Path aduPath = appFolder.resolve(java.lang.Long.toString(aduId));
        logger.log(FINE, "Writing partial " + appId + ":" + aduId + " to " + aduPath + " with offset " + offset);
        // write data to aduPath at the specified file offset
        try (FileOutputStream fos = new FileOutputStream(aduPath.toFile(), true)) {
            var channel = fos.getChannel();
            if (channel.size() > offset) {
                logger.log(WARNING,
                           "Offset " + offset + " is greater than file size " + channel.size() + " truncating");
                channel.truncate(offset);
            }
            channel.position(offset);
            fos.write(data);
        }

        if (finished == null || finished) {
            if (aduId < 0) {
                // if aduId is negative, we need to set it to the last adu added
                // and move the temporary file to the new location
                aduId = lastAduAdded + 1;
                var oldAduPath = aduPath;
                aduPath = appFolder.resolve(java.lang.Long.toString(aduId));
                Files.move(oldAduPath, aduPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // if finished, we need to set the last adu added
            if (aduId > lastAduAdded) {
                logger.log(INFO, "updating lastAdu from " + lastAduAdded + " to " + aduId);
                metadata.lastAduAdded = aduId;
                setMetadata(clientId, appId, metadata);
            }
        }

        return aduPath.toFile();
    }
}
