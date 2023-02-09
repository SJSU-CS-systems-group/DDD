package com.ddd.model.poc;

public interface EncryptionAlgorithm {

  public byte[] encrypt(byte[] source);

  public byte[] decrypt(byte[] source);
}
