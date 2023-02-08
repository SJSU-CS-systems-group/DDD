package com.ddd.model;

import java.io.File;
import java.util.List;

public class Bundle {

  private final File source;

  public File getSource() {
    return this.source;
  }

  private final String bundleId;

  private final Acknowledgement ackRecord;

  private final List<ADU> ADUs;

  public Bundle(String bundleId, Acknowledgement ackRecord, List<ADU> ADUs, File source) {
    this.bundleId = bundleId;
    this.ackRecord = ackRecord;
    this.ADUs = ADUs;
    this.source = source;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public Acknowledgement getAckRecord() {
    return this.ackRecord;
  }

  public List<ADU> getADUs() {
    return this.ADUs;
  }

  public static class Builder {

    private File source;

    private String bundleId;

    private Acknowledgement ackRecord;

    private List<ADU> ADUs;

    public String getBundleId() {
      return this.bundleId;
    }

    public Acknowledgement getAckRecord() {
      return this.ackRecord;
    }

    public List<ADU> getADUs() {
      return this.ADUs;
    }

    public File getSource() {
      return this.source;
    }

    public Builder setAckRecord(Acknowledgement ackRecord) {
      this.ackRecord = ackRecord;
      return this;
    }

    public Builder setADUs(List<ADU> ADUs) {
      this.ADUs = ADUs;
      return this;
    }

    public Builder setBundleId(String bundleId) {
      this.bundleId = bundleId;
      return this;
    }

    public Builder setSource(File source) {
      this.source = source;
      return this;
    }

    public Bundle build() {
      return new Bundle(this.bundleId, this.ackRecord, this.ADUs, this.source);
    }
  }
}
