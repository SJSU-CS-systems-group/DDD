package net.discdd.bundledropbox.visit;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.BundleOwnershipPSI;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIElement;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurityKey;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * A stand-in for an unmodified BundleTransport: its gRPC service is adapted line by line from
 * {@code AndroidApps/BundleTransport/.../RpcServer.java} (same base {@link BundleExchangeServiceImpl} upload
 * observer, same zero-element-allowed {@code psiExchange}, same single-use PSI sessions, same 5 MiB inbound
 * limit) and it serves real TLS on localhost with a {@code GrpcSecurityKey(..., SERVER)} self-signed cert, so
 * the production {@link CarrierChannelFactory#tls} path is exercised end to end.
 *
 * <p>Layout mirrors {@code TransportPaths}: {@code client/} holds client-bound bundles (served via PSI),
 * {@code server/} receives uploads.
 */
final class FakeCarrier implements AutoCloseable {
    private static final int MAX_PSI_ELEMENTS_PER_REQUEST = 10;

    final Path toClient;
    final Path toServer;
    final int port;
    volatile GetRecencyBlobResponse recency = GetRecencyBlobResponse.getDefaultInstance();
    /** If set, {@code psiDownloadBundle} signals {@link #downloadBlocked} and waits for this latch. */
    volatile CountDownLatch downloadGate;
    final CountDownLatch downloadBlocked = new CountDownLatch(1);
    /** Called with the index after each successful PSI download (before the session is removed). */
    volatile IntConsumer afterDownload = i -> {};
    final AtomicInteger psiExchanges = new AtomicInteger();
    final AtomicInteger psiDownloads = new AtomicInteger();
    final AtomicInteger uploads = new AtomicInteger();
    /** Bundle ids of completed uploads, in completion order. */
    final List<String> uploadOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Map<String, List<String>> psiSessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Server server;

    FakeCarrier(Path root) throws Exception {
        toClient = Files.createDirectories(root.resolve("client"));
        toServer = Files.createDirectories(root.resolve("server"));
        GrpcSecurityKey key = new GrpcSecurityKey(root.resolve("GrpcSecurity"), SecurityUtils.SERVER);
        var credentials = TlsServerCredentials.newBuilder()
                .keyManager(DDDTLSUtil.getKeyManagerFactory(key.grpcKeyPair, key.grpcCert).getKeyManagers())
                .build();
        server = OkHttpServerBuilder.forPort(0, credentials)
                .maxInboundMessageSize(5 * 1024 * 1024)
                .addService(new Service())
                .executor(executor)
                .build()
                .start();
        port = server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
        CountDownLatch gate = downloadGate;
        if (gate != null) gate.countDown();
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    private List<String> listBundleFiles() {
        File[] files = toClient.toFile().listFiles();
        if (files == null) return List.of();
        return Arrays.stream(files)
                .filter(f -> f.isFile() && !f.getName().equals("recencyBlob.bin"))
                .map(File::getName)
                .collect(Collectors.toList());
    }

    private final class Service extends BundleExchangeServiceImpl {
        @Override
        protected void onBundleExchangeEvent(BundleExchangeEvent event) {}

        @Override
        protected Path pathProducer(BundleExchangeName name, BundleSenderType senderType, PublicKeyMap map) {
            if (name.isDownload()) {
                Path p = toClient.resolve(name.encryptedBundleId());
                return Files.exists(p) ? p : null;
            }
            return toServer.resolve(name.encryptedBundleId());
        }

        @Override
        protected void bundleCompletion(BundleExchangeName name, BundleSenderType senderType, Path path) {
            uploadOrder.add(name.encryptedBundleId());
            uploads.incrementAndGet();
        }

        @Override
        public void getRecencyBlob(GetRecencyBlobRequest request, StreamObserver<GetRecencyBlobResponse> response) {
            response.onNext(recency);
            response.onCompleted();
        }

        @Override
        public void psiExchange(PSIRequest request, StreamObserver<PSIResponse> responseObserver) {
            psiExchanges.incrementAndGet();
            if (request.getClientBlindedValuesCount() > MAX_PSI_ELEMENTS_PER_REQUEST) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.asException());
                return;
            }
            var psi = new BundleOwnershipPSI();
            BigInteger secret = psi.generateSecret();
            List<String> names = listBundleFiles();
            List<BigInteger> clientBlinded = new ArrayList<>();
            for (PSIElement e : request.getClientBlindedValuesList()) {
                clientBlinded.add(new BigInteger(1, e.getValue().toByteArray()));
            }
            var tr = psi.transportProcess(clientBlinded, names, secret);
            String sessionId = UUID.randomUUID().toString();
            psiSessions.put(sessionId, names);
            var b = PSIResponse.newBuilder().setSessionId(sessionId);
            for (BigInteger v : tr.doublyBlindedClientValues()) {
                b.addDoublyBlindedClientValues(PSIElement.newBuilder().setValue(ByteString.copyFrom(v.toByteArray())));
            }
            for (BigInteger v : tr.transportBlindedValues()) {
                b.addTransportBlindedValues(PSIElement.newBuilder().setValue(ByteString.copyFrom(v.toByteArray())));
            }
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        }

        @Override
        public void psiDownloadBundle(PSIDownloadRequest request,
                                      StreamObserver<BundleDownloadResponse> responseObserver) {
            try {
                CountDownLatch gate = downloadGate;
                if (gate != null) {
                    downloadBlocked.countDown();
                    gate.await();
                }
                List<String> names = psiSessions.get(request.getSessionId());
                if (names == null) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asException());
                    return;
                }
                int index = request.getTransportIndex();
                if (index < 0 || index >= names.size()) {
                    responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.asException());
                    return;
                }
                Path file = toClient.resolve(names.get(index));
                if (!Files.exists(file)) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asException());
                    return;
                }
                try (InputStream is = Files.newInputStream(file)) {
                    // Same 4 MiB chunking as the transport (BundleExchangeServiceImpl.DOWNLOAD_BUFFER_SIZE).
                    transferToStream(is,
                                     bytes -> responseObserver.onNext(BundleDownloadResponse.newBuilder()
                                                                              .setChunk(BundleChunk.newBuilder()
                                                                                                .setChunk(bytes))
                                                                              .build()));
                }
                psiDownloads.incrementAndGet();
                afterDownload.accept(index);
                responseObserver.onCompleted();
                psiSessions.remove(request.getSessionId()); // single-use session, as RpcServer.java:199
            } catch (Exception e) {
                responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(String.valueOf(e)).asException());
            }
        }
    }
}
