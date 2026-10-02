package net.discdd.bundledropbox.rpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Captures the remote IP of every incoming client RPC into the gRPC {@link Context} under
 * {@link #REMOTE_IP}, so later interceptors ({@link AdmissionInterceptor}) and the service
 * ({@link DropboxExchangeService}) can key work by client.
 *
 * <p>Why the IP: the stock BundleClient's exchange is four independent RPCs
 * ({@code getRecencyBlob}, {@code psiExchange}, {@code psiDownloadBundle}, {@code uploadBundle}) with no
 * exchange/session id on the wire (Research/R1 §7 "Busy detection"). On the dropbox's Wi-Fi Direct group
 * every client gets its own DHCP address from the group owner, so the remote IP is the only stable,
 * unmodified-client-compatible session key (03 Architecture, "Client-side keying").
 *
 * <p>Must run <em>before</em> {@link AdmissionInterceptor}. With
 * {@code ServerInterceptors.intercept(service, admission, remoteAddr)} the last interceptor listed runs
 * first, which is how {@link DropboxRpcServer} wires them.
 *
 * <p>Stateless and thread-safe.
 */
public final class RemoteAddrInterceptor implements ServerInterceptor {

    /** Value used when the transport did not expose a remote address (e.g. in-process tests). */
    public static final String UNKNOWN_IP = "unknown";

    /** Context key holding the caller's IP as a string (never {@code null} inside an intercepted call). */
    public static final Context.Key<String> REMOTE_IP = Context.key("dropbox-remote-ip");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String ip = ipOf(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
        // Contexts.interceptCall attaches the context for startCall AND for every listener callback,
        // so client-streaming uploadBundle callbacks (onNext per chunk) also see REMOTE_IP.
        Context ctx = Context.current().withValue(REMOTE_IP, ip);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    /**
     * @return the IP of the caller of the current gRPC call, or {@link #UNKNOWN_IP} if the call was not
     *         intercepted (only happens in unit tests that invoke the service directly)
     */
    public static String currentIp() {
        String ip = REMOTE_IP.get();
        return ip == null ? UNKNOWN_IP : ip;
    }

    /**
     * Reduces a socket address to the session key. The port is deliberately dropped: the stock client
     * opens a new TCP connection per exchange (and OkHttp may reconnect between RPCs), so only the host
     * part is stable across the RPCs of one exchange.
     *
     * @param addr the remote socket address from the transport attributes, possibly {@code null}
     * @return host IP string, or {@link #UNKNOWN_IP}
     */
    static String ipOf(SocketAddress addr) {
        if (addr instanceof InetSocketAddress) {
            InetSocketAddress isa = (InetSocketAddress) addr;
            if (isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
            return isa.getHostString();
        }
        return addr == null ? UNKNOWN_IP : addr.toString();
    }
}
