package net.discdd.bundletransport;

import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.widget.Toast;

import androidx.annotation.NonNull;

import net.discdd.wifiaware.WifiAwareHelper;

import java.util.function.Consumer;
import java.util.logging.Logger;

public class TransportWifiAwarePublisher extends DiscoverySessionCallback {
    private static final Logger logger = Logger.getLogger(TransportWifiAwarePublisher.class.getName());
    private final WifiAwareHelper wifiAwareHelper;
    public String serviceName;
    public byte[] description;
    public final int port;
    private final Consumer<WifiAwareHelper.PeerMessage> subscriber;
    private PublishDiscoverySession session;

    public TransportWifiAwarePublisher(WifiAwareHelper wifiAwareHelper, String serviceName, byte[] description, int port, Consumer<WifiAwareHelper.PeerMessage> subscriber) {
        this.wifiAwareHelper = wifiAwareHelper;
        this.serviceName = serviceName;
        this.description = description;
        this.port = port;
        this.subscriber = subscriber;
    }

    public void createSessionForClient(PeerHandle peerHandle, byte[] message) {
        // This we want to open a channel to the port from the client. We don't care about the network info that comes
        // back since we are waiting for the client to connect to us.
        wifiAwareHelper.getConnectivityManager(session, peerHandle, port);
        session.sendMessage(peerHandle, 0xbadd00d, message);
    }

    @Override
    public void onPublishStarted(@NonNull PublishDiscoverySession session) {
        this.session = session;
        Toast.makeText(wifiAwareHelper.context, "Publish started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
        if (session == null) {
            logger.severe("Received a message but session is null. onMessageReceived was called before onPublishStarted?");
            return;
        }
        Toast.makeText(wifiAwareHelper.context, "Message received: " + new String(message), Toast.LENGTH_SHORT).show();
        subscriber.accept(new WifiAwareHelper.PeerMessage(
                peerHandle,
                message
        ));
    }

    public void unpublish() {
        session.close();
    }

    public void updateConfig(@NonNull String serviceName, byte[] description) {
        this.serviceName = serviceName;
        this.description = description;
        var publishConfigBuilder = new PublishConfig.Builder();
        publishConfigBuilder.setServiceName(serviceName);
        if (description != null) publishConfigBuilder.setServiceSpecificInfo(description);
        session.updatePublish(publishConfigBuilder.build());
    }
}