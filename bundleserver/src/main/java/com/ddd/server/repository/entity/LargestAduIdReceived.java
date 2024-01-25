package com.ddd.server.repository.entity;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;


@Entity(name="LargestAduIdReceived")
@Table(name="largest_adu_id_received")
public class LargestAduIdReceived {

  @Id
  @UuidGenerator
  @Column(
      name = "id",
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
    name = "app_id",
    nullable = false,
    columnDefinition = "TEXT"
  )
  @NotBlank
  private String appId;

  @Column(
    name = "adu_id",
    nullable = false,
    columnDefinition = "BIGINT"
  )
  @NotNull
  private Long aduId;

  public LargestAduIdReceived() {}

  public LargestAduIdReceived(String clientId, String appId, Long aduId) {
    this.clientId = clientId;
    this.appId = appId;
    this.aduId = aduId;
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
