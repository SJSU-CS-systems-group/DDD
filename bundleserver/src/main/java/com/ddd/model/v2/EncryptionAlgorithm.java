package com.ddd.model.v2;

public interface EncryptionAlgorithm {

  public byte[] encrypt(byte[] source);

  public byte[] decrypt(byte[] source);
}
