package net.discdd.client.bundletransmission;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import lombok.Getter;
import lombok.Setter;
import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Used to track in memory recently seen transports.
 * All times are in milliseconds since epoch.
 */
@Entity (tableName = "RecentTransports")
public class RecentTransport {
    @PrimaryKey (autoGenerate = true)
    public int id;
    @Getter @Setter
    private TransportDevice device;
    /* @param lastExchange time of last bundle exchange */
    @Getter @Setter
    private long lastExchange;
    /* @param lastSeen time of last device discovery */
    @Getter @Setter
    private long lastSeen;
    /* @param recencyTime time from the last recencyBlob received */
    @Getter @Setter
    private long recencyTime;
    /* @param recencyBlobResponse the latest recencyBlobResponse received */
    @Getter @Setter
    private GetRecencyBlobResponse recencyBlobResponse;

    public RecentTransport(TransportDevice device) {
        this.device = device;
    }

    public RecentTransport(TransportDevice device, GetRecencyBlobResponse recencyBlobResponse) {
        this.device = device;
        this.recencyBlobResponse = recencyBlobResponse;
    }

}
