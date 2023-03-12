package com.ddd.model.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.validation.constraints.NotNull;

public class BundlePayloadContents {
  private final Acknowledgement ackRecord;

  private final List<ADU> ADUs;

  public BundlePayloadContents(@NotNull Acknowledgement ackRecord) {
    this.ackRecord = ackRecord;
    this.ADUs = new ArrayList<>();
  }

  public BundlePayloadContents(@NotNull Acknowledgement ackRecord, @NotNull List<ADU> ADUs) {
    this.ackRecord = ackRecord;
    this.ADUs = ADUs;
  }

  public Acknowledgement getAckRecord() {
    return this.ackRecord;
  }

  public List<ADU> getADUs() {
    // TODO return defensive copy instead
    return this.ADUs;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.ADUs, this.ackRecord);
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
    BundlePayloadContents other = (BundlePayloadContents) obj;
    if (!this.getAckRecord().getBundleId().equals(other.getAckRecord().getBundleId())) {
      return false;
    }
    List<ADU> otherADUs = other.getADUs();

    if (this.getADUs().size() != otherADUs.size()) {
      return false;
    }

    for (int i = 0; i < otherADUs.size(); i++) {
      ADU thisADU = this.ADUs.get(i);
      ADU otherADU = otherADUs.get(i);
      if (thisADU.getAppId() != otherADU.getAppId() || thisADU.getADUId() != otherADU.getADUId()) {
        return false;
      }
    }

    return true;
  }
}
