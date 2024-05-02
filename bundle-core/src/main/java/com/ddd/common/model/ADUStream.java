package com.ddd.model;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public interface ADUStream {

  public int read(byte[] b);

  public void close();
}

class ADUStreamImpl implements ADUStream {

  private BufferedInputStream bufferedInputStream;

  public ADUStreamImpl() {
    try {
      this.bufferedInputStream = new BufferedInputStream(new FileInputStream(""));
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
  }

  @Override
  public int read(byte[] b) {
    try {
      return this.bufferedInputStream.read(b);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0;
  }

  @Override
  public void close() {
    try {
      this.bufferedInputStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
