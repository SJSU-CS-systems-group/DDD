package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.LargestAduIdDeliveredRepository;
import net.discdd.server.repository.LargestAduIdReceivedRepository;
import net.discdd.server.repository.LargestBundleIdReceivedRepository;
import net.discdd.server.repository.LastBundleIdSentRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.SentBundleDetailsRepository;
import net.discdd.server.repository.entity.RegisteredAppAdapter;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Also, these tests use H2 database for testing
 */
@DataJpaTest
public class ApplicationDataManagerTest {
    @Autowired
    private LargestAduIdReceivedRepository largestAduIdReceivedRepository; // = mock(LargestAduIdReceivedRepository.class);
    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository; // = mock(RegisteredAppAdapterRepository.class);
    private ApplicationDataManager applicationDataManager;
    @Autowired
    private LargestAduIdDeliveredRepository largestAduDeliveredRepository; // = mock(LargestAduIdDeliveredRepository.class);
    @Autowired
    private LastBundleIdSentRepository lastBundleIdSentRepository; // = mock(LastBundleIdSentRepository.class);
    @Autowired
    private LargestBundleIdReceivedRepository largestBundleIdReceivedRepository; // = mock(LargestBundleIdReceivedRepository.class);
    @Autowired
    private SentBundleDetailsRepository sentBundleDetailsRepository; // = mock(SentBundleDetailsRepository.class);
    @Autowired
    private SentAduDetailsRepository sentAduDetailsRepository; // = mock(SentAduDetailsRepository.class);
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
        applicationDataManager = new ApplicationDataManager(tempRootDir, (a,b) -> System.out.println("hello"),largestAduIdReceivedRepository, largestAduDeliveredRepository, lastBundleIdSentRepository, largestBundleIdReceivedRepository, sentBundleDetailsRepository, sentAduDetailsRepository, registeredAppAdapterRepository, bundleServerConfig);
        var receiveADUsStorageField = ApplicationDataManager.class.getDeclaredField("receiveADUsStorage");
        receiveADUsStorageField.setAccessible(true);
        receiveADUsStorage = (StoreADUs) receiveADUsStorageField.get(applicationDataManager);
        var sendADUsStorageField = ApplicationDataManager.class.getDeclaredField("sendADUsStorage");
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
        applicationDataManager.storeReceivedADUs(clientId, "client1", adus);
        var receiveMetadata = receiveADUsStorage.getMetadata(clientId, appId);
        Assertions.assertEquals((long)adus.size(), receiveMetadata.lastReceivedMessageId);
        var fetchedAdus = receiveADUsStorage.getAppData(clientId, appId);
        Assertions.assertArrayEquals(adus.toArray(), fetchedAdus.toArray());
    }
}