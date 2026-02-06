package net.discdd.datastore;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import net.discdd.client.bundletransmission.*;
import net.discdd.grpc.GetRecencyBlobResponse;

@Entity (tableName = "RecentTransports")
public class RecentTransport {
    @PrimaryKey @NonNull
    private String transportId;
    private String deviceName;
    private TransportDevice device;
    private long lastExchange;
    private long lastSeen;
    private long recencyTime;
    private GetRecencyBlobResponse recencyBlobResponse;

    @Ignore
    public RecentTransport(TransportDevice device) {
        this.device = device;
        this.deviceName = device.getDescription();
        this.transportId = device.getId();
    }

    public RecentTransport(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        this.device = device;
        this.deviceName = device.getDescription();
        this.transportId = device.getId();
        this.recencyBlobResponse = recencyBlobResponse;
    }

    @NonNull
    public String getTransportId() {
        return transportId;
    }

    public void setTransportId(String transportId) { this.transportId = transportId; }

    public TransportDevice getDevice() { return device; }

    public void setDevice(TransportDevice device) {
        this.device = device;
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

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }
}
