package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;

import java.io.IOException;
import java.util.List;

/**
 * An app ID that the server consumes in-process instead of forwarding to a gRPC service adapter.
 * <p>
 * Before this SPI, an upstream ADU for an app ID with no registered adapter was stored in the receive folder and
 * never consumed, with a SEVERE "No service adapter" log (Research R8 §2.1). {@link BundleServerAduDeliverer} now
 * looks up a handler for the app ID first and, if there is one, calls {@link #handleUpstream} synchronously.
 * {@link ServerApplicationDataManager} asks {@link #isRegisteredFor} when it builds a client's app ID list, so a
 * handler can expose its app ID to some clients only.
 * <p>
 * Spring wiring constraint: implementations are injected into {@link BundleServerAduDeliverer}, which
 * {@link ServerApplicationDataManager} depends on, so an implementation must not depend on
 * {@link ServerApplicationDataManager} or {@code ServerBundleTransmission} (that would be a constructor cycle).
 * Depend on repositories, {@link AduStores} and {@code ObjectProvider<ServerSecurity>} instead; the
 * {@code ServerSecurity} bean is null in CLI mode.
 */
public interface InternalAppHandler {
    /** the app ID this handler owns */
    String appId();

    /**
     * Whether {@link #appId()} belongs in {@code clientId}'s registered app ID list ({@code app_ids.txt}) and in the
     * scan for downstream ADUs to send it. A client only uploads ADUs for app IDs in that list after its first
     * downstream bundle (Research R8 §2.3).
     */
    boolean isRegisteredFor(String clientId);

    /**
     * Consume upstream ADUs from {@code clientId}, ordered by ADU ID. This runs synchronously inside bundle
     * processing, so on a live server it shares the upload RPC's transaction ({@code GrpcExecutor}); a repository
     * exception therefore rolls back that RPC's database work even if it is caught. When this method returns normally
     * the caller deletes the ADUs from the receive folder; when it throws they stay and are offered again with the
     * next delivery. So an implementation should treat malformed input as consumed (log and return) and throw only
     * for transient failures.
     */
    void handleUpstream(String clientId, List<ADU> adus) throws IOException;

    /**
     * Called just before the server generates (or reuses) a downstream bundle for {@code clientId}, so a handler can
     * refresh its queued reply from facts that arrived after the upstream ADU was handled. The default does nothing.
     */
    default void beforeBundleGeneration(String clientId) {}
}
