package com.ddd.server.repository.entity;

// @Table("last_bundle_id_sent")
public class LastBundleIdSent {

  //  @Id
  //  @Column("client_id")
  private String clientId;

  //  @Column("bundle_id")
  private String bundleId;

  public LastBundleIdSent() {}

  public LastBundleIdSent(String clientId, String bundleId) {
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
