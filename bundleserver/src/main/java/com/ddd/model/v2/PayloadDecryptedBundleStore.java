package com.ddd.model.v2;

public interface PayloadDecryptedBundleStore {

  public PayloadDecryptedBundle decryptAndWrite(
      PayloadEncryptedBundle encryptedBundle, String key, EncryptionAlgorithm algorithm);

  public PayloadDecryptedBundle copy(PayloadDecryptedBundle bundle);
}
