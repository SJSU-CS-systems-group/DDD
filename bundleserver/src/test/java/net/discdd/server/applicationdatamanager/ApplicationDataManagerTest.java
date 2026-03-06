package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientBundleCountersRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Also, these tests use H2 database for testing
 */
@DataJpaTest
@Import({ GrpcExecutor.class })
public class ApplicationDataManagerTest {
    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> tempDir.toString());
    }

    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository;
    private ServerApplicationDataManager applicationDataManager;
    @Autowired
    private SentAduDetailsRepository sentAduDetailsRepository;
    @Autowired
    private BundleMetadataRepository bundleMetadataRepository;
    @Autowired
    private ClientBundleCountersRepository clientBundleCountersRepository;
    private BundleServerConfig bundleServerConfig; // = mock(BundleServerConfig.class);
    private StoreADUs receiveADUsStorage;
    private StoreADUs sendADUsStorage;
    @TempDir
    Path tempRootDir;

    @Test
    public void testEverything() throws Exception {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter("app1", "localhost:88888"));
        bundleServerConfig = new BundleServerConfig();
        bundleServerConfig.getApplicationDataManager().setAppDataSizeLimit(100_000_000L);
        applicationDataManager = new ServerApplicationDataManager(new AduStores(tempRootDir),
                                                                  (a, b) -> System.out.println("hello"),
                                                                  sentAduDetailsRepository,
                                                                  bundleMetadataRepository,
                                                                  registeredAppAdapterRepository,
                                                                  clientBundleCountersRepository,
                                                                  bundleServerConfig);
        var receiveADUsStorageField = ServerApplicationDataManager.class.getDeclaredField("receiveADUsStorage");
        receiveADUsStorageField.setAccessible(true);
        receiveADUsStorage = (StoreADUs) receiveADUsStorageField.get(applicationDataManager);
        var sendADUsStorageField = ServerApplicationDataManager.class.getDeclaredField("sendADUsStorage");
        sendADUsStorageField.setAccessible(true);
        sendADUsStorage = (StoreADUs) sendADUsStorageField.get(applicationDataManager);

        Assertions.assertNotNull(applicationDataManager);
        Assertions.assertNotNull(receiveADUsStorage);
        Assertions.assertNotNull(sendADUsStorage);

        String clientId = "client1";
        String appId = "app1";
        var adus = new ArrayList<ADU>();
        for (int i = 1; i < 20; i++) {
            var tempFile = File.createTempFile("temp" + i, ".tmp");
            Files.write(tempFile.toPath(), ("test" + i).getBytes());
            adus.add(new ADU(tempFile, appId, i, tempFile.length(), clientId));
        }
        applicationDataManager.storeReceivedADUs(clientId, "client1", 1, adus);
        Assertions.assertEquals(adus.size(), receiveADUsStorage.getLastADUIdAdded(clientId, appId));
        var fetchedAdus = receiveADUsStorage.getAppData(clientId, appId);
        Assertions.assertArrayEquals(adus.toArray(), fetchedAdus.toArray());
    }

    private ServerApplicationDataManager buildManager() throws Exception {
        var cfg = new BundleServerConfig();
        cfg.getApplicationDataManager().setAppDataSizeLimit(100_000_000L);
        return new ServerApplicationDataManager(new AduStores(tempRootDir),
                                                (a, b) -> {},
                                                sentAduDetailsRepository,
                                                bundleMetadataRepository,
                                                registeredAppAdapterRepository,
                                                clientBundleCountersRepository,
                                                cfg);
    }

    private StoreADUs getSendStorage(ServerApplicationDataManager mgr) throws Exception {
        var field = ServerApplicationDataManager.class.getDeclaredField("sendADUsStorage");
        field.setAccessible(true);
        return (StoreADUs) field.get(mgr);
    }

    /**
     * Core regression test for the newDataToSend() fix.
     * Before the fix: after processAcknowledgement() deleted SentAduDetails, newDataToSend()
     * would compare lastStoredAdu (e.g. 1) against an empty map (0) and return true incorrectly.
     * After the fix: getLastADUIdDeleted() provides the correct floor so the result is false.
     */
    @Test
    public void testNewDataToSendFalseAfterAckNoNewADUs() throws Exception {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter("app1", "localhost:11111"));
        var mgr = buildManager();
        var sendStorage = getSendStorage(mgr);

        String clientId = "clientAck1";
        String bundleId = "bundle-ack-1";

        sendStorage.addADU(clientId, "app1", "payload".getBytes(), 1L);
        mgr.registerNewBundleId(clientId, bundleId, 1L, 0L);
        mgr.fetchADUsToSend(bundleId, 1L, 0L, clientId);

        // Before ACK: SentAduDetails intact, no new data
        assertFalse(mgr.newDataToSend(bundleId));

        // ACK: deletes SentAduDetails records and ADU files, sets lastAduDeleted = 1
        mgr.processAcknowledgement(clientId, bundleId);

        // After ACK with no new ADUs: must still be false (was returning true before fix)
        assertFalse(mgr.newDataToSend(bundleId));
    }

    /**
     * Verifies that after an ACK, if a genuinely new ADU arrives, newDataToSend() returns true.
     */
    @Test
    public void testNewDataToSendTrueAfterAckWithNewADU() throws Exception {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter("app1", "localhost:11112"));
        var mgr = buildManager();
        var sendStorage = getSendStorage(mgr);

        String clientId = "clientAck2";
        String bundleId = "bundle-ack-2";

        sendStorage.addADU(clientId, "app1", "payload1".getBytes(), 1L);
        mgr.registerNewBundleId(clientId, bundleId, 1L, 0L);
        mgr.fetchADUsToSend(bundleId, 1L, 0L, clientId);
        mgr.processAcknowledgement(clientId, bundleId);

        // Add a new ADU with a higher ID after the ACK
        sendStorage.addADU(clientId, "app1", "payload2".getBytes(), 2L);

        // New data exists — must return true
        assertTrue(mgr.newDataToSend(bundleId));
    }

    /**
     * Regression guard: verifies the pre-ACK path still works correctly.
     */
    @Test
    public void testNewDataToSendBeforeAck() throws Exception {
        registeredAppAdapterRepository.save(new RegisteredAppAdapter("app1", "localhost:11113"));
        var mgr = buildManager();
        var sendStorage = getSendStorage(mgr);

        String clientId = "clientPreAck";
        String bundleId = "bundle-pre-ack";

        sendStorage.addADU(clientId, "app1", "payload".getBytes(), 1L);
        mgr.registerNewBundleId(clientId, bundleId, 1L, 0L);
        mgr.fetchADUsToSend(bundleId, 1L, 0L, clientId);

        // SentAduDetails intact, ADU already in the bundle — no new data
        assertFalse(mgr.newDataToSend(bundleId));

        // Add a second ADU: now there IS new data
        sendStorage.addADU(clientId, "app1", "payload2".getBytes(), 2L);
        assertTrue(mgr.newDataToSend(bundleId));
    }

    /**
     * Verifies getClientIdForBundle() resolves a registered bundle and returns null for an unknown one.
     */
    @Test
    public void testGetClientIdForBundle() throws Exception {
        var mgr = buildManager();

        mgr.registerNewBundleId("clientXYZ", "encBundleXYZ", 1L, 0L);

        assertEquals("clientXYZ", mgr.getClientIdForBundle("encBundleXYZ"));
        assertNull(mgr.getClientIdForBundle("bundleThatDoesNotExist"));
    }
}