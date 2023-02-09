package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

public class WhoAmIResponse {
  @JsonProperty
  public String uuid;

  @JsonProperty
  public String pni;

  @JsonProperty
  public String number;

  @JsonProperty
  public String username;

  public String getAci() {
    return uuid;
  }

  public String getPni() {
    return pni;
  }

  public String getNumber() {
    return number;
  }

  public String getUsername() {
    return username;
  }
}
