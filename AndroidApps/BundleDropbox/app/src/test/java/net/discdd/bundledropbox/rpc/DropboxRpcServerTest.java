package net.discdd.bundledropbox.rpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundlesecurity.BundleOwnershipPSI;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIElement;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.DDDX509ExtendedTrustManager;
import net.discdd.tls.GrpcSecurityKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end over real TLS on loopback, using the same channel setup as the stock client
 * ({@code ClientBundleTransmission.doExchangeWithTransport}): OkHttp, {@link DDDX509ExtendedTrustManager},
 * client cert presented but not required. Replays the stock client RPC order.
 */
public class DropboxRpcServerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RpcTestFakes.FakeRelayStore store;
    private RpcTestFakes.FakeRecencyBlobStore blobs;
    private RpcTestFakes.FakeDeliveryLog deliveryLog;
    private RpcTestFakes.RecordingListener listener;
    private ActivityGate gate;
    private final AtomicInteger drained = new AtomicInteger();
    private DropboxRpcServer server;
    private GrpcSecurityKey clientKey;
    private final List<ManagedChannel> channels = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        store = new RpcTestFakes.FakeRelayStore(tmp.newFolder("store").toPath());
        blobs = new RpcTestFakes.FakeRecencyBlobStore();
        blobs.blob = GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(RecencyBlob.newBuilder().setBlobTimestamp(1234).setSenderId("carrier"))
                .build()
                .toByteArray();
        deliveryLog = new RpcTestFakes.FakeDeliveryLog();
        listener = new RpcTestFakes.RecordingListener();
        gate = new ActivityGate();
        gate.setDrainListener(drained::incrementAndGet);
        gate.openAdmissions();
        GrpcSecurityKey serverKey = new GrpcSecurityKey(tmp.newFolder("srv").toPath(), SecurityUtils.SERVER);
        clientKey = new GrpcSecurityKey(tmp.newFolder("cli").toPath(), SecurityUtils.CLIENT);
        server = new DropboxRpcServer(serverKey.grpcKeyPair, serverKey.grpcCert, 0, store, blobs, deliveryLog, gate,
                                      System::currentTimeMillis, 10_000, listener);
        server.start();
    }

    @After
    public void tearDown() {
        for (ManagedChannel c : channels) c.shutdownNow();
        server.stop();
    }

    private BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blocking(ManagedChannel ch) {
        return BundleExchangeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    /** Same TLS setup as the stock client. */
    private ManagedChannel channel() throws Exception {
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(DDDTLSUtil.getKeyManagerFactory(clientKey.grpcKeyPair, clientKey.grpcCert).getKeyManagers(),
                 new TrustManager[] { new DDDX509ExtendedTrustManager(true) }, new SecureRandom());
        ManagedChannel ch = OkHttpChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .hostnameVerifier((h, s) -> true)
                .useTransportSecurity()
                .sslSocketFactory(ssl.getSocketFactory())
                .build();
        channels.add(ch);
        return ch;
    }

    private static List<BigInteger> ints(List<PSIElement> els) {
        List<BigInteger> out = new ArrayList<>();
        for (PSIElement e : els) out.add(new BigInteger(1, e.getValue().toByteArray()));
        return out;
    }

    private BundleUploadResponse upload(ManagedChannel ch, String id, byte[] data) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<BundleUploadResponse> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<BundleUploadRequest> req = BundleExchangeServiceGrpc.newStub(ch)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .uploadBundle(new StreamObserver<BundleUploadResponse>() {
                    @Override
                    public void onNext(BundleUploadResponse value) {
                        result.set(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });
        // Stock client order: bundleId, chunks, senderType LAST, then completed (Research/R1 §3.2).
        req.onNext(BundleUploadRequest.newBuilder()
                           .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(id))
                           .build());
        req.onNext(BundleUploadRequest.newBuilder()
                           .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(data)))
                           .build());
        req.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
        req.onCompleted();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError("upload failed", error.get());
        return result.get();
    }

    @Test
    public void stockClientExchangeOrderWorksAndEndsSessionAfterUpload() throws Exception {
        store.putDownstream("reply-bundle", "server says hi".getBytes(StandardCharsets.UTF_8));
        ManagedChannel ch = channel();

        // 1. recency blob (served verbatim)
        GetRecencyBlobResponse blob = blocking(ch).getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
        assertEquals(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS, blob.getStatus());
        assertEquals(1234, blob.getRecencyBlob().getBlobTimestamp());
        assertEquals(1, server.activeSessions());

        // Dwell timer expires mid-exchange: the admitted client must still finish.
        gate.closeAdmissions();

        // 2. PSI
        var psi = new BundleOwnershipPSI();
        BigInteger a = psi.generateSecret();
        var req = PSIRequest.newBuilder();
        for (BigInteger v : psi.clientBlindBundleIds(List.of("reply-bundle"), a)) {
            req.addClientBlindedValues(PSIElement.newBuilder().setValue(ByteString.copyFrom(v.toByteArray())));
        }
        PSIResponse pr = blocking(ch).psiExchange(req.build());
        var matches = psi.clientFindMatches(ints(pr.getDoublyBlindedClientValuesList()),
                                            ints(pr.getTransportBlindedValuesList()), a);
        assertEquals(1, matches.size());

        // 3. PSI download
        Iterator<BundleDownloadResponse> it = blocking(ch).psiDownloadBundle(PSIDownloadRequest.newBuilder()
                .setSessionId(pr.getSessionId())
                .setTransportIndex(matches.get(0).transportIndex())
                .build());
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        while (it.hasNext()) got.write(it.next().getChunk().getChunk().toByteArray());
        assertEquals("server says hi", got.toString(StandardCharsets.UTF_8));

        // 4. upload (last RPC: ends the session, which drains the closed gate)
        BundleUploadResponse up = upload(ch, "client-bundle-1", "to the server".getBytes(StandardCharsets.UTF_8));
        assertEquals(net.discdd.grpc.Status.SUCCESS, up.getStatus());
        assertTrue(store.contains(Direction.UPSTREAM, "client-bundle-1"));
        assertEquals(List.of("reply-bundle"), deliveryLog.served);

        waitFor(() -> server.activeSessions() == 0 && drained.get() == 1);
        assertEquals(0, gate.activeCount());

        // A new exchange from the same IP is now a new session and is refused while closed.
        try {
            blocking(ch).getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
            fail("expected UNAVAILABLE");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
        assertFalse(listener.rejected.isEmpty());
    }

    @Test
    public void refusesNewClientsWhileGateClosed() throws Exception {
        gate.closeAdmissions();
        try {
            blocking(channel()).getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
            fail("expected UNAVAILABLE");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        }
        assertEquals(0, gate.activeCount());
    }

    @Test
    public void stopReleasesSessionsAndServerCanRestart() throws Exception {
        blocking(channel()).getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
        assertEquals(1, gate.activeCount());
        server.stop();
        assertFalse(server.isRunning());
        assertEquals("stop() releases session tokens so the cycle can switch", 0, gate.activeCount());
        server.stop(); // idempotent

        server.start();
        assertTrue(server.isRunning());
        GetRecencyBlobResponse blob = blocking(channel()).getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
        assertEquals(1234, blob.getRecencyBlob().getBlobTimestamp());
    }

    private static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition not reached");
            Thread.sleep(10);
        }
    }
}
