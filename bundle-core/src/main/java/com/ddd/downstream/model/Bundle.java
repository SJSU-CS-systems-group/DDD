package com.ddd.model;

import java.io.File;

public class Bundle {
  private final File source;

  public Bundle(File source) {
    this.source = source;
  }

  public File getSource() {
    return this.source;
  }
}
