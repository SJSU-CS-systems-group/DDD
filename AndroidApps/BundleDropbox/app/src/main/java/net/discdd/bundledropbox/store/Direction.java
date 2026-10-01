package net.discdd.bundledropbox.store;

/**
 * Which way a relayed bundle is travelling through the dropbox.
 *
 * <p>The dropbox is opaque: it never decrypts bundles, it only stores and forwards the encrypted
 * jar bytes. Each direction has its own directory under {@link DropboxPaths#dropboxDir}, and
 * file names are always the bundle's encrypted bundle id (the stock client's PSI hashes those
 * file names, so they must match exactly; Research/R1 §3.3).
 */
public enum Direction {
    /**
     * Client to server. Deposited by BundleClients while the dropbox is HOSTING, uploaded to every
     * visited BundleTransport (carrier) that has not received it yet.
     */
    UPSTREAM,

    /**
     * Server to client. Pulled from carriers while the dropbox is VISITING (v1: download-all),
     * offered to BundleClients via PSI while HOSTING.
     */
    DOWNSTREAM
}
