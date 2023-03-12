package com.ddd.model.v2;

import java.io.InputStream;

public interface ADU {

  public String getAppId();

  public Long getADUId();

  public Long getSize();

  public InputStream getInputStream();
}
