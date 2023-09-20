package com.ddd.server.repository.entity;

// @Table("sent_bundle_details")
public class SentBundleDetails {

  //  @Id
  //  @Column("bundle_id")
  private String bundleId;

  private String clientId;
  
  //  @Column("acked_bundle_id")
  private String ackedBundleId;
  

  public SentBundleDetails() {}

  public SentBundleDetails(String bundleId, String ackedBundleId) {
    this.bundleId = bundleId;
    this.ackedBundleId = ackedBundleId;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public void setBundleId(String bundleId) {
    this.bundleId = bundleId;
  }

  public String getAckedBundleId() {
    return this.ackedBundleId;
  }

  public void setAckedBundleId(String ackedBundleId) {
    this.ackedBundleId = ackedBundleId;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }
}
