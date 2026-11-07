package net.discdd.server.bundlerouting;

import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.service.GrpcExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

@DataJpaTest
@Import({ BundleRouting.class, GrpcExecutor.class })
public class BundleRoutingTest {
    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> tempDir.toString());
        registry.add("bundle-server.routing.max-transports-per-client", () -> "3");
    }

    @Autowired
    private BundleRouting bundleRouting;
    @Autowired
    private ServerRoutingRepository serverRoutingRepository;
    @TempDir
    Path payloadDir;

    @BeforeEach
    void clearTable() {
        serverRoutingRepository.deleteAll();
    }

    private void receive(String routingMetadata, String deliveringTransport, String clientId) throws Exception {
        Files.writeString(payloadDir.resolve("routing.metadata"), routingMetadata);
        bundleRouting.processClientMetaData(payloadDir, deliveringTransport, clientId);
    }

    private long score(String transportId, String clientId) {
        return Long.parseLong(serverRoutingRepository.findByServerRoutingIdClientIDAndServerRoutingIdTransportID(
                clientId,
                transportId).getScore());
    }

    @Test
    void onlyTopTransportsCarryClient() throws Exception {
        receive("{\"T1\":5,\"T2\":4,\"T3\":3,\"T4\":2,\"T5\":1}", "T1", "C");

        Assertions.assertTrue(bundleRouting.getClientsForTransportId("T1").contains("C"));
        Assertions.assertTrue(bundleRouting.getClientsForTransportId("T2").contains("C"));
        Assertions.assertTrue(bundleRouting.getClientsForTransportId("T3").contains("C"));
        Assertions.assertFalse(bundleRouting.getClientsForTransportId("T4").contains("C"));
        Assertions.assertFalse(bundleRouting.getClientsForTransportId("T5").contains("C"));
    }

    @Test
    void transportsTiedForLastPlaceAreIncluded() throws Exception {
        receive("{\"T1\":2,\"T2\":2,\"T3\":2,\"T4\":2}", "T1", "C");

        for (var transport : new String[] { "T1", "T2", "T3", "T4" }) {
            Assertions.assertTrue(bundleRouting.getClientsForTransportId(transport).contains("C"));
        }
    }

    @Test
    void deliveringTransportKeepsReportedCount() throws Exception {
        receive("{\"T1\":5}", "T1", "C");
        Assertions.assertEquals(5, score("T1", "C"));
    }

    @Test
    void unreportedDeliveringTransportIsAddedWithoutLoweringScore() throws Exception {
        receive("{\"T9\":3}", "T1", "C");
        Assertions.assertEquals(1, score("T1", "C"));

        // T9 delivers later but is no longer in the snapshot: its earlier score stays
        receive("{}", "T9", "C");
        Assertions.assertEquals(3, score("T9", "C"));
    }

    @Test
    void deliveringTransportRecordedWhenMetadataIsUnreadable() throws Exception {
        receive("not json", "T1", "C");
        Assertions.assertEquals(1, score("T1", "C"));
    }

    @Test
    void transportsTheClientExpiredAreRemoved() throws Exception {
        receive("{\"T1\":2,\"T2\":1}", "T1", "C");
        receive("{\"T1\":3}", "T1", "C");

        Assertions.assertNull(serverRoutingRepository.findByServerRoutingIdClientIDAndServerRoutingIdTransportID("C",
                                                                                                                "T2"));
        Assertions.assertEquals(3, score("T1", "C"));
    }

    @Test
    void deliveringTransportIsKeptEvenIfNotListed() throws Exception {
        receive("{\"T2\":1}", "T1", "C");
        receive("{\"T2\":2}", "T1", "C");

        Assertions.assertEquals(1, score("T1", "C"));
        Assertions.assertEquals(2, score("T2", "C"));
    }

    @Test
    void emptyListRemovesNothing() throws Exception {
        receive("{\"T1\":2,\"T2\":1}", "T1", "C");
        receive("{}", "T1", "C");

        Assertions.assertEquals(2, score("T1", "C"));
        Assertions.assertEquals(1, score("T2", "C"));
    }

    @Test
    void directClientUploadRecordsOnlyReportedTransports() throws Exception {
        receive("{\"T1\":2}", null, "C");
        Assertions.assertEquals(1, serverRoutingRepository.findByServerRoutingIdClientID("C").size());
        Assertions.assertEquals(2, score("T1", "C"));
    }
}
