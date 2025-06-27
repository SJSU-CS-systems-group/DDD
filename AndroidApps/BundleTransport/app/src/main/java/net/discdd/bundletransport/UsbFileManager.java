package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.pathutils.TransportPaths;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UsbFileManager {
    private static final Logger logger = Logger.getLogger(UsbFileManager.class.getName());
    private static final String USB_DIR_NAME = "DDD_transport";
    private static final String RELATIVE_CLIENT_PATH = "toClient";
    private static final String RELATIVE_SERVER_PATH = "toServer";
    private StorageManager storageManager;
    private TransportPaths transportPaths;

    public UsbFileManager(StorageManager storageManager, TransportPaths transportPaths) {
        this.storageManager = storageManager;
        this.transportPaths = transportPaths;
    }

    /**
     * Finds appropriate usb dir for ddd activities and copies device files.
     *
     * @throws IOException
     */
    public boolean populateUsb() throws IOException {
        boolean result = true;
        List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
        for (StorageVolume volume : storageVolumes) {
            if (volume.isRemovable() && !volume.isEmulated()) {
                File usbStorageDir = volume.getDirectory();
                if (usbStorageDir != null) {
                    File usbTransportDir = new File(usbStorageDir, USB_DIR_NAME);
                    File usbTransportToServerDir = new File(usbTransportDir, RELATIVE_SERVER_PATH);
                    File usbTransportToClientDir = new File(usbTransportDir, RELATIVE_CLIENT_PATH);
                    if (!usbTransportDir.exists()) {
                        usbTransportDir.mkdirs();
                        usbTransportToServerDir.mkdirs();
                        usbTransportToClientDir.mkdirs();
                    } else {
                        if (!usbTransportToServerDir.exists()) {
                            usbTransportToServerDir.mkdirs();
                        }
                        if (!usbTransportToClientDir.exists()) {
                            usbTransportToClientDir.mkdirs();
                        }
                    }
                    try {
                        toClientList(usbTransportToClientDir);
                        toServerList(usbTransportToServerDir);
                    } catch (Exception e) {
                        result = false;
                        logger.log(WARNING, "Failed to populate USB and or Android device");
                        throw new RuntimeException("Bad call to populate USB or Android device", e);
                    } finally {
                        reduceUsbFiles(usbTransportToClientDir, usbTransportToServerDir);
                        return result;
                    }
                }
                // to be handled by return type: updateUsbStatus(true, "Sync successful", Color.GREEN);
            }
        }
        logger.log(WARNING, "No removable volumes in USB");
        //notify user with this status
        return false;
    }

    /**
     * Copies for-client files from transport device (handling downstream).
     *
     * @param targetDir target directory; USBs client directory
     * @throws IOException
     */
    private void toClientList(File targetDir) throws IOException, GeneralSecurityException,
            RoutingExceptions.ClientMetaDataFileException, InvalidKeyException {
        List<Path> storageList;
        Path devicePathForClient = transportPaths.toClientPath;
        try (Stream<Path> walk = Files.walk(devicePathForClient)) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        if (storageList.isEmpty()) {
            logger.log(INFO, "No bundles to download from device to USB (to client files)");
            logger.log(INFO, "Our empty storageList was " + devicePathForClient);
            return;
        }
        for (Path deviceFilePath : storageList) {
            Path targetPath = targetDir.toPath().resolve(deviceFilePath.getFileName());
            Files.copy(deviceFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copies for-server files from usb (handling upstream).
     *
     * @param sourceDir source directory; USBs server directory
     */
    private void toServerList(File sourceDir) throws IOException {
        List<Path> storageList;
        try (Stream<Path> walk = Files.walk(sourceDir.toPath())) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        if (storageList.isEmpty()) {
            logger.log(INFO, "No bundles to download from USB to device (to server files)");
            logger.log(INFO, "Our empty storageList was " + sourceDir);
            return;
        }
        Path devicePathForServer = transportPaths.toServerPath;
        for (Path usbFilePath : storageList) {
            Path targetPath = devicePathForServer.resolve(usbFilePath.getFileName());
            Files.copy(usbFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Delete USB toClient and toServer files when files missing from transport and already on transport respectively.
     *
     * @param usbTransportToClientDir
     * @param usbTransportToServerDir
     * @throws IOException
     */
    private void reduceUsbFiles(File usbTransportToClientDir, File usbTransportToServerDir) throws IOException {
        List<Path> usbToClient;
        try (Stream<Path> walk = Files.walk(usbTransportToClientDir.toPath())) {
            usbToClient = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        boolean deletedClientFiles = false;
        for (Path usbFile : usbToClient) {
            File possibleFile = new File(String.valueOf(transportPaths.toClientPath), usbFile.getFileName().toString());
            boolean usbFileExistsInAndroid = possibleFile.exists();
            if (!usbFileExistsInAndroid) {
                Files.deleteIfExists(usbFile);
                deletedClientFiles = true;
            }
        }
        String res = (deletedClientFiles) ?
                     "Successfully deleted excess client files from USB" :
                     "No excess client files to delete from USB";
        logger.log(INFO, res);
        List<Path> androidToServer;
        try (Stream<Path> walk = Files.walk(transportPaths.toServerPath)) {
            androidToServer = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        boolean deletedServerFiles = false;
        for (Path usbFile : androidToServer) {
            File possibleFile = new File(usbTransportToServerDir, usbFile.getFileName().toString());
            boolean androidFileExistsInUsb = possibleFile.exists();
            if (androidFileExistsInUsb) {
                Files.deleteIfExists(possibleFile.toPath());
                deletedServerFiles = true;
            }
        }
        res = (deletedServerFiles) ?
              "Successfully deleted excess server files from USB" :
              "No excess server files to delete from USB";
        logger.log(INFO, res);
    }
}
