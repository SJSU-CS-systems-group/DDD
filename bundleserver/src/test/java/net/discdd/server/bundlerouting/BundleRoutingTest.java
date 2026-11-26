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
import java.util.Set;
import java.util.stream.Collectors;

@DataJpaTest
@Import({ BundleRouting.class, GrpcExecutor.class })
public class BundleRoutingTest {
    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> tempDir.toString());
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

    private Set<String> routedTransports(String clientId) {
        return serverRoutingRepository.findByServerRoutingIdClientID(clientId)
                .stream()
                .map(serverRouting -> serverRouting.getServerRoutingId().getTransportID())
                .collect(Collectors.toSet());
    }

    @Test
    void everyListedTransportCarriesTheClient() throws Exception {
        receive("{\"T1\":1,\"T2\":1,\"T3\":1,\"T4\":1,\"T5\":1}", "T1", "C");

        for (var transport : new String[] { "T1", "T2", "T3", "T4", "T5" }) {
            Assertions.assertTrue(bundleRouting.getClientsForTransportId(transport).contains("C"));
        }
    }

    @Test
    void countsInTheListAreIgnored() throws Exception {
        receive("{\"T1\":7,\"T2\":1}", "T1", "C");
        Assertions.assertEquals(Set.of("T1", "T2"), routedTransports("C"));
    }

    @Test
    void listWithoutCountsIsAccepted() throws Exception {
        receive("{\"T1\":null,\"T2\":null}", null, "C");
        Assertions.assertEquals(Set.of("T1", "T2"), routedTransports("C"));

        receive("[\"T2\",\"T3\"]", null, "C");
        Assertions.assertEquals(Set.of("T2", "T3"), routedTransports("C"));
    }

    @Test
    void invalidTransportIdsAreIgnored() throws Exception {
        receive("{\"\":1,\"" + "x".repeat(300) + "\":1,\"T1\":1}", null, "C");
        Assertions.assertEquals(Set.of("T1"), routedTransports("C"));
    }

    @Test
    void deliveringTransportRecordedWhenMetadataIsUnreadable() throws Exception {
        receive("not json", "T1", "C");
        Assertions.assertEquals(Set.of("T1"), routedTransports("C"));
    }

    @Test
    void transportsMissingFromTheListAreRemoved() throws Exception {
        receive("{\"T1\":1,\"T2\":1}", "T1", "C");
        receive("{\"T1\":1}", "T1", "C");

        Assertions.assertEquals(Set.of("T1"), routedTransports("C"));
        Assertions.assertFalse(bundleRouting.getClientsForTransportId("T2").contains("C"));
    }

    @Test
    void deliveringTransportIsKeptEvenIfNotListed() throws Exception {
        receive("{\"T2\":1}", "T1", "C");
        receive("{\"T2\":1}", "T1", "C");
        Assertions.assertEquals(Set.of("T1", "T2"), routedTransports("C"));
    }

    @Test
    void emptyListRemovesNothing() throws Exception {
        receive("{\"T1\":1,\"T2\":1}", "T1", "C");
        receive("{}", "T1", "C");
        Assertions.assertEquals(Set.of("T1", "T2"), routedTransports("C"));
    }

    @Test
    void directClientUploadRecordsOnlyListedTransports() throws Exception {
        receive("{\"T1\":1}", null, "C");
        Assertions.assertEquals(Set.of("T1"), routedTransports("C"));
    }
}
