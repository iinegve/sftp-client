package com.jcraft.jsch;

/**
 * Customized version of JSch that allow providing identity file from the in memory string without
 * actually creating a temporary file.
 */
public class CustomJSch extends JSch {

  /**
   * Adds RSA identity without having a file.
   *
   * @param privateKey private key
   * @throws UncheckedJSchException in case anything happens
   */
  public void addRsaIdentity(String privateKey) {
    try {
      Identity identity = IdentityFile.newInstance("in-memory", privateKey.getBytes(), null, this);
      addIdentity(identity, null);
    } catch (Exception ex) {
      throw new UncheckedJSchException(ex);
    }
  }
}
