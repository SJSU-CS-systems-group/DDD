package net.discdd.server.applicationdatamanager;

import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.LargestAduIdDeliveredRepository;
import net.discdd.server.repository.LargestAduIdReceivedRepository;
import net.discdd.server.repository.LargestBundleIdReceivedRepository;
import net.discdd.server.repository.LastBundleIdSentRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.SentBundleDetailsRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.model.ADU;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

class ApplicationDataManagerTest {
    LargestAduIdReceivedRepository largestAduIdReceivedRepository = mock(LargestAduIdReceivedRepository.class);
    RegisteredAppAdapterRepository registeredAppAdapterRepository = mock(RegisteredAppAdapterRepository.class);

    private ApplicationDataManager applicationDataManager = mock(ApplicationDataManager.class);
    private LargestAduIdDeliveredRepository largestAduDeliveredRepository = mock(LargestAduIdDeliveredRepository.class);
    private LastBundleIdSentRepository lastBundleIdSentRepository = mock(LastBundleIdSentRepository.class);
    private LargestBundleIdReceivedRepository largestBundleIdReceivedRepository = mock(LargestBundleIdReceivedRepository.class);
    private SentBundleDetailsRepository sentBundleDetailsRepository = mock(SentBundleDetailsRepository.class);
    private SentAduDetailsRepository sentAduDetailsRepositry = mock(SentAduDetailsRepository.class);
    private BundleServerConfig bundleServerConfig = mock(BundleServerConfig.class);

    @BeforeEach
    void setUp() {
        applicationDataManager = new ApplicationDataManager((a,b) -> System.out.println("hello"),largestAduIdReceivedRepository, largestAduDeliveredRepository, lastBundleIdSentRepository, largestBundleIdReceivedRepository, sentBundleDetailsRepository, sentAduDetailsRepositry, registeredAppAdapterRepository, bundleServerConfig);
    }

    @Test
    void testPersistADUsForServer() throws Exception {
        // Setup
        String clientId = "client1";
        String appId = "app1";
        var adus = new ArrayList<ADU>();
        for (int i = 1; i < 20; i++) {
            var tempFile = File.createTempFile("temp" + i, ".tmp");
            Files.write(tempFile.toPath(), ("test" + i).getBytes());
            adus.add(new ADU(tempFile, appId, i, tempFile.length(), clientId));
        }

        applicationDataManager.storeReceivedADUs(clientId, "client1", adus);
        var fetchedAdus = applicationDataManager.fetchADUsToSend(20, clientId);
        Assertions.assertArrayEquals(adus.toArray(), fetchedAdus.toArray());
    }
}