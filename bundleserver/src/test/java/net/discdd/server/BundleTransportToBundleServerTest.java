package net.discdd.server;


import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.discdd.grpc.BundleExchangeServiceGrpc;

import net.discdd.grpc.EncryptedBundleId;
import net.discdd.transport.TransportToBundleServerManager;
import org.junit.jupiter.api.*;

import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static net.discdd.transport.TransportToBundleServerManager.RECENCY_BLOB_BIN;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
@SpringBootTest(classes = {BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class})
@TestMethodOrder(MethodOrderer.MethodName.class)
public class BundleTransportToBundleServerTest extends End2EndTest {
    private static final Logger logger = Logger.getLogger(BundleTransportToBundleServerTest.class.getName());
    private static TransportToBundleServerManager manager;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceStub stub;
    private static BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingStub;
    private static ManagedChannel channel;
    private static Path fromClientPath;
    private static Path fromServerPath;

    @BeforeAll
    static void setUp() throws Exception {
        manager = new TransportToBundleServerManager(
                End2EndTest.tempRootDir,
                "localhost",
                Integer.toString(TEST_ADAPTER_GRPC_PORT),
                (Void) -> {System.out.println("connectComplete"); return null;},
                (Exception e) -> {System.out.println("connectError"); return null;});
        fromClientPath = End2EndTest.tempRootDir.resolve("BundleTransmission/server");
        fromServerPath = End2EndTest.tempRootDir.resolve("BundleTransmission/client");
    }

    @BeforeEach
    void setUpEach() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", BUNDLESERVER_GRPC_PORT).usePlaintext().build();
        stub = BundleExchangeServiceGrpc.newStub(channel);
        blockingStub = BundleExchangeServiceGrpc.newBlockingStub(channel);

        try {
            if (!Files.exists(fromServerPath) || !Files.isDirectory(fromClientPath)) {
                Files.createDirectories(fromServerPath);
                Files.createDirectories(fromClientPath);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to get inventory", e);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up directories and files
        if (Files.exists(fromServerPath)) {
            Files.walk(fromServerPath)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            logger.severe("Failed to delete file: " + file + ", error: " + e.getMessage());
                        }
                    });
            Files.deleteIfExists(fromServerPath);
        }

        if (Files.exists(fromClientPath)) {
            Files.walk(fromClientPath)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            logger.severe("Failed to delete file: " + file + ", error: " + e.getMessage());
                        }
                    });
            Files.deleteIfExists(fromClientPath);
        }

        // Shutdown the channel
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void testRecencyBlob() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, IOException {
        // Prepare to process recency blob
        Method processRecencyBlob = TransportToBundleServerManager.class.getDeclaredMethod("processRecencyBlob", BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub.class);
        processRecencyBlob.setAccessible(true);
        processRecencyBlob.invoke(manager, blockingStub);

        // Verify that recency blob file was created
        Path blobPath = fromServerPath.resolve(RECENCY_BLOB_BIN);
        assertTrue(Files.exists(blobPath), "Recency Blob should have been written.");

        // Validate the contents of the recency blob
        byte[] blobData = Files.readAllBytes(blobPath);
        assertNotNull(blobData, "Recency Blob data should not be empty.");
        assertTrue(blobData.length > 0, "Recency Blob should contain data.");

        logger.info("Recency Blob processed successfully with size: " + blobData.length);
    }

    @Test
    void testUploadBundles() throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // Create files on client side
        Files.createFile(fromClientPath.resolve("bundle1"));
        Files.createFile(fromClientPath.resolve("bundle2"));
        Files.createFile(fromClientPath.resolve("bundle3"));
        Method populateListFromPath = TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the client
        List<EncryptedBundleId> bundlesToUpload = (List<EncryptedBundleId>) populateListFromPath.invoke(manager, fromClientPath);

        // Assert that bundles are successfully added to /server path
        assertEquals(3, bundlesToUpload.size(), "The number of bundles should be 3.");

        // Prepare to upload the bundles
        Method processUploadBundles = TransportToBundleServerManager.class.getDeclaredMethod("processUploadBundles", List.class, BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processUploadBundles.setAccessible(true);

        // Upload all bundles
        processUploadBundles.invoke(manager, bundlesToUpload, stub);

        // Check that the bundles were uploaded to server
        for (EncryptedBundleId toUpload : bundlesToUpload) {
            Path uploadPath = fromClientPath.resolve(toUpload.getEncryptedId());
            assertFalse(Files.exists(uploadPath), toUpload.getEncryptedId() + " should have been deleted after upload.");
        }
    }

    @Test
    void testDownloadBundles() throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // Create files on server side
        Files.createFile(fromServerPath.resolve("bundle1"));
        Files.createFile(fromServerPath.resolve("bundle2"));
        Files.createFile(fromServerPath.resolve("bundle3"));
        Method populateListFromPath = TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the server
        List<EncryptedBundleId> bundlesToDownload = (List<EncryptedBundleId>) populateListFromPath.invoke(manager, fromServerPath);

        // Assert that bundles are successfully created
        assertEquals(3, bundlesToDownload.size(), "The number of bundles should be 3.");

        // Prepare to download the bundles
        Method processDownloadBundles = TransportToBundleServerManager.class.getDeclaredMethod("processDownloadBundles", List.class, BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processDownloadBundles.setAccessible(true);

        // Download all bundles from the server
        processDownloadBundles.invoke(manager, bundlesToDownload, stub);

        // Check that the bundles were downloaded to the client
        for (EncryptedBundleId toDownload : bundlesToDownload) {
            Path downloadPath = fromServerPath.resolve(toDownload.getEncryptedId());
            assertTrue(Files.exists(downloadPath), toDownload.getEncryptedId() + " should have been downloaded.");
        }
    }

    @Test
    void testDeleteBundles() throws Exception {
        //Create files on server side
        Files.createFile(fromServerPath.resolve("bundle1"));
        Files.createFile(fromServerPath.resolve("bundle2"));
        Files.createFile(fromServerPath.resolve("bundle3"));
        Method populateListFromPath = TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the server
        List<EncryptedBundleId> bundlesToDelete = (List<EncryptedBundleId>) populateListFromPath.invoke(manager, fromServerPath);

        // Assert that bundles are successfully added to /client path
        assertEquals(3, bundlesToDelete.size(), "The number of bundles should be 3.");

        // Prepare to delete bundles
        Method processDeleteBundles = TransportToBundleServerManager.class.getDeclaredMethod("processDeleteBundles", List.class);
        processDeleteBundles.setAccessible(true);

        // Delete all bundles
        processDeleteBundles.invoke(manager, bundlesToDelete);

        // Assert that the bundles have been deleted on client side
        for (EncryptedBundleId toDelete : bundlesToDelete) {
            Path deletePath = fromServerPath.resolve(toDelete.getEncryptedId());
            assertFalse(Files.exists(deletePath),  toDelete.getEncryptedId() + " should be deleted: " + deletePath);
        }
    }

    @Test
    void testUploadNonExistentBundle() throws InvocationTargetException, IllegalAccessException, IOException, NoSuchMethodException {
        Method populateListFromPath = TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the empty directory
        List<EncryptedBundleId> bundlesToUpload = (List<EncryptedBundleId>) populateListFromPath.invoke(manager, fromClientPath);
        assertTrue(bundlesToUpload.isEmpty(), "The list of bundles should be empty.");

        // Attempt to upload bundles
        Method processUploadBundles = TransportToBundleServerManager.class.getDeclaredMethod("processUploadBundles", List.class, BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processUploadBundles.setAccessible(true);
        processUploadBundles.invoke(manager, bundlesToUpload, stub);

        // Verify that no files were uploaded
        assertEquals(0, Files.list(fromClientPath).count(), "No files should have been uploaded.");
    }

    @Test
    void testDownloadNonExistentBundle() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, IOException {
        Method populateListFromPath = TransportToBundleServerManager.class.getDeclaredMethod("populateListFromPath", Path.class);
        populateListFromPath.setAccessible(true);

        // Retrieve the bundles from the empty directory
        List<EncryptedBundleId> bundlesToDownload = (List<EncryptedBundleId>) populateListFromPath.invoke(manager, fromServerPath);
        assertTrue(bundlesToDownload.isEmpty(), "The list of bundles should be empty.");

        // Attempt to download bundles
        Method processDownloadBundles = TransportToBundleServerManager.class.getDeclaredMethod("processDownloadBundles", List.class, BundleExchangeServiceGrpc.BundleExchangeServiceStub.class);
        processDownloadBundles.setAccessible(true);
        processDownloadBundles.invoke(manager, bundlesToDownload, stub);

        // Verify that no files were downloaded
        assertEquals(0, Files.list(fromServerPath).count(), "No files should have been downloaded.");
    }

}