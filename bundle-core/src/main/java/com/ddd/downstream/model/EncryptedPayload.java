package com.ddd.downstream.model;

import java.io.File;

public class EncryptedPayload {
  private final String bundleId;
  private final File source;

  public EncryptedPayload(String bundleId, File source) {
    this.bundleId = bundleId;
    this.source = source;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public File getSource() {
    return this.source;
  }
}
