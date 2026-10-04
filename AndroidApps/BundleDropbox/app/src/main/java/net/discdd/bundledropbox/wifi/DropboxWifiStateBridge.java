package net.discdd.bundledropbox.wifi;

import net.discdd.bundledropbox.DropboxWifiSnapshot;
import net.discdd.bundledropbox.DropboxWifiState;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mirrors {@link WifiRadioState} into the UI's Kotlin {@code DropboxWifiState} StateFlow (the dropbox's
 * replacement for the transport's {@code DDDWifiServiceEvents} + polling of {@code networkInfo}, R5 §5.5).
 *
 * <p>Kept as a separate adapter so the radio code itself stays Kotlin-free; {@code DropboxService} registers
 * it with {@code driver.addStateListener(new DropboxWifiStateBridge())}.
 */
public final class DropboxWifiStateBridge implements WifiRadioStateListener {
    @Override
    public void onWifiRadioStateChanged(WifiRadioState s) {
        WifiRadioState.GroupInfo g = s.group();
        // Only our own group belongs in the "hosted group" fields; a carrier's group shows up as currentCarrier.
        boolean hosting = g != null && g.isGroupOwner();
        List<String> carriers = s.discoveredCarriers()
                .stream()
                .map(DiscoveredCarrier::carrierId)
                .collect(Collectors.toList());
        DiscoveredCarrier current = s.currentCarrier();
        DropboxWifiState.update(new DropboxWifiSnapshot(hosting ? g.networkName() : null,
                                                        hosting ? g.groupOwnerAddress() : null,
                                                        hosting ? g.clientCount() : 0,
                                                        hosting ? g.clientNames() : List.of(),
                                                        current == null ? null : current.carrierId(),
                                                        carriers,
                                                        s.p2pEnabled()));
    }
}
