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
}