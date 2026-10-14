package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RecencyRequest;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientBundleCountersRepository;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2-backed tests of {@link DropboxRecencyHandler} with a real repository and send store. The handler gets a mocked
 * ServerSecurity holding a real Signal identity key pair, so every grant can be checked the way
 * {@code ClientBundleTransmission.processRecencyBlob} checks a blob; the requests are signed with real TLS key pairs
 * from {@link DDDTLSUtil#generateKeyPair}.
 */
@DataJpaTest
@Import({ GrpcExecutor.class })
class DropboxRecencyHandlerTest {
    private static final String D = "dropboxClient";
    private static final String C = "stockClient";
    @TempDir
    static Path staticTempDir;
    @TempDir
    Path tempRootDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> staticTempDir.toString());
    }

    @Autowired
    DropboxRecencyGrantRepository recencyGrantRepository;
    @Autowired
    BundleMetadataRepository bundleMetadataRepository;
    @Autowired
    SentAduDetailsRepository sentAduDetailsRepository;
    @Autowired
    RegisteredAppAdapterRepository registeredAppAdapterRepository;
    @Autowired
    ClientBundleCountersRepository clientBundleCountersRepository;
    @Autowired
    ClientDownstreamAckRepository clientDownstreamAckRepository;
    @Autowired
    ObjectProvider<ServerSecurity> noServerSecurity;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ServerSecurity> serverKeys = mock(ObjectProvider.class);

    private static IdentityKeyPair serverIdentity;
    private static KeyPair tls;
    private static KeyPair otherTls;

    private AduStores aduStores;
    private StoreADUs sendStore;
    private DropboxRecencyHandler handler;
    private ServerApplicationDataManager dataManager;
    private long nextAduId = 1;

    @BeforeAll
    static void generateKeys() throws Exception {
        var keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        tls = DDDTLSUtil.generateKeyPair();
        otherTls = DDDTLSUtil.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        aduStores = new AduStores(tempRootDir);
        sendStore = aduStores.getSendADUsStorage();
        var serverSecurity = mock(ServerSecurity.class);
        when(serverSecurity.getSigningKey()).thenReturn(serverIdentity.getPrivateKey());
        when(serverSecurity.getIdentityPublicKey()).thenReturn(serverIdentity.getPublicKey());
        when(serverKeys.getIfAvailable()).thenReturn(serverSecurity);
        handler = new DropboxRecencyHandler(recencyGrantRepository, serverKeys, aduStores);
        var cfg = new BundleServerConfig();
        cfg.getApplicationDataManager().setAppDataSizeLimit(100_000_000L);
        dataManager = new ServerApplicationDataManager(aduStores,
                                                       (a, b) -> {},
                                                       sentAduDetailsRepository,
                                                       bundleMetadataRepository,
                                                       registeredAppAdapterRepository,
                                                       clientBundleCountersRepository,
                                                       clientDownstreamAckRepository,
                                                       List.of(handler),
                                                       cfg);
    }

    // ---------------------------------------------------------------- helpers

    private ADU aduOf(byte[] bytes) throws Exception {
        long id = nextAduId++;
        Path f = tempRootDir.resolve("adu-" + id);
        Files.write(f, bytes);
        return new ADU(f.toFile(), DropboxControl.RECENCY_APP_ID, id, bytes.length, D);
    }

    private ADU request(long seq) throws Exception {
        return request(D, seq, tls);
    }

    private ADU request(String signedFor, long seq, KeyPair key) throws Exception {
        return aduOf(DropboxControlCodec.encode(RecencyRequestSigner.sign(signedFor, seq, key)));
    }

    private List<RecencyGrant> queuedGrants() throws Exception {
        var grants = new ArrayList<RecencyGrant>();
        for (var adu : sendStore.getAppData(D, DropboxControl.RECENCY_APP_ID)) {
            grants.add(DropboxControlCodec.decodeRecencyGrant(Files.readAllBytes(adu.getSource().toPath())));
        }
        return grants;
    }

    private RecencyGrant single(List<RecencyGrant> grants) {
        assertEquals(1, grants.size(), () -> "expected exactly one queued grant, got " + grants);
        return grants.get(0);
    }

    private static String senderIdOf(KeyPair key) {
        return SecurityUtils.generateID(key.getPublic().getEncoded());
    }

    /** the checks {@code ClientBundleTransmission.processRecencyBlob} applies, plus the dropbox's own senderId */
    static GetRecencyBlobResponse assertValidBlobFor(RecencyGrant grant, IdentityKey serverKey, String senderId)
            throws Exception {
        var response = GetRecencyBlobResponse.parseFrom(grant.getRecencyBlobBytes());
        assertEquals(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, response.getStatus());
        var blob = response.getRecencyBlob();
        assertTrue(blob.getBlobTimestamp() <= System.currentTimeMillis() + 60_000);
        var receivedServerKey = Curve.decodePoint(response.getServerPublicKey().toByteArray(), 0);
        assertEquals(serverKey.getPublicKey(), receivedServerKey);
        assertTrue(SecurityUtils.verifySignatureRaw(blob.toByteArray(),
                                                    receivedServerKey,
                                                    response.getRecencyBlobSignature().toByteArray()));
        assertEquals(senderId, blob.getSenderId());
        return response;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void validRequestQueuesASignedGrantForTheDropboxsOwnKey() throws Exception {
        assertFalse(handler.isRegisteredFor(D));
        handler.handleUpstream(D, List.of(request(1)));

        var grant = single(queuedGrants());
        assertEquals(1, grant.getRequestSeq());
        String expectedSenderId = RecencyRequestSigner.senderIdFor(
                RecencyRequestSigner.sign(D, 1, tls).getTlsPublicKey());
        assertEquals(senderIdOf(tls), expectedSenderId, "the gRPC formula for the dropbox's TLS certificate");
        var response = assertValidBlobFor(grant, serverIdentity.getPublicKey(), expectedSenderId);
        assertEquals(0, response.getRecencyBlob().getVersion());
        assertEquals(response.getRecencyBlob().getBlobTimestamp(), grant.getIssuedAt());

        var row = recencyGrantRepository.findById(D).orElseThrow();
        assertEquals(1, row.lastRequestSeq);
        assertEquals(expectedSenderId, row.senderId);
        assertEquals(RecencyRequestSigner.sign(D, 1, tls).getTlsPublicKey(), row.tlsPublicKey);
        assertEquals(grant.getIssuedAt(), row.lastGrantMs);

        assertTrue(handler.isRegisteredFor(D));
        assertTrue(dataManager.getRegisteredAppIds(D).contains(DropboxControl.RECENCY_APP_ID));
        assertFalse(dataManager.getRegisteredAppIds(C).contains(DropboxControl.RECENCY_APP_ID));
        assertEquals(dataManager.getRegisteredAppIds(), dataManager.getRegisteredAppIds(C));
        assertFalse(Files.exists(sendStore.rootFolder.resolve(C)), "asking about a stock client creates nothing");
    }

    @Test
    void forgedSignatureQueuesNothing() throws Exception {
        // the dropbox's key with a signature made by another key
        var genuine = RecencyRequestSigner.sign(D, 1, tls);
        var forged = new RecencyRequest(1, genuine.getTlsPublicKey(), RecencyRequestSigner.sign(D, 1, otherTls)
                .getSignature());
        handler.handleUpstream(D, List.of(aduOf(DropboxControlCodec.encode(forged))));
        assertTrue(queuedGrants().isEmpty());
        assertFalse(handler.isRegisteredFor(D));
        assertFalse(recencyGrantRepository.existsById(D));
    }

    @Test
    void requestSignedForAnotherClientIsRejected() throws Exception {
        handler.handleUpstream(D, List.of(request("someOtherClient", 1, tls)));
        assertTrue(queuedGrants().isEmpty());
        assertFalse(handler.isRegisteredFor(D));
    }

    @Test
    void malformedMisroutedAndUnknownAdusAreDropped() throws Exception {
        var plan = new RetentionPlan(1, 1, 1L, List.of(), List.of(), null);
        handler.handleUpstream(D, List.of(aduOf("not json".getBytes(StandardCharsets.UTF_8)),
                                          aduOf("{\"type\":\"telemetry\",\"v\":1}".getBytes(StandardCharsets.UTF_8)),
                                          aduOf(DropboxControlCodec.encode(plan)),
                                          aduOf(DropboxControlCodec.encode(new InventoryReport(1, 0, 10, 1, null,
                                                                                               List.of(), List.of(),
                                                                                               List.of())))));
        assertTrue(queuedGrants().isEmpty());
        assertFalse(handler.isRegisteredFor(D));
    }

    @Test
    void reSentRequestWhoseGrantIsStillQueuedIsIgnored() throws Exception {
        handler.handleUpstream(D, List.of(request(1)));
        byte[] queued = sendStore.getADU(D, DropboxControl.RECENCY_APP_ID, 1L);

        handler.handleUpstream(D, List.of(request(1)));
        assertEquals(1, sendStore.getLastADUIdAdded(D, DropboxControl.RECENCY_APP_ID), "no new ADU, no new bundle");
        assertArrayEquals(queued, sendStore.getADU(D, DropboxControl.RECENCY_APP_ID, 1L));

        // once that grant was delivered and acknowledged, the same request is answered again
        sendStore.deleteAllFilesUpTo(D, DropboxControl.RECENCY_APP_ID, 1);
        handler.handleUpstream(D, List.of(request(1)));
        assertEquals(1, single(queuedGrants()).getRequestSeq());
        assertEquals(2, sendStore.getLastADUIdAdded(D, DropboxControl.RECENCY_APP_ID));
    }

    /** The queue is written at once, the row only on commit: the duplicate check must not depend on the row. */
    @Test
    void duplicateCheckReadsTheQueueNotTheRow() throws Exception {
        handler.handleUpstream(D, List.of(request(4)));
        recencyGrantRepository.deleteById(D); // as if the RPC that queued grant 4 rolled back its database work
        assertTrue(handler.isRegisteredFor(D), "a queued grant still reaches the dropbox");

        handler.handleUpstream(D, List.of(request(4)));
        assertEquals(1, sendStore.getLastADUIdAdded(D, DropboxControl.RECENCY_APP_ID));
    }

    @Test
    void newerRequestReplacesTheQueuedGrant() throws Exception {
        handler.handleUpstream(D, List.of(request(1)));
        handler.handleUpstream(D, List.of(request(2)));
        var grant = single(queuedGrants());
        assertEquals(2, grant.getRequestSeq());
        assertValidBlobFor(grant, serverIdentity.getPublicKey(), senderIdOf(tls));
        assertEquals(2, recencyGrantRepository.findById(D).orElseThrow().lastRequestSeq);
    }

    @Test
    void severalRequestsInOneDeliveryUseTheNewestValidOne() throws Exception {
        var forgedNewest = new RecencyRequest(9, RecencyRequestSigner.sign(D, 9, tls).getTlsPublicKey(),
                                              RecencyRequestSigner.sign(D, 8, tls).getSignature());
        handler.handleUpstream(D, List.of(request(1), request(3), request(2),
                                          aduOf(DropboxControlCodec.encode(forgedNewest))));
        assertEquals(3, single(queuedGrants()).getRequestSeq());
    }

    @Test
    void aRestartedNumberingIsAnswered() throws Exception {
        handler.handleUpstream(D, List.of(request(7)));
        handler.handleUpstream(D, List.of(request(1)));
        assertEquals(1, single(queuedGrants()).getRequestSeq());
    }

    @Test
    void newTlsKeyGetsABlobForThatKey() throws Exception {
        handler.handleUpstream(D, List.of(request(1)));
        // same requestSeq but another key: not a re-sent copy
        handler.handleUpstream(D, List.of(request(D, 1, otherTls)));
        var grant = single(queuedGrants());
        assertValidBlobFor(grant, serverIdentity.getPublicKey(), senderIdOf(otherTls));
        var row = recencyGrantRepository.findById(D).orElseThrow();
        assertEquals(senderIdOf(otherTls), row.senderId);
        assertNotEquals(senderIdOf(tls), row.senderId);
    }

    @Test
    void withoutServerKeysRecencyAdusAreLeftForTheLiveServer() throws Exception {
        var cliHandler = new DropboxRecencyHandler(recencyGrantRepository, noServerSecurity, aduStores);
        assertThrows(IOException.class, () -> cliHandler.handleUpstream(D, List.of(request(1))));
        assertFalse(cliHandler.isRegisteredFor(D));
        assertTrue(queuedGrants().isEmpty());
    }
}
