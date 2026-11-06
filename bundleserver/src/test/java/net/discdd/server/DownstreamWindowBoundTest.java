package net.discdd.server;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.grpc.BundleSenderType;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RT5: the server never issues a downstream counter outside the window the client would accept. After the client
 * processes counter k its window is k+1..k+{@link Constants#DEFAULT_WINDOW_SIZE} (initially 0..9), so with the
 * client's last ack at k the server re-offers its last bundle once it has issued k+10, however much data is queued.
 * Uses the raw Signal client of {@link End2EndTest}; test methods build on each other and run in order.
 */
@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DownstreamWindowBoundTest extends End2EndTest {
    private static final int W = Constants.DEFAULT_WINDOW_SIZE;
    @TempDir
    static Path uploads;
    /** downstream bundle ID by counter, as issued */
    static final Map<Long, String> issued = new HashMap<>();
    static int upstreamCounter;
    static long aduCounter;

    @Autowired
    ServerBundleTransmission transmission;
    @Autowired
    AduStores stores;
    @Autowired
    BundleMetadataRepository bundleMetadataRepository;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner runner;

    @Test
    @Order(1)
    void freshClientBoundedToInitialWindow() throws Exception {
        upload("HB"); // registers the client's session; acks nothing
        long last = 0;
        for (int i = 0; i < W + 5; i++) last = queueAndGenerate();
        assertEquals(W - 1, last, "a client that never acked accepts only counters 0..9");
        assertEquals(W - 1, maxIssued());
        // the re-offered bundle is the last one issued, not a new ID
        assertEquals(issued.get(W - 1L), generate());
    }

    @Test
    @Order(2)
    void resumesAfterAck() throws Exception {
        upload(issued.get(4L));
        long last = 0;
        for (int i = 0; i < W; i++) last = queueAndGenerate();
        assertEquals(4 + W, last, "after acking 4 the client accepts up to 14");

        upload(issued.get(4L + W));
        String next = generate();
        assertNotEquals(issued.get(4L + W), next, "the ack lets the queued ADUs go out at once");
        assertEquals(5 + W, counterOf(next));
    }

    @Test
    @Order(3)
    void neverIssuesCounterBeyondClientWindow() throws Exception {
        long acked = 4 + W; // the client's last ack (test 2)
        boolean reachedBound = false;
        for (int step = 1; step <= 40; step++) {
            long counter = queueAndGenerate();
            assertTrue(counter > acked && counter <= acked + W,
                       "counter " + counter + " outside the client's window " + (acked + 1) + ".." + (acked + W));
            reachedBound |= counter == acked + W;
            // every 13th step the client picks up the newest bundle and acks it
            if (step % 13 == 0) {
                upload(issued.get(counter));
                acked = counter;
            }
        }
        assertTrue(reachedBound, "the schedule must hit the window bound");
    }

    // ---------------------------------------------------------------- helpers

    /** queues one more ADU for the client and asks for its downstream bundle; returns the bundle's counter */
    private long queueAndGenerate() throws Exception {
        stores.getSendADUsStorage().addADU(clientId, TEST_APPID, ("reply" + aduCounter++).getBytes(), -1);
        return counterOf(generate());
    }

    private String generate() throws Exception {
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Exception>();
        runner.executeInTransaction(() -> {
            try {
                result.set(transmission.generateBundleForClient(clientId));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
        long counter = counterOf(result.get());
        issued.putIfAbsent(counter, result.get());
        return result.get();
    }

    private long counterOf(String bundleId) {
        return bundleMetadataRepository.findById(bundleId).orElseThrow().bundleCounter;
    }

    private static long maxIssued() {
        return issued.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** the client uploads an ack-only bundle acking {@code ackedBundleId} ("HB" for none) through a transport */
    private void upload(String ackedBundleId) throws Exception {
        long counter = ++upstreamCounter;
        var payload = new ByteArrayOutputStream();
        BundleUtils.createBundlePayloadForAdus(List.of(), "{}".getBytes(), ackedBundleId, null, payload,
                                               List.of(TEST_APPID));
        var encryptedId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, counter,
                                                                              BundleIDGenerator.UPSTREAM));
        Path jar = uploads.resolve("upstream-" + counter);
        try (var output = Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)) {
            BundleUtils.encryptPayloadAndCreateBundle(clientSessionCipher::encrypt,
                                                      clientIdentity.getPublicKey().getPublicKey(),
                                                      baseKeyPair.getPublicKey(),
                                                      serverIdentity.getPublicKey().getPublicKey(),
                                                      encryptedId,
                                                      new ByteArrayInputStream(payload.toByteArray()),
                                                      output);
        }
        runner.executeInTransaction(() -> transmission.processBundleFile(jar.toFile(),
                                                                         BundleSenderType.TRANSPORT,
                                                                         "window-test-transport"));
    }
}
