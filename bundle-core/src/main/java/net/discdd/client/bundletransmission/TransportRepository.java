package net.discdd.client.bundletransmission;

import net.discdd.grpc.RecencyBlob;

public interface TransportRepository {
    boolean isRecencyBlobNew(TransportDevice device, RecencyBlob recencyBlob);

    void timestampExchange(TransportDevice device);

}
