package com.ddd.model.v2;

import javax.validation.constraints.NotNull;

public class EncryptionHeader {

  private final Long P;
  private final Long N;

  public EncryptionHeader(@NotNull Long P, @NotNull Long N) {
    this.P = P;
    this.N = N;
  }

  public Long getP() {
    return this.P;
  }

  public Long getN() {
    return this.N;
  }
}
