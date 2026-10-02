package net.discdd.bundledropbox.visit;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.DDDX509ExtendedTrustManager;
import net.discdd.tls.GrpcSecurityKey;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.InetAddress;
import java.security.SecureRandom;

/**
 * Creates the gRPC channel the dropbox uses to talk to a carrier (an unmodified BundleTransport) during a
 * VISIT.
 *
 * <p>It is an interface so {@link TransportVisitClient} stays pure Java and testable: production code uses
 * {@link #tls(GrpcSecurityKey)}, while tests may substitute a plaintext or differently configured channel.
 * Each call must return a NEW channel; the visit client owns it and shuts it down when the visit ends.
 */
public interface CarrierChannelFactory {

    /**
     * Maximum inbound gRPC message size for carrier channels: 8 MiB.
     *
     * <p>The transport streams downloads through a 4 MiB buffer ({@code BundleExchangeServiceImpl.DOWNLOAD_BUFFER_SIZE}),
     * so a chunk may be up to 4 MiB of data, and a full chunk plus protobuf framing exceeds OkHttp's default 4 MiB
     * limit (Research/R2 §4.1). In practice {@code transferToStream} reads through {@code Channels.newChannel},
     * which yields much smaller chunks (8 KiB on the JDK), so the stock client usually gets away without raising
     * it; the dropbox raises it anyway so the worst case cannot fail a visit.
     */
    int MAX_INBOUND_MESSAGE_BYTES = 8 * 1024 * 1024;

    /**
     * @param host carrier address (the Wi-Fi Direct group owner address, normally 192.168.49.1)
     * @param port carrier gRPC port ({@link TransportVisitClient#TRANSPORT_PORT})
     * @return a new, unstarted-or-idle channel owned by the caller
     * @throws Exception if TLS material cannot be set up
     */
    ManagedChannel create(InetAddress host, int port) throws Exception;

    /**
     * The production factory: TLS exactly as the stock client builds it in
     * {@code ClientBundleTransmission.doExchangeWithTransport} (Research/R2 §2 step 0), so the carrier cannot
     * tell the dropbox from a client:
     * <ul>
     *   <li>a fresh {@link DDDX509ExtendedTrustManager}{@code (true)} per channel: it accepts the carrier's
     *       self-signed EC certificate only if {@code CN == publicKeyToName(pubkey)} and pins the first cert it
     *       sees for the life of the channel (one visit = one carrier);</li>
     *   <li>hostname verification disabled (the carrier cert has no host name, only the key-derived CN);</li>
     *   <li>the dropbox's own {@link GrpcSecurityKey} offered as the client certificate, as the stock client
     *       offers its client key. The transport never requests or inspects client certs (TLS server built
     *       without {@code clientAuth}, Research/R2 §5), so this is parity, not authentication;</li>
     *   <li>{@link #MAX_INBOUND_MESSAGE_BYTES} for 4 MiB download chunks.</li>
     * </ul>
     *
     * <p>FUTURE: pin the carrier's TLS key to the DNS-SD {@code transportId} it advertised, so a visit cannot be
     * hijacked by a look-alike group owner.
     *
     * @param identityOrNull the dropbox TLS identity ({@code new GrpcSecurityKey(paths.grpcSecurity, SERVER)},
     *                       the same key it uses when hosting); {@code null} to present no client certificate
     * @return a factory creating one TLS channel per call
     */
    static CarrierChannelFactory tls(GrpcSecurityKey identityOrNull) {
        return (host, port) -> {
            KeyManager[] keyManagers = identityOrNull == null ?
                                       null :
                                       DDDTLSUtil.getKeyManagerFactory(identityOrNull.grpcKeyPair,
                                                                       identityOrNull.grpcCert).getKeyManagers();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers,
                            new TrustManager[] { new DDDX509ExtendedTrustManager(true) },
                            new SecureRandom());
            // Use the literal IP: the stock client passes an address string, and DNS is meaningless offline.
            return OkHttpChannelBuilder.forAddress(host.getHostAddress(), port)
                    .hostnameVerifier((hostname, session) -> true)
                    .useTransportSecurity()
                    .sslSocketFactory(sslContext.getSocketFactory())
                    .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                    .build();
        };
    }
}
