package com.ddd.server.repository.entity;

// @Table("sent_adu_details")
public class SentAduDetails {

  private String id;

  //  @Column("bundle_id")
  private String bundleId;

  //  @Column("app_id")
  private String appId;

  //  @Column("adu_id_range_start")
  private Long aduIdRangeStart;

  //  @Column("adu_id_range_end")
  private Long aduIdRangeEnd;

  public SentAduDetails() {}

  public SentAduDetails(String bundleId, String appId, Long aduIdRangeStart, Long aduIdRangeEnd) {
    this.bundleId = bundleId;
    this.appId = appId;
    this.aduIdRangeStart = aduIdRangeStart;
    this.aduIdRangeEnd = aduIdRangeEnd;
  }

  public String getId() {
    return this.id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getBundleId() {
    return this.bundleId;
  }

  public void setBundleId(String bundleId) {
    this.bundleId = bundleId;
  }

  public String getAppId() {
    return this.appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public Long getAduIdRangeStart() {
    return this.aduIdRangeStart;
  }

  public void setAduIdRangeStart(Long aduIdRangeStart) {
    this.aduIdRangeStart = aduIdRangeStart;
  }

  public Long getAduIdRangeEnd() {
    return this.aduIdRangeEnd;
  }

  public void setAduIdRangeEnd(Long aduIdRangeEnd) {
    this.aduIdRangeEnd = aduIdRangeEnd;
  }
}
