package com.ddd.server.repository.entity;


import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;

@Entity(name="LargestBundleIdReceived")
@Table(name="largest_bundle_id_received")
public class LargestBundleIdReceived {

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
    name = "client_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String clientId;

  @Column(
    name = "bundle_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String bundleId;

  public LargestBundleIdReceived() {}

  public LargestBundleIdReceived(String clientId, String bundleId) {
    this.clientId = clientId;
    this.bundleId = bundleId;
  }

  public String getId() {
    return this.id.toString();
  }

  public void setId(String id) {
    this.id = UUID.fromString(id);
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
