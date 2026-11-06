package net.discdd.server;

import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.client.bundletransmission.ClientBundleTransmission.BundleExchangeCounts;
import net.discdd.client.bundletransmission.ClientBundleTransmission.Statuses;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.bundlerouting.RoutingSource;
import net.discdd.server.bundlesecurity.RecencyBlobMinter;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.entity.RoutingEdge;
import net.discdd.server.repository.entity.RoutingEdgeId;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.DDDTLSUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of client carrier recording (routing spec RT3): a real bundle-core
 * {@link ClientBundleTransmission} runs {@code doExchangeWithTransport} against in-process fake carriers.
 * <ul>
 *   <li>A fake carrier is a TLS gRPC server ({@link DDDNettyTLS}) with its own key. Its {@code getRecencyBlob} serves
 *       a real server-minted blob ({@link RecencyBlobMinter}) naming its own TLS key, or another key's. It inherits
 *       the base class's UNIMPLEMENTED PSI, so the client falls back to downloading by ID, and it captures uploads.</li>
 *   <li>A captured upload is handed to {@link ServerBundleTransmission#processBundleFile} as that carrier's
 *       TRANSPORT upload, so the server's REPORTED edges show what the uploaded {@code routing.metadata} carried.</li>
 * </ul>
 * The tests build on one client (bundle-core's {@code ClientSecurity} is a per-process singleton) and run in order.
 */
@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CarrierRecordingEnd2EndTest extends End2EndTest {
    @TempDir
    static Path clientRoot;
    @TempDir
    static Path workDir;

    static ClientPaths clientPaths;
    static ClientBundleTransmission client;
    static String bcClientId;
    static final List<FakeCarrier> carriers = new ArrayList<>();
    static FakeCarrier carrier1;
    static String boundUploadId;
    static int fileCounter;

    @Autowired
    ServerSecurity serverSecurity;
    @Autowired
    ServerBundleTransmission serverBundleTransmission;
    @Autowired
    RoutingEdgeRepository routingEdgeRepository;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner transactionalRunner;

    @BeforeAll
    static void setUpClient() throws Exception {
        clientPaths = new ClientPaths(clientRoot,
                                      Files.readAllBytes(serverIdentityKeyPath),
                                      Files.readAllBytes(serverSignedPreKeyPath),
                                      Files.readAllBytes(serverRatchetKeyPath));
        // no RecencyTracker: processRecencyBlob reports "not newer" instead of failing, and recording ignores it
        client = new ClientBundleTransmission(clientPaths, adu -> {});
        bcClientId = client.getBundleSecurity().getClientSecurity().getClientID();
    }

    @AfterAll
    static void stopCarriers() {
        carriers.forEach(c -> c.server.shutdownNow());
    }

    @Test
    @Order(1)
    void directServerExchangeRecordsNothing() throws Exception {
        var counts = client.doExchangeWithTransport(TransportDevice.SERVER_DEVICE,
                                                    "localhost",
                                                    BUNDLESERVER_GRPC_PORT,
                                                    false);
        assertNull(counts.e());
        assertEquals(Statuses.COMPLETE, counts.uploadStatus());
        assertFalse(clientPaths.metadataFile.exists(), "the server is not a carrier");
        assertTrue(reportedEdges().isEmpty());
    }

    @Test
    @Order(2)
    void blobForOtherKeyRecordsNothing() throws Exception {
        carrier1 = newCarrier();
        // a valid server blob, but minted for another TLS key: replaying it must not name this carrier
        carrier1.blobSenderId = SecurityUtils.peerIdForTlsKey(DDDTLSUtil.generateKeyPair().getPublic());

        Path upload = exchangeUploads(carrier1, null);
        assertFalse(clientPaths.metadataFile.exists());

        carrierDelivers(carrier1, upload);
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(carrier1.peerId,
                                                                      bcClientId,
                                                                      RoutingSource.UPLOADED)),
                   "the server saw the carrier's upload");
        assertTrue(reportedEdges().isEmpty());
        carrier1.blobSenderId = carrier1.peerId;
    }

    @Test
    @Order(3)
    void exchangeRecordsBoundCarrierInSameUpload() throws Exception {
        // a full exchange: the carrier also holds the client's current downstream bundle
        String downstream = inRpcTransaction(() -> serverBundleTransmission.generateBundleForClient(bcClientId));
        Files.copy(serverBundleTransmission.getPathForBundleToSend(bcClientId, downstream),
                   carrier1.downloads.resolve(downstream));

        long before = System.currentTimeMillis();
        Path upload = exchangeUploads(carrier1, Statuses.COMPLETE);
        long after = System.currentTimeMillis();
        boundUploadId = upload.getFileName().toString();

        carrierDelivers(carrier1, upload);
        var edge = routingEdgeRepository.findById(reported(carrier1.peerId)).orElseThrow();
        assertTrue(edge.lastSeenMs >= before && edge.lastSeenMs <= after, () -> "lastSeen " + edge.lastSeenMs);
        assertEquals(List.of(carrier1.peerId), reportedEdges().stream().map(e -> e.id.peerId).toList());
    }

    @Test
    @Order(4)
    void samePeerWithinRefreshReusesBundle() throws Exception {
        long routingMtime = clientPaths.metadataFile.lastModified();
        Path upload = exchangeUploads(carrier1, Statuses.EMPTY);
        assertEquals(boundUploadId, upload.getFileName().toString(), "meeting the same carrier again changes nothing");
        assertEquals(routingMtime, clientPaths.metadataFile.lastModified());
    }

    @Test
    @Order(5)
    void uploadOnlyExchangeShipsNewPeer() throws Exception {
        var carrier2 = newCarrier();
        // nothing to download and no new ADU or ack: only the new peer can make a new bundle
        Path upload = exchangeUploads(carrier2, Statuses.EMPTY);
        assertNotEquals(boundUploadId, upload.getFileName().toString());

        carrierDelivers(carrier2, upload);
        assertTrue(routingEdgeRepository.existsById(reported(carrier2.peerId)));
        assertTrue(routingEdgeRepository.existsById(reported(carrier1.peerId)));
    }

    /** runs one exchange with {@code carrier} and returns the bundle the client uploaded to it */
    private Path exchangeUploads(FakeCarrier carrier, Statuses expectedDownload) throws Exception {
        // the client compares millisecond mtimes to decide whether to reuse its last bundle
        Thread.sleep(20);
        int uploadsBefore = carrier.uploads.size();
        BundleExchangeCounts counts = client.doExchangeWithTransport(TransportDevice.FAKE_DEVICE,
                                                                     "localhost",
                                                                     carrier.server.getPort(),
                                                                     true);
        assertNull(counts.e());
        assertEquals(Statuses.COMPLETE, counts.uploadStatus());
        if (expectedDownload != null) assertEquals(expectedDownload, counts.downloadStatus());
        assertEquals(uploadsBefore + 1, carrier.uploads.size());
        return carrier.uploads.get(uploadsBefore);
    }

    /** the carrier syncs with the server and uploads the captured bundle (the server deletes what it processes) */
    private void carrierDelivers(FakeCarrier carrier, Path upload) throws Exception {
        Path copy = workDir.resolve("upload-" + fileCounter++);
        Files.copy(upload, copy);
        inRpcTransaction(() -> {
            serverBundleTransmission.processBundleFile(copy.toFile(), BundleSenderType.TRANSPORT, carrier.peerId);
            return null;
        });
    }

    private List<RoutingEdge> reportedEdges() {
        return StreamSupport.stream(routingEdgeRepository.findAll().spliterator(), false)
                .filter(e -> e.id.source == RoutingSource.REPORTED && e.id.clientId.equals(bcClientId))
                .toList();
    }

    private static RoutingEdgeId reported(String peerId) {
        return new RoutingEdgeId(peerId, bcClientId, RoutingSource.REPORTED);
    }

    private FakeCarrier newCarrier() throws Exception {
        var carrier = new FakeCarrier(serverSecurity, workDir.resolve("carrier-" + carriers.size()));
        carriers.add(carrier);
        return carrier;
    }

    /** runs a server call the way the gRPC executor runs every RPC: inside one transaction */
    private <T> T inRpcTransaction(Callable<T> call) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        transactionalRunner.executeInTransaction(() -> {
            try {
                result.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    /**
     * A carrier as a stock client sees one: TLS with its own key, a server-minted recency blob for
     * {@link #blobSenderId}, downloads by ID from {@link #downloads}, and every upload captured in {@link #uploads}.
     */
    static final class FakeCarrier extends BundleExchangeServiceImpl {
        final String peerId;
        final Path downloads;
        final Path uploadDir;
        final List<Path> uploads = new CopyOnWriteArrayList<>();
        final Server server;
        private final ServerSecurity serverSecurity;
        volatile String blobSenderId;

        FakeCarrier(ServerSecurity serverSecurity, Path dir) throws Exception {
            this.serverSecurity = serverSecurity;
            KeyPair keyPair = DDDTLSUtil.generateKeyPair();
            X509Certificate cert =
                    DDDTLSUtil.getSelfSignedCertificate(keyPair, DDDTLSUtil.publicKeyToName(keyPair.getPublic()));
            peerId = SecurityUtils.peerIdForTlsKey(cert.getPublicKey());
            blobSenderId = peerId;
            downloads = Files.createDirectories(dir.resolve("downloads"));
            uploadDir = Files.createDirectories(dir.resolve("uploads"));
            server = DDDNettyTLS.createGrpcServer(Executors.newCachedThreadPool(), keyPair, cert, 0, this).start();
        }

        @Override
        public void getRecencyBlob(GetRecencyBlobRequest request,
                                   StreamObserver<GetRecencyBlobResponse> responseObserver) {
            try {
                responseObserver.onNext(RecencyBlobMinter.mint(serverSecurity, blobSenderId));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(io.grpc.Status.INTERNAL.withCause(e).asException());
            }
        }

        @Override
        protected void onBundleExchangeEvent(BundleExchangeEvent event) {}

        @Override
        protected Path pathProducer(BundleExchangeName bundleExchangeName,
                                    BundleSenderType senderType,
                                    PublicKeyMap publicKeyMap) {
            if (bundleExchangeName.isDownload()) {
                Path held = downloads.resolve(bundleExchangeName.encryptedBundleId());
                return Files.isRegularFile(held) ? held : null;
            }
            // one directory per upload, so a re-upload of the same bundle ID is kept apart and keeps its name
            try {
                return Files.createDirectories(uploadDir.resolve(Integer.toString(uploads.size())))
                        .resolve(bundleExchangeName.encryptedBundleId());
            } catch (java.io.IOException e) {
                return null;
            }
        }

        @Override
        protected void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSenderType senderType, Path path) {
            uploads.add(path);
        }
    }
}
