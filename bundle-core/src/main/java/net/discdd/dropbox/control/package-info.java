/**
 * Dropbox control channel contract, shared by the bundleserver and the BundleDropbox app.
 *
 * <p>A dropbox runs its own DDD client endpoint and exchanges control ADUs with the server under
 * {@link net.discdd.dropbox.control.DropboxControl#APP_ID}: it reports its inventory
 * ({@link net.discdd.dropbox.control.InventoryReport}) and the server answers with the exact bundles it may delete
 * ({@link net.discdd.dropbox.control.RetentionPlan}).
 *
 * <p>Under {@link net.discdd.dropbox.control.DropboxControl#RECENCY_APP_ID} the dropbox gets its own server-signed
 * recency blob: it sends a {@link net.discdd.dropbox.control.RecencyRequest} signed with its TLS key
 * ({@link net.discdd.dropbox.control.RecencyRequestSigner}) and the server answers with a
 * {@link net.discdd.dropbox.control.RecencyGrant} minted for that key's sender ID. The dropbox never re-serves a
 * carrier transport's blob.
 *
 * <p>{@link net.discdd.dropbox.control.DropboxControlCodec} is the only encoder/decoder for both channels;
 * {@link net.discdd.dropbox.control.BundleJarInfo} reads the plaintext jar metadata both sides use to identify and
 * tag bundles. See "06 Storage Management Design" and "09 Own Recency Blob Design" in the DDD Dropbox notes.
 */
package net.discdd.dropbox.control;
