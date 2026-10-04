/**
 * Dropbox control channel contract, shared by the bundleserver and the BundleDropbox app.
 *
 * <p>A dropbox runs its own DDD client endpoint and exchanges control ADUs with the server under
 * {@link net.discdd.dropbox.control.DropboxControl#APP_ID}: it reports its inventory
 * ({@link net.discdd.dropbox.control.InventoryReport}) and the server answers with the exact bundles it may delete
 * ({@link net.discdd.dropbox.control.RetentionPlan}). {@link net.discdd.dropbox.control.DropboxControlCodec} is the
 * only encoder/decoder; {@link net.discdd.dropbox.control.BundleJarInfo} reads the plaintext jar metadata both sides
 * use to identify and tag bundles. See "06 Storage Management Design" in the DDD Dropbox notes.
 */
package net.discdd.dropbox.control;
