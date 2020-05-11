package com.jcraft.jsch;

import org.slf4j.LoggerFactory;

/**
 * Customized version of JSch that allows providing identity file from in memory string without
 * creating a temporary file.
 */
public class CustomJSch extends JSch {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger("org.iinegve.CustomJSch");

  public CustomJSch() {
    setLogger(new SimpleLogger());
  }

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


  private static class SimpleLogger implements com.jcraft.jsch.Logger {

    @Override
    public boolean isEnabled(int level) {
      return true;
    }

    @Override
    public void log(int level, String message) {
      if (level == DEBUG) {
        log.debug(message);
      } else if (level == INFO) {
        log.info(message);
      } else if (level == WARN) {
        log.warn(message);
      } else if (level == ERROR) {
        log.error(message);
      } else if (level == FATAL) {
        log.error(message);
      }
    }
  }
}
