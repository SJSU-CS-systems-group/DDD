package com.ddd.common.model;

import java.io.File;

public class EncryptionHeader {

  private final File signedPreKey;
  private final File identityKey;
  private final File ratchetKey;
  private final File baseKey;

  /**
   * This constructor is meant for server-side use
   * @param signedPreKey
   * @param identityKey
   * @param ratchetKey
   */
  public EncryptionHeader(File signedPreKey, File identityKey, File ratchetKey) {
    this.identityKey = identityKey;
    this.signedPreKey = signedPreKey;
    this.ratchetKey = ratchetKey;
    this.baseKey = null;
  }

  /**
   * This constructor is meant for client-side use
   * @param baseKey
   * @param identityKey
   */
  public EncryptionHeader(File baseKey, File identityKey) {
    this.identityKey = identityKey;
    this.baseKey = baseKey;
    this.ratchetKey = null;
    this.signedPreKey = null;
  }

  public File getSignedPreKey() { return this.signedPreKey; }
  public File getIdentityKey() { return this.identityKey; }
  public File getRatchetKey() { return this.ratchetKey; }
  public File getBaseKey() { return this.baseKey; }
}