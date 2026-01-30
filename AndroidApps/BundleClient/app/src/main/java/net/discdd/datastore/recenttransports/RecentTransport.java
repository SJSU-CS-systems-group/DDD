package net.discdd.datastore.recenttransports;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.client.bundletransmission.TransportRecord;
import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Room entity for tracking recently seen transports.
 * Extends TransportRecord from bundle-core and adds Room annotations.
 * All times are in milliseconds since epoch.
 */
@Entity(tableName = "RecentTransports")
public class RecentTransport extends TransportRecord {

    // Room needs a primary key field directly - we override the getter/setter
    @PrimaryKey
    @NonNull
    private String transportId = "";

    // Required no-arg constructor for Room
    public RecentTransport() {
        super();
    }

    @Ignore
    public RecentTransport(TransportDevice device) {
        super(device);
        this.transportId = device.getId();
    }

    @Ignore
    public RecentTransport(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        super(device, recencyBlobResponse);
        this.transportId = device.getId();
    }

    @NonNull
    @Override
    public String getTransportId() {
        return transportId;
    }

    @Override
    public void setTransportId(String transportId) {
        this.transportId = transportId != null ? transportId : "";
        super.setTransportId(this.transportId);
    }

    /**
     * Convert from TransportRecord to RecentTransport for Room persistence.
     */
    public static RecentTransport fromTransportRecord(TransportRecord record) {
        RecentTransport rt = new RecentTransport();
        rt.setTransportId(record.getTransportId());
        rt.setDescription(record.getDescription());
        rt.setLastExchange(record.getLastExchange());
        rt.setLastSeen(record.getLastSeen());
        rt.setRecencyTime(record.getRecencyTime());
        rt.setRecencyBlobBytes(record.getRecencyBlobBytes());
        rt.setDevice(record.getDevice());
        return rt;
    }
}