package org.iinegve.sftp;

import com.jcraft.jsch.CustomJSch;

public final class SftpClientBuilder {

  private String host;
  private int port;
  private String username;
  private byte[] privateKey;
  private CustomJSch jsch;

  public SftpClientBuilder host(String host) {
    this.host = host;
    return this;
  }

  public SftpClientBuilder port(int port) {
    this.port = port;
    return this;
  }

  public SftpClientBuilder username(String username) {
    this.username = username;
    return this;
  }

  public SftpClientBuilder privateKey(byte[] privateKey) {
    this.privateKey = privateKey;
    return this;
  }

  public SftpClientBuilder jsch(CustomJSch jsch) {
    this.jsch = jsch;
    return this;
  }

  public SftpClient build() {
    if (jsch == null) {
      jsch = new CustomJSch();
    }
    return new SftpClient(host, port, username, privateKey, jsch);
  }
}
