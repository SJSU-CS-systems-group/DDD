package net.discdd.bundledropbox.visit;

import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.bundledropbox.store.ForwardLedger;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** In-memory {@link ForwardLedger} and {@link RecencyBlobStore} fakes for visit tests. */
final class FakeLedgerAndRecency {
    private FakeLedgerAndRecency() {}

    /** Set-backed ledger keyed by {@code bundleId + "@" + carrierId}. */
    static final class Ledger implements ForwardLedger {
        final Set<String> forwarded = new HashSet<>();

        @Override
        public synchronized boolean wasForwardedTo(String bundleId, String carrierId) {
            return forwarded.contains(bundleId + "@" + carrierId);
        }

        @Override
        public synchronized void recordForwarded(String bundleId, String carrierId, long atMillis) {
            forwarded.add(bundleId + "@" + carrierId);
        }
    }

    /** Keeps the newest SUCCESS blob, exactly as the {@link RecencyBlobStore#offer} contract says. */
    static final class Recency implements RecencyBlobStore {
        private byte[] current;
        private long timestamp;

        @Override
        public synchronized Optional<byte[]> current() { return Optional.ofNullable(current); }

        @Override
        public synchronized boolean offer(byte[] rawResponse) {
            try {
                GetRecencyBlobResponse r = GetRecencyBlobResponse.parseFrom(rawResponse);
                if (r.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS) return false;
                long ts = r.getRecencyBlob().getBlobTimestamp();
                if (ts <= timestamp) return false;
                current = rawResponse.clone();
                timestamp = ts;
                return true;
            } catch (InvalidProtocolBufferException e) {
                return false;
            }
        }

        @Override
        public synchronized long currentTimestamp() { return timestamp; }
    }
}
