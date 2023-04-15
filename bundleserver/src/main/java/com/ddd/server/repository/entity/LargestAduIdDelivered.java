package com.ddd.server.repository.entity;

// @Table("largest_adu_id_delivered")
public class LargestAduIdDelivered {

  //  @Id
  //  @Column("id")
  private String id;

  //  @Column("client_id")
  private String clientId;

  //  @Column("app_id")
  private String appId;

  //  @Column("adu_id")
  private Long aduId;

  public LargestAduIdDelivered() {}

  public LargestAduIdDelivered(String clientId, String appId, Long aduId) {
    this.clientId = clientId;
    this.appId = appId;
    this.aduId = aduId;
  }

  public String getId() {
    return this.id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getClientId() {
    return this.clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getAppId() {
    return this.appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public Long getAduId() {
    return this.aduId;
  }

  public void setAduId(Long aduId) {
    this.aduId = aduId;
  }
}
