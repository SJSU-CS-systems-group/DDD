package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The in-process {@link InternalAppHandler} path of {@link BundleServerAduDeliverer}: ADUs are deleted only after the
 * handler returns normally, kept and offered again when it throws, and ADUs left in the receive store by a crash or
 * restart are handed to the handler by {@code init()}.
 */
class BundleServerAduDelivererInternalHandlerTest {
    private static final String APP = "net.discdd.test.internal";
    private static final String CLIENT = "client-1";

    @TempDir
    Path root;

    /** Fails the first {@code failures} calls, then records what it was given. */
    private static final class FlakyHandler implements InternalAppHandler {
        int failures;
        final List<List<String>> delivered = new ArrayList<>();

        @Override
        public String appId() {return APP;}

        @Override
        public boolean isRegisteredFor(String clientId) {return true;}

        @Override
        public void handleUpstream(String clientId, List<ADU> adus) throws IOException {
            if (failures-- > 0) throw new IOException("transient");
            var contents = new ArrayList<String>();
            for (var adu : adus) contents.add(Files.readString(adu.getSource().toPath()));
            delivered.add(contents);
        }
    }

    private BundleServerAduDeliverer deliverer(AduStores stores, InternalAppHandler handler) {
        var repo = mock(RegisteredAppAdapterRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        return new BundleServerAduDeliverer(stores, repo, Duration.ofHours(1), Duration.ZERO, null, List.of(handler));
    }

    @Test
    void adusStayWhenTheHandlerThrowsAndAreDeletedAfterItSucceeds() throws IOException {
        var stores = new AduStores(root);
        var receive = stores.getReceiveADUsStorage();
        receive.addADU(CLIENT, APP, "a".getBytes(), -1);
        receive.addADU(CLIENT, APP, "b".getBytes(), -1);
        var handler = new FlakyHandler();
        handler.failures = 1;
        var deliverer = deliverer(stores, handler);

        deliverer.onAduDelivered(CLIENT, Set.of(APP));
        assertEquals(List.of(), handler.delivered);
        assertEquals(2, receive.getAppData(CLIENT, APP).size(), "kept after a failure");

        deliverer.onAduDelivered(CLIENT, Set.of(APP));
        assertEquals(List.of(List.of("a", "b")), handler.delivered);
        assertEquals(0, receive.getAppData(CLIENT, APP).size(), "deleted after success");

        deliverer.onAduDelivered(CLIENT, Set.of(APP)); // nothing pending: the handler is not called
        assertEquals(1, handler.delivered.size());
    }

    @Test
    void initDeliversAdusLeftInTheReceiveStore() throws IOException {
        var stores = new AduStores(root);
        stores.getReceiveADUsStorage().addADU(CLIENT, APP, "left over".getBytes(), -1);
        var handler = new FlakyHandler();

        deliverer(stores, handler).init();

        assertEquals(List.of(List.of("left over")), handler.delivered);
        assertEquals(0, stores.getReceiveADUsStorage().getAppData(CLIENT, APP).size());
    }
}
