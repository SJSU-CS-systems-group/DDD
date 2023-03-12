package com.ddd.model.v2;

import java.util.List;

/* encapsulates structure of a bundle directory */
public interface PayloadEncryptedBundleStore {

  public PayloadEncryptedBundle write(
      BundleContents contents, String key, EncryptionAlgorithm algorithm);

  public PayloadEncryptedBundle read(String key);

  public List<PayloadEncryptedBundle> readAll();

  public PayloadEncryptedBundle copy(PayloadEncryptedBundle bundle);
}
