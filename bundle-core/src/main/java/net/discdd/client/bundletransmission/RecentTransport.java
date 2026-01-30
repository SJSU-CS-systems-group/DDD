package net.discdd.client.bundletransmission;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Getter;
import lombok.Setter;
import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Room entity for tracking recently seen transports.
 * All times are in milliseconds since epoch.
 */
@Entity(tableName = "RecentTransports")
public class RecentTransport {
    @PrimaryKey
    public String transportId;

    @Getter @Setter
    private String description;

    @Getter @Setter
    private long lastExchange;

    @Getter @Setter
    private long lastSeen;

    @Getter @Setter
    private long recencyTime;

    @Getter @Setter
    private byte[] recencyBlobBytes;

    // Transient fields - not persisted by Room
    // This may be problematic...
    @Ignore @Getter @Setter
    private transient TransportDevice device;

    @Ignore
    private transient GetRecencyBlobResponse recencyBlobResponse;

    // Required no-arg constructor for Room
    public RecentTransport() {
        this.transportId = "";
    }

    public RecentTransport(TransportDevice device) {
        this.transportId = device.getId();
        this.description = device.getDescription();
        this.device = device;
    }

    public RecentTransport(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        this.transportId = device.getId();
        this.description = device.getDescription();
        this.device = device;
        setRecencyBlobResponse(recencyBlobResponse);
    }

    public void setTransportId(@NonNull String transportId) {
        this.transportId = transportId;
    }

    public String getTransportId() {
        return transportId;
    }

    public GetRecencyBlobResponse getRecencyBlobResponse() {
        if (recencyBlobResponse == null && recencyBlobBytes != null && recencyBlobBytes.length > 0) {
            try {
                recencyBlobResponse = GetRecencyBlobResponse.parseFrom(recencyBlobBytes);
            } catch (InvalidProtocolBufferException e) {
                return null;
            }
        }
        return recencyBlobResponse;
    }

    public void setRecencyBlobResponse(GetRecencyBlobResponse response) {
        this.recencyBlobResponse = response;
        if (response != null) {
            this.recencyBlobBytes = response.toByteArray();
        } else {
            this.recencyBlobBytes = null;
        }
    }
}