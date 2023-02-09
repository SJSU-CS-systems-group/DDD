package com.ddd.model.poc;

import java.io.InputStream;

public interface Bundle {

  public EncryptionHeader getEncryptionHeader();

  public String getBundleId();

  public InputStream getInputStream();

  public Long getSize();
}
