package net.discdd.datastore;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import net.discdd.client.bundletransmission.*;
import net.discdd.grpc.GetRecencyBlobResponse;

@Entity
public class RecentTransport {
    @PrimaryKey @NonNull
    final private String transportId;
    private TransportDevice device;
    private long lastExchange;
    private long lastSeen;
    private long recencyTime;
    private GetRecencyBlobResponse recencyBlobResponse;

    public RecentTransport(TransportDevice device) {
        this.device = device;
        this.transportId = device.getId();
    }

    public RecentTransport(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        this.device = device;
        this.transportId = device.getId();
        this.recencyBlobResponse = recencyBlobResponse;
    }

    @NonNull
    public String getTransportId() {
        return transportId;
    }

    public TransportDevice getDevice() {
        return device;
    }

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
}
