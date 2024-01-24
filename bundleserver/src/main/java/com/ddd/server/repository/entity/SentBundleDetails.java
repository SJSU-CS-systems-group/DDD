package com.ddd.server.repository.entity;


import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;

@Entity(name="SentBundleDetails")
@Table(name = "sent_bundle_details")
public class SentBundleDetails {

  @Id
  @GeneratedValue(generator = "UUID")
  @GenericGenerator(
        name = "UUID",
        strategy = "org.hibernate.id.UUIDGenerator"
  ) 
  @Column(
    name="id",
    updatable = false,
    nullable = false
  )
  private UUID id;

  @Column(
    name = "bundle_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String bundleId;

  @Column(
    name = "client_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String clientId;
  
  @Column(
    name = "acked_bundle_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String ackedBundleId;
  

  public SentBundleDetails() {}

  public SentBundleDetails(String bundleId, String ackedBundleId) {
    this.bundleId = bundleId;
    this.ackedBundleId = ackedBundleId;
  }

  public String getId() {
    return this.id.toString();
  }

  public void setId(String id) {
    this.id = UUID.fromString(id);
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
