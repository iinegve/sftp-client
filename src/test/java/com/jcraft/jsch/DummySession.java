package com.jcraft.jsch;

public class DummySession extends Session {

  public DummySession() throws JSchException {
    super(new CustomJSch(), "username", "", 0);
  }

  @Override
  public void connect() {
    // do nothing
  }

  @Override
  public void disconnect() {
    // do nothing
  }
}
