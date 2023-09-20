package com.ddd.server.repository.entity;

// @Table("largest_bundle_id_received")
public class LargestBundleIdReceived {

  //  @Id
  //  @Column("client_id")
  private String clientId;

  //  @Column("bundle_id")
  private String bundleId;

  public LargestBundleIdReceived() {}

  public LargestBundleIdReceived(String clientId, String bundleId) {
    this.clientId = clientId;
    this.bundleId = bundleId;
  }

  public String getClientId() {
    return this.clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public void setBundleId(String bundleId) {
    this.bundleId = bundleId;
  }
}
