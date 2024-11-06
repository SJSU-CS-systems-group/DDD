package net.discdd.server;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.pathutils.TransportPaths;
import net.discdd.transport.TransportToBundleServerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import static net.discdd.transport.TransportToBundleServerManager.RECENCY_BLOB_BIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class BundleTransportToBundleServerTest extends End2EndTest {
    private static final Logger logger = Logger.getLogger(BundleTransportToBundleServerTest.class.getName());
    private static TransportToBundleServerManager manager;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceStub stub;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingStub;
    private static ManagedChannel channel;
    private Path toClientPath;
    private Path toServerPath;

    @BeforeEach
    void setUp() {
        // Initialize the TransportPaths class
        TransportPaths transportPaths = new TransportPaths(End2EndTest.tempRootDir);

        toClientPath = transportPaths.toClientPath;
        toServerPath = transportPaths.toServerPath;

        manager = new TransportToBundleServerManager(transportPaths, "localhost",
                                                     Integer.toString(BUNDLESERVER_GRPC_PORT), (Void) -> {
            System.out.println("connectComplete");
            return null;
        }, (Exception e) -> {
            System.out.println("connectError");
            return null;
        });
    }

    @BeforeEach
    void setUpEach() {
        channel = ManagedChannelBuilder.forAddress("localhost", BUNDLESERVER_GRPC_PORT).usePlaintext().build();
        stub = BundleExchangeServiceGrpc.newStub(channel);
        blockingStub = BundleExchangeServiceGrpc.newBlockingStub(channel);

        toClientPath.toFile().mkdirs();
        toServerPath.toFile().mkdirs();
    }

    @AfterEach
    void tearDownEach() throws IOException {
        // Delete the files and directories
        recursiveDelete(toClientPath);
        recursiveDelete(toServerPath);
        // Shutdown the channel
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private void recursiveDelete(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path).sorted(Comparator.reverseOrder()) // Delete files before directories
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            logger.severe("Failed to delete file: " + file + ", error: " + e.getMessage());
                        }
                    });
        }
    }

    @Test
    void testRun() throws IOException {
        // Create files for upload
        Files.createFile(toServerPath.resolve("bundle1"));
        Files.createFile(toServerPath.resolve("bundle2"));
        Files.createFile(toServerPath.resolve("bundle3"));

        // Create files for download
        Files.createFile(toClientPath.resolve("bundle4"));
        Files.createFile(toClientPath.resolve("bundle5"));
        Files.createFile(toClientPath.resolve("bundle6"));

        // Create files for deletion
        Files.createFile(toClientPath.resolve("bundle7"));
        Files.createFile(toClientPath.resolve("bundle8"));
        Files.createFile(toClientPath.resolve("bundle9"));

        manager.run();

        // Verify that the files were deleted after upload, download, and deletion
        assertEquals(0, Files.list(toServerPath).count(), "/server is not empty");
        // /client should have one file for recency blob
        assertEquals(1, Files.list(toClientPath).count(), "/client should only be left with recencyBlob.bin");
    }

    @Test
    void testRecencyBlob() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
            IOException {
        // Prepare to process recency blob
        Method processRecencyBlob = TransportToBundleServerManager.class.getDeclaredMethod("processRecencyBlob",
                                                                                           BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub.class);
        processRecencyBlob.setAccessible(true);
        processRecencyBlob.invoke(manager, blockingStub);

        // Verify that recency blob file was created
        Path blobPath = toClientPath.resolve(RECENCY_BLOB_BIN);
        assertTrue(Files.exists(blobPath), "Recency Blob should have been written.");

        // Validate the contents of the recency blob
        byte[] blobData = Files.readAllBytes(blobPath);
        assertNotNull(blobData, "Recency Blob data should not be empty.");
        assertTrue(blobData.length > 0, "Recency Blob should contain data.");

        logger.info("Recency Blob processed successfully with size: " + blobData.length);

        // Check timestamp of recency blob
        FileTime lastModifiedTime = Files.getLastModifiedTime(blobPath);
        logger.info("Recency Blob last modified time: " + lastModifiedTime);
        long currentTime = System.currentTimeMillis();
        long lastModifiedMillis = lastModifiedTime.toMillis();
        assertTrue((currentTime - lastModifiedMillis) < 50000,
                   "Recency Blob should have been modified within the last 5 seconds.");
    }

    @Test
    void testUploadBundles() throws IOException, NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {
        // Create files on client side
        Files.createFile(toServerPath.resolve("bundle1"));
        Files.createFile(toServerPath.resolve("bundle2"));
        Files.createFile(toServerPath.resolve("bundle3"));
        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the client
        List<EncryptedBundleId> bundlesToUpload =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toServerPath);

        // Assert that bundles are successfully added to /server path
        assertEquals(3, bundlesToUpload.size(), "The number of bundles should be 3.");

        // Prepare to upload the bundles
        Method processUploadBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processUploadBundles", List.class,
                                                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processUploadBundles.setAccessible(true);

        // Upload all bundles
        processUploadBundles.invoke(manager, bundlesToUpload, stub);

        // Check that the bundles were uploaded to server
        for (EncryptedBundleId toUpload : bundlesToUpload) {
            Path uploadPath = toServerPath.resolve(toUpload.getEncryptedId());
            assertFalse(Files.exists(uploadPath),
                        toUpload.getEncryptedId() + " should have been deleted after upload.");
        }

        assertEquals(0, Files.list(toServerPath).count());
    }

    @Test
    void testDownloadBundles() throws IOException, NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {
        // Create files on server side
        Files.createFile(toClientPath.resolve("bundle1"));
        Files.createFile(toClientPath.resolve("bundle2"));
        Files.createFile(toClientPath.resolve("bundle3"));
        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the server
        List<EncryptedBundleId> bundlesToDownload =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toClientPath);

        // Assert that bundles are successfully created
        assertEquals(3, bundlesToDownload.size(), "The number of bundles should be 3.");

        // Prepare to download the bundles
        Method processDownloadBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processDownloadBundles", List.class,
                                                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processDownloadBundles.setAccessible(true);

        // Download all bundles from the server
        processDownloadBundles.invoke(manager, bundlesToDownload, stub);

        // Check that the bundles were downloaded to /client
        for (EncryptedBundleId toDownload : bundlesToDownload) {
            Path downloadPath = toClientPath.resolve(toDownload.getEncryptedId());
            assertTrue(Files.exists(downloadPath), toDownload.getEncryptedId() + " should have been downloaded.");
        }
        assertEquals(3, Files.list(toClientPath).count());
    }

    @Test
    void testDownloadWithSendingBundles() throws Exception {
        var toSendDir = End2EndTest.tempRootDir.resolve("BundleTransmission/bundle-generation/to-send");
        // create bundle files to be sent
        Files.createFile(toSendDir.resolve("bundle1"));
        Files.createFile(toSendDir.resolve("bundle2"));
        Files.createFile(toSendDir.resolve("bundle3"));

        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);
        List<EncryptedBundleId> bundlesToDownload =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toSendDir);

        Method processDownloadBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processDownloadBundles", List.class,
                                                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processDownloadBundles.setAccessible(true);

        processDownloadBundles.invoke(manager, bundlesToDownload, stub);

        // check if /client contains those EncryptedBundleIds
        for (EncryptedBundleId toDownload : bundlesToDownload) {
            Path downloadPath = toClientPath.resolve(toDownload.getEncryptedId());
            assertTrue(Files.exists(downloadPath),
                       toDownload.getEncryptedId() + " should have been sent and downloaded.");
        }
        assertEquals(3, Files.list(toClientPath).count());
    }

    @Test
    void testDeleteBundles() throws Exception {
        //Create files on server side
        Files.createFile(toClientPath.resolve("bundle1"));
        Files.createFile(toClientPath.resolve("bundle2"));
        Files.createFile(toClientPath.resolve("bundle3"));
        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the server
        List<EncryptedBundleId> bundlesToDelete =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toClientPath);

        // Assert that bundles are successfully added to /client path
        assertEquals(3, bundlesToDelete.size(), "The number of bundles should be 3.");

        // Prepare to delete bundles
        Method processDeleteBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processDeleteBundles", List.class);
        processDeleteBundles.setAccessible(true);

        // Delete all bundles
        processDeleteBundles.invoke(manager, bundlesToDelete);

        // Assert that the bundles have been deleted on client side
        for (EncryptedBundleId toDelete : bundlesToDelete) {
            Path deletePath = toClientPath.resolve(toDelete.getEncryptedId());
            assertFalse(Files.exists(deletePath), toDelete.getEncryptedId() + " should be deleted: " + deletePath);
        }
    }

    @Test
    void testUploadNonExistentBundle() throws InvocationTargetException, IllegalAccessException, IOException,
            NoSuchMethodException {
        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the empty directory
        List<EncryptedBundleId> bundlesToUpload =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toServerPath);
        assertTrue(bundlesToUpload.isEmpty(), "The list of bundles should be empty.");

        // Attempt to upload bundles
        Method processUploadBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processUploadBundles", List.class,
                                                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processUploadBundles.setAccessible(true);
        processUploadBundles.invoke(manager, bundlesToUpload, stub);

        // Verify that no files were uploaded
        assertEquals(0, Files.list(toServerPath).count(), "No files should have been uploaded.");
    }

    @Test
    void testDownloadNonExistentBundle() throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException, IOException {
        Method populateListFromPath =
                TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the empty directory
        List<EncryptedBundleId> bundlesToDownload =
                (List<EncryptedBundleId>) populateListFromPath.invoke(manager, toClientPath);
        assertTrue(bundlesToDownload.isEmpty(), "The list of bundles should be empty.");

        // Attempt to download bundles
        Method processDownloadBundles =
                TransportToBundleServerManager.class.getDeclaredMethod("processDownloadBundles", List.class,
                                                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processDownloadBundles.setAccessible(true);
        processDownloadBundles.invoke(manager, bundlesToDownload, stub);

        // Verify that no files were downloaded
        assertEquals(0, Files.list(toClientPath).count(), "No files should have been downloaded.");
    }

}