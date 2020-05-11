package com.jcraft.jsch;

import java.io.IOException;
import java.io.InputStream;

public class ThrowingInputStream extends InputStream {

  @Override
  public int read() throws IOException {
    throw new IOException("The only thing this stream does - throws exception");
  }
}
