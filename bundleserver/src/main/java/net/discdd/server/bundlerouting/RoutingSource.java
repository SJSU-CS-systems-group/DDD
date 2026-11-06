package net.discdd.server.bundlerouting;

/** How the server learned that a peer can reach a client; part of a {@code RoutingEdge}'s key. */
public enum RoutingSource {
    /** the peer uploaded one of the client's bundles (server clock) */
    UPLOADED,
    /** the client's {@code routing.metadata} reported meeting the peer */
    REPORTED,
    /** a dropbox's accepted inventory held one of the client's bundles */
    DEPOSITED
}
