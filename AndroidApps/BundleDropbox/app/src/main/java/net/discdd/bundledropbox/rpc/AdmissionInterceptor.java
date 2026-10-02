package net.discdd.bundledropbox.rpc;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.discdd.grpc.BundleExchangeServiceGrpc;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gatekeeper in front of every client RPC: asks the {@link ClientSessionTracker} for admission keyed by
 * the remote IP that {@link RemoteAddrInterceptor} put in the context, refuses with {@code UNAVAILABLE}
 * when the cycle is draining, and reports the end of each admitted RPC back to the tracker.
 *
 * <p>Admission lives in an interceptor rather than in each service method because it then covers all five
 * RPCs uniformly (including {@code getRecencyBlob}, which emits no bundle-core event), and because the
 * listener's {@code onComplete}/{@code onCancel} are guaranteed to fire exactly once per call, which gives
 * balanced in-flight accounting (unlike bundle-core's {@code BundleExchangeEvent}s, Research/R1 §3.2).
 *
 * <p>Refusing with {@code UNAVAILABLE} is NOT free for the stock client: {@code doExchangeWithTransport}'s
 * outer catch swallows the error, but {@code BundleClientService.exchangeWith} then stamps
 * {@code lastExchange} for our advertised transportId anyway, and the client skips that id until a recency
 * blob newer than the stamp is advertised (R3 §7). In practice a refused client is locked out until the
 * advertised id rotates after the next successful carrier visit (or for good with rotation off, until a
 * newer blob arrives). The cycle therefore minimizes refusals: it withdraws the DNS-SD record as soon as a
 * HOST drain begins ({@code RadioDriver.withdrawHostAdvertisement}), and admitted clients are never reaped
 * while draining ({@link ClientSessionTracker}). A draining dropbox never refuses an already admitted
 * client, so an exchange is only cut in half by the drain cap.
 *
 * <p>Stateless apart from the tracker; thread-safe.
 */
public final class AdmissionInterceptor implements ServerInterceptor {
    private static final Logger logger = Logger.getLogger(AdmissionInterceptor.class.getName());

    /** uploadBundle is the last RPC of a stock client exchange; its end ends the session. */
    private static final String UPLOAD_METHOD =
            BundleExchangeServiceGrpc.getUploadBundleMethod().getFullMethodName();

    private final ClientSessionTracker tracker;
    private final RpcEventListener listener;

    /**
     * @param tracker  session tracker bound to the cycle's {@code ActivityGate}
     * @param listener receives {@link RpcEventListener#onClientRejected}
     */
    public AdmissionInterceptor(ClientSessionTracker tracker, RpcEventListener listener) {
        this.tracker = tracker;
        this.listener = listener == null ? RpcEventListener.NO_OP : listener;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String ip = RemoteAddrInterceptor.currentIp();
        String method = call.getMethodDescriptor().getFullMethodName();
        ClientSessionTracker.RpcTicket ticket = tracker.beginRpc(ip);
        if (ticket == null) {
            logger.log(Level.INFO, "Refusing " + method + " from " + ip + ": admissions closed");
            listener.onClientRejected(ip, method);
            call.close(Status.UNAVAILABLE.withDescription("DDD dropbox is switching modes; try again later"),
                       new Metadata());
            return new ServerCall.Listener<ReqT>() {};
        }
        final boolean endsSession = UPLOAD_METHOD.equals(method);
        final ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(call, headers);
        } catch (RuntimeException e) {
            ticket.end(false);
            throw e;
        }
        return new SimpleForwardingServerCallListener<ReqT>(delegate) {
            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    ticket.end(endsSession);
                }
            }

            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } finally {
                    // A cancelled upload (client deadline, link dropped) also ends that client's exchange.
                    ticket.end(endsSession);
                }
            }
        };
    }
}
