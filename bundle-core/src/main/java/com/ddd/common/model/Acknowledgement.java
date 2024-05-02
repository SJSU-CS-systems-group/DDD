package com.ddd.model;

import java.util.Objects;

public class Acknowledgement {

  private final String bundleId;

  private final long size;

  public Acknowledgement(String bundleId) {
    this.bundleId = bundleId;
    this.size = bundleId.getBytes().length;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public long getSize() {
    return this.size;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.bundleId);
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
    Acknowledgement other = (Acknowledgement) obj;
    return Objects.equals(this.bundleId, other.bundleId);
  }
}
