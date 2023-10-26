package com.ddd.model;

import java.io.File;
import java.util.Objects;

public class ADU {

  private final File source;

  private final String appId;

  private final long aduId;

  private final long size;

  public ADU(File source, String appId, long aduId, long size) {
    this.source = source;
    this.appId = appId;
    this.aduId = aduId;
    this.size = size;
  }

  public File getSource() {
    return this.source;
  }

  public long getSize() {
    return this.size;
  }

  public String getAppId() {
    return this.appId;
  }

  public long getADUId() {
    return this.aduId;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.aduId, this.appId);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (this.getClass() != obj.getClass()) {
      return false;
    }
    ADU other = (ADU) obj;
    return this.aduId == other.aduId && Objects.equals(this.appId, other.appId);
  }
}
