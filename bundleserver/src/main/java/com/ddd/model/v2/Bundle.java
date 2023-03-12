package com.ddd.model.v2;

import java.io.InputStream;

public interface Bundle {

  public EncryptionHeader getEncryptionHeader();

  public String getBundleId();

  public InputStream getInputStream();

  public Long getSize();
}
