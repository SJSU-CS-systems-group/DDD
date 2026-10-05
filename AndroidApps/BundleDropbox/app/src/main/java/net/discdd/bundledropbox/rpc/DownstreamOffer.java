package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.store.BundleRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * The committed DOWNSTREAM IDs offered in one PSI exchange. A stock transport offers every held client-bound
 * file, and the dropbox follows that rule. An older held version may be the only one inside the client's
 * current ten-ID window even when a newer version for that client is also held. Only a server retention
 * plan removes a committed bundle from future offers.
 */
final class DownstreamOffer {
    private DownstreamOffer() {}

    /**
     * @param downstream all committed DOWNSTREAM records
     * @return immutable PSI snapshot of every held ID, in store order
     */
    static List<String> snapshot(List<BundleRecord> downstream) {
        List<String> ids = new ArrayList<>(downstream.size());
        for (BundleRecord record : downstream) ids.add(record.bundleId());
        return List.copyOf(ids);
    }
}
