package net.discdd.client.bundletransmission;

import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Plain POJO for tracking transport data.
 * This class has no Room/Android dependencies and can be used in Maven projects.
 * All times are in milliseconds since epoch.
 */
public class TransportRecord {
    private String transportId;
    private String description;
    private long lastExchange;
    private long lastSeen;
    private long recencyTime;
    private byte[] recencyBlobBytes;
    private transient TransportDevice device;
    private transient GetRecencyBlobResponse recencyBlobResponse;

    public TransportRecord() {
        this.transportId = "";
    }

    public TransportRecord(String transportId) {
        this.transportId = transportId;
    }

    public TransportRecord(TransportDevice device) {
        this.transportId = device.getId();
        this.description = device.getDescription();
        this.device = device;
    }

    public TransportRecord(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        this.transportId = device.getId();
        this.description = device.getDescription();
        this.device = device;
        setRecencyBlobResponse(recencyBlobResponse);
    }

    public String getTransportId() {
        return transportId;
    }

    public void setTransportId(String transportId) {
        this.transportId = transportId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public byte[] getRecencyBlobBytes() {
        return recencyBlobBytes;
    }

    public void setRecencyBlobBytes(byte[] recencyBlobBytes) {
        this.recencyBlobBytes = recencyBlobBytes;
    }

    public TransportDevice getDevice() {
        return device;
    }

    public void setDevice(TransportDevice device) {
        this.device = device;
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