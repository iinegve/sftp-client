package org.iinegve.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.CustomJSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static java.util.stream.Collectors.toList;

public class SftpClient {

  private static final Logger log = LoggerFactory.getLogger(SftpClient.class);

  private final String host;
  private final int port;
  private final String username;

  private final CustomJSch jsch;
  private final Properties config;
  private final ThreadLocal<Session> jschSession;

  SftpClient(String host, int port, String username, CustomJSch jsch) {
    this.host = host;
    this.port = port;
    this.username = username;

    this.jsch = jsch;
    this.config = new Properties();
    config.put("StrictHostKeyChecking", "no");
    this.jschSession = new ThreadLocal<>();
  }

  public static SftpClientBuilder sftpClient() {
    return new SftpClientBuilder();
  }

  public void connect() {
    try {
      if (isConnected()) {
        disconnect();
      }
      Session session = jsch.getSession(username, host, port);
      session.setConfig(config);
      session.connect();
      jschSession.set(session);
    } catch (JSchException e) {
      throw new SftpClientException(e);
    }
  }

  public void disconnect() {
    if (jschSession.get() != null) {
      jschSession.get().disconnect();
    }
    jschSession.remove();
  }

  public boolean isConnected() {
    return jschSession.get() != null && jschSession.get().isConnected();
  }

  /**
   * Uploads file to sftp
   * <p>
   * @param file      file on local machine to upload
   * @param remoteDir remote directory where to upload. Must not be null or empty, use . for current
   *                  directory.
   */
  public void upload(File file, String remoteDir) {
    if (remoteDir == null || remoteDir.isEmpty()) {
      throw new IllegalArgumentException("Remote dir must not be neither null nor empty");
    }

    doInSftp(channel -> {
      String destination = remoteDir + "/" + file.getName();
      log.info("Uploading file [{}] to [{}]", file.getAbsoluteFile(), destination);
      channel.put(file.getAbsolutePath(), destination);
    });
  }

  public List<String> listDirectory(String remoteDir) {
    if (remoteDir == null || remoteDir.isEmpty()) {
      throw new IllegalArgumentException("Remote dir must not be neither null nor empty");
    }

    return doInSftp(channel -> {
      log.debug("Listing directory [{}]", remoteDir);
      List<LsEntry> ls = new ArrayList<>(channel.ls(remoteDir));
      List<String> filenames = ls.stream()
        .map(LsEntry::getFilename)
        .filter(fn -> !fn.equals("."))
        .filter(fn -> !fn.equals(".."))
        .collect(toList());
      log.debug("Found: [{}]", filenames);
      return filenames;
    });
  }

  /**
   * Downloads file to either a given directory or a given file
   * <p>
   * @param remoteFilePath   path to a file on sftp
   * @param localDestination path to either a directory to put downloaded file or a file to put
   *                         downloaded file into
   */
  public File download(String remoteFilePath, File localDestination) {
    return doInSftp(channel -> {
      int remoteFileNameIndex = remoteFilePath.lastIndexOf('/');
      String remoteFileName = remoteFileNameIndex == -1
        ? remoteFilePath : remoteFilePath.substring(remoteFileNameIndex + 1);

      String localFileName = localDestination.isDirectory()
        ? localDestination + File.separator + remoteFileName
        : localDestination.getAbsolutePath();
      log.debug("Downloading remote file [{}] into local [{}]", remoteFileName, localFileName);

      try (InputStream in = channel.get(remoteFilePath);
           OutputStream out = new FileOutputStream(new File(localFileName))
      ) {
        in.transferTo(out);
      } catch (IOException e) {
        log.error("Cannot download file", e);
        throw new SftpClientException(e);
      }
      return new File(localFileName);
    });
  }

  /**
   * Move file on sftp from one location to another
   * <p>
   * @param remoteFileFrom path to file to move
   * @param remoteFileTo   path to a file where to move it
   */
  public void move(String remoteFileFrom, String remoteFileTo) {
    doInSftp(channel -> {
      log.debug("Move [{}] to [{}]", remoteFileFrom, remoteFileTo);
      channel.rename(remoteFileFrom, remoteFileTo);
    });
  }

  /**
   * Deletes a file.
   * <p>
   * Note: doesn't delete directory.
   *
   * @param remoteFile relative path to designate file to be deleted
   */
  public void delete(String remoteFile) {
    doInSftp(channel -> {
      log.debug("Delete [{}]", remoteFile);
      channel.rm(remoteFile);
    });
  }

  /**
   * Deletes files in bulk.
   * <p>
   * Note: doesn't delete directories.
   *
   * @param remoteFiles collection of relative paths that have to be deleted
   */
  public void delete(List<String> remoteFiles) {
    Set<String> toBeDeletedFiles = new HashSet<>(remoteFiles);
    doInSftp(channel -> {
      log.debug("Deleting [{}] files", toBeDeletedFiles.size());
      Iterator<String> it = toBeDeletedFiles.iterator();
      while (it.hasNext()) {
        channel.rm(it.next());
        it.remove();
      }
      log.debug("Files successfully deleted");
    });
  }

  /**
   * Method that wraps simple operation to keep all the low level burden with opening and closing
   * the channel in here leaving actual operation to where it belongs.
   * <p>
   * @param op  operation to do in sftp
   * @param <T> type of value that's going to be returned as a result of operation
   * @return result of operation, for example list of file names
   */
  private <T> T doInSftp(ReturningFileOp<T> op) {
    ChannelSftp channel = null;
    if (!isConnected()) {
      connect();
    }

    try {
      channel = reconnectChannelOnException();
      int retries = 2;
      SftpClientException retryEx = null;
      while (retries > 0) {
        try {
          return op.process(channel);
        } catch (Exception ex) {
          retries--;
          log.error("Caught exception [{}], retrying", ex.getMessage());
          retryEx = new SftpClientException(ex);
        }
      }
      throw retryEx;
    } catch (JSchException ex) {
      disconnect();
      throw new SftpClientException(ex);
    } finally {
      if (channel != null) {
        channel.disconnect();
      }
    }
  }

  private ChannelSftp reconnectChannelOnException() throws JSchException {
    try {
      ChannelSftp channel = (ChannelSftp) jschSession.get().openChannel("sftp");
      channel.connect();
      return channel;
    } catch (Exception e) {
      log.warn("Trying to reconnect because of [{}]", e.getMessage());
      connect();
      ChannelSftp channel = (ChannelSftp) jschSession.get().openChannel("sftp");
      channel.connect();
      return channel;
    }
  }

  /**
   * The operation doesn't have result, thus doesn't return anything, but it's convenient to have
   * it, because it helps avoid having return statement in all the places, where this operation is
   * used.
   * <p>
   * @param op operation to do in sftp
   */
  private void doInSftp(FileOp op) {
    doInSftp(channel -> {
      op.process(channel);
      return null;
    });
  }

  @FunctionalInterface
  private interface FileOp {
    public void process(ChannelSftp channel) throws SftpException;
  }

  @FunctionalInterface
  private interface ReturningFileOp<T> {
    public T process(ChannelSftp channel) throws SftpException;
  }
}
