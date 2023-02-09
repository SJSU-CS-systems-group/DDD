package com.ddd.model.poc;

import java.io.InputStream;
import java.io.OutputStream;
import javax.validation.constraints.NotNull;

/* encapsulates contents of bundle */
public class BundleContents {

  private final EncryptionHeader encryptionHeader;

  private final String bundleId;

  private final BundlePayloadContents bundlePayloadContentsSource;

  public BundleContents(
      @NotNull EncryptionHeader header,
      @NotNull String bundleId,
      @NotNull BundlePayloadContents payloadSource) {
    this.encryptionHeader = header;
    this.bundleId = bundleId;
    this.bundlePayloadContentsSource = payloadSource;
  }

  public EncryptionHeader getEncryptionHeader() {
    return this.encryptionHeader;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public BundlePayloadContents getBundlePayloadContentsSource() {
    return this.bundlePayloadContentsSource;
  }

  public void write(OutputStream out) {
    // TODO
  }

  public static BundleContents constructFromSource(InputStream in) {
    // TODO
    return null;
  }
}
