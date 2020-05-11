package com.jcraft.jsch;

public class DummyChannelExec extends ChannelExec {

  @Override
  public void connect() {
    // do nothing
  }

  @Override
  public void disconnect() {
    // do nothing
  }
}
