package com.ddd.model;

import java.io.File;

public class EncryptionHeader {
  private final File clientBaseKey;
  private final File clientIdentityKey;

  public EncryptionHeader(File clientBaseKey, File clientIdentityKey) {
    this.clientBaseKey = clientBaseKey;
    this.clientIdentityKey = clientIdentityKey;
  }

  public File getClientBaseKey() {
    return this.clientBaseKey;
  }

  public File getClientIdentityKey() {
    return this.clientIdentityKey;
  }
}
