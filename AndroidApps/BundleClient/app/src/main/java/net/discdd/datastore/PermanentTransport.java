package net.discdd.datastore;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.grpc.GetRecencyBlobResponse;

@Entity
public class PermanentTransport {
    @PrimaryKey @NonNull
    public String transportId;
    public String description;
    public long lastExchange;
    private long lastSeen;
    private long recencyTime;
    private GetRecencyBlobResponse recencyBlobResponse;

    public PermanentTransport(ClientBundleTransmission.RecentTransport transport) {
        this.transportId = transport.device.getId();
        this.description = transport.device.getDescription();
        this.lastExchange = 0;
        this.lastSeen = 0;
        this.recencyTime = 0;
        this.recencyBlobResponse = null;
    }
}
