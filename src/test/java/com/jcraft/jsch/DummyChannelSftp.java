package com.jcraft.jsch;

import java.util.Vector;

public class DummyChannelSftp extends ChannelSftp {

  @Override
  public void connect() throws JSchException {
    // do nothing
  }

  @Override
  public void disconnect() {
    // do nothing
  }

  @Override
  public Vector ls(String path) {
    return new Vector();
  }
}
