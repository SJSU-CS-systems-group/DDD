package net.discdd.bundleclient.utils;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.grpc.GetRecencyBlobResponse;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;

/**
 * Used to track in memory recently seen transports.
 * All times are in milliseconds since epoch.
 */
@Entity(tableName = "RecentTransports")
public class RecentTransport {
    @PrimaryKey(autoGenerate = false) @Nonnull
    private String transportId;
    private long lastExchange;
    private long lastSeen;
    private long recencyTime;
    private GetRecencyBlobResponse recencyBlobResponse;
    private String wifiAddress;
    private String description;
    @Ignore
    private TransportDevice device;

    public RecentTransport() {
        transportId = "Default";
        lastExchange = 0;
        lastSeen = System.currentTimeMillis();
        recencyTime = 0;
        wifiAddress = null;
        description = null;
        device = null;
    }

    public RecentTransport(DDDWifiDevice device, GetRecencyBlobResponse response) {
        this.transportId = (device.getId().isEmpty()) ? "Default" : device.getId();
        this.lastExchange = 0; // No exchanges yet
        this.lastSeen = System.currentTimeMillis();
        this.recencyTime = response.getRecencyBlob().getBlobTimestamp();
        this.recencyBlobResponse = response;
        this.wifiAddress = device.getWifiAddress();
        this.description = device.getDescription();
        this.device = device;
    }

    public @NotNull String getTransportId() {
        return transportId;
    }
    public void setTransportId(String transportId) {
        this.transportId = (transportId.isEmpty()) ? "Default" : transportId;
    }
    public long getLastExchange() {
        return lastExchange;
    }
    public void setLastExchange(long lastExchange) {
        this.lastExchange = lastExchange;
    }
    public long getLastSeen() {
        return lastSeen;
    }
    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }
    public long getRecencyTime() {
        return recencyTime;
    }
    public void setRecencyTime(long recencyTime) {
        this.recencyTime = recencyTime;
    }
    public GetRecencyBlobResponse getRecencyBlobResponse() {
        return recencyBlobResponse;
    }
    public void setRecencyBlobResponse(GetRecencyBlobResponse recencyBlobResponse) {
        this.recencyBlobResponse = recencyBlobResponse;
    }
    public String getWifiAddress() {
        return wifiAddress;
    }
    public void setWifiAddress(String wifiAddress) {
        this.wifiAddress = wifiAddress;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public TransportDevice getDevice() {
        return device;
    }
    public void setDevice(TransportDevice device) {
        this.device = device;
        if (device != null) {
            wifiAddress = device instanceof DDDWifiDevice dddWifiDevice ? dddWifiDevice.getWifiAddress() : null;
            description = device.getDescription();
        }
    }
}
