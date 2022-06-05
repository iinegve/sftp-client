package org.iinegve.sftp;

import com.github.stefanbirkner.fakesftpserver.rule.FakeSftpServerRule;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.CustomJSch;
import com.jcraft.jsch.DummyChannelExec;
import com.jcraft.jsch.DummyChannelSftp;
import com.jcraft.jsch.DummySession;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.ThrowingInputStream;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.iinegve.sftp.SftpClient.sftpClient;
import static org.iinegve.sftp.Util.list;

public class SftpClientTest {

  private static final int port = 2000;

  @Rule
  public final FakeSftpServerRule sftpServer = new FakeSftpServerRule()
    .setPort(port)
    .addUser("user", "");

  @Before
  public void setUp() throws Exception {
    sftpServer.createDirectory("/subdir");

    sftpServer.putFile("/file-in-root", new String(content("files/file-in-root")), UTF_8);
    sftpServer.createDirectory("/list-files");
    sftpServer.createDirectory("/list-files/sublist-files");
    sftpServer.putFile("/list-files/sublist-files/first-file", "first file content", UTF_8);
    sftpServer.putFile("/list-files/sublist-files/second-file", "second file content", UTF_8);
    sftpServer.createDirectory("/to-move");
    sftpServer.createDirectory("/to-delete");
  }

  @Test
  public void create_connect_disconnect__properly_changes_state_of_client() {
    SftpClient sftp = workingSftpClient();
    assertThat(sftp.isConnected()).isFalse();

    sftp.connect();
    assertThat(sftp.isConnected()).isTrue();

    sftp.disconnect();
    assertThat(sftp.isConnected()).isFalse();
  }

  @Test
  public void disconnect_on_reconnection__when_already_connected() {
    int[] connectCount = {0};
    int[] disconnectCount = {0};
    CustomJSch jsch = new CustomJSch() {
      @Override
      public Session getSession(String username, String host, int port) throws JSchException {
        return new DummySession() {
          @Override public boolean isConnected() { return true; }
          @Override public void connect() { connectCount[0]++; }
          @Override public void disconnect() { disconnectCount[0]++; }
        };
      }
    };

    SftpClient sftp = sftpClient().host("localhost").port(2000).username("user")
      .privateKey(content("files/private-key")).jsch(jsch).build();

    sftp.connect(); // first time session is null inside, connect that
    sftp.connect(); // second time it's connected, then it has to be disconnected

    assertThat(connectCount[0]).isEqualTo(2);
    assertThat(disconnectCount[0]).isEqualTo(1);
  }

  @Test
  public void implicitly_connect_to_sftp_server__when_not_connected() {
    SftpClient sftp = workingSftpClient();

    List<String> filenames = sftp.listDirectory(".");

    assertThat(filenames)
      .containsOnly("file-in-root", "subdir", "list-files", "to-move", "home", "to-delete");
  }

  @Test
  public void upload_file_to_remote_directory__when_remote_directory_provided() throws Exception {
    SftpClient sftp = workingSftpClient();
    sftp.connect();

    sftp.upload(new File(uri("files/a-file")), "subdir");
    byte[] aFile = sftpServer.getFileContent("/subdir/a-file");
    assertThat(aFile).isEqualTo("Just a-file to test upload".getBytes());

    sftp.upload(new File(uri("files/b-file")), "/subdir");
    byte[] bFile = sftpServer.getFileContent("/subdir/b-file");
    assertThat(bFile).isEqualTo("Just b-file to test upload".getBytes());
  }

  @Test
  public void upload_file_to_root_directory__when_remote_directory_current() throws Exception {
    SftpClient sftp = workingSftpClient();
    sftp.connect();

    sftp.upload(new File(uri("files/c-file")), ".");

    byte[] aFile = sftpServer.getFileContent("/c-file");
    assertThat(aFile).isEqualTo("Just c-file to test upload".getBytes());
  }

  @Test
  public void upload_file_throws_exception__when_remote_directory_null_or_empty() {
    assertThatThrownBy(() -> workingSftpClient().upload(new File(uri("files/d-file")), ""))
      .isExactlyInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> workingSftpClient().upload(new File(uri("files/d-file")), null))
      .isExactlyInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void list_directory() {
    SftpClient sftp = workingSftpClient();
    sftp.connect();

    List<String> fileNames = sftp.listDirectory("list-files/sublist-files");

    assertThat(fileNames).containsOnly("first-file", "second-file");
  }

  @Test
  public void list_directory_throws__when_directory_null_or_empty() {
    assertThatThrownBy(() -> workingSftpClient().listDirectory(""))
      .isExactlyInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> workingSftpClient().listDirectory(""))
      .isExactlyInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void download_remote_files_into_local_directory() throws IOException {
    File tempDir = Files.createTempDirectory("sftp-client").toFile();
    tempDir.deleteOnExit();

    SftpClient sftp = workingSftpClient();
    sftp.connect();

    List<String> fileNames = sftp.listDirectory("list-files/sublist-files");
    fileNames.forEach(fn -> sftp.download("list-files/sublist-files/" + fn, tempDir));
    File fileInRoot = sftp.download("/file-in-root", tempDir);

    assertThat(tempDir.list()).containsOnly("file-in-root", "first-file", "second-file");
    assertThat(content(fileInRoot)).isEqualTo("File in sftp root");
    assertThat(content(new File(tempDir, "first-file"))).isEqualTo("first file content");
    assertThat(content(new File(tempDir, "second-file"))).isEqualTo("second file content");
  }

  @Test
  public void download_remote_file_into_local_file() throws IOException {
    File tempDir = Files.createTempDirectory("sftp-client").toFile();
    tempDir.deleteOnExit();

    SftpClient sftp = workingSftpClient();
    sftp.connect();

    File fileInRoot = sftp.download("/file-in-root", new File(tempDir, "file-in-root"));
    File firstFile = sftp.download("list-files/sublist-files/first-file", new File(tempDir, "first-file"));

    assertThat(tempDir.list()).containsOnly("file-in-root", "first-file");
    assertThat(content(fileInRoot)).isEqualTo("File in sftp root");
    assertThat(content(firstFile)).isEqualTo("first file content");
  }

  @Test
  public void move_files_to_another_directory() {
    SftpClient sftp = workingSftpClient();
    sftp.connect();

    assertThat(sftp.listDirectory("."))
      .containsOnly("file-in-root", "subdir", "list-files", "to-move", "home", "to-delete");
    assertThat(sftp.listDirectory("to-move")).isEmpty();

    sftp.move("file-in-root", "to-move/file-in-to-move");

    assertThat(sftp.listDirectory("to-move")).containsOnly("file-in-to-move");
  }

  @Test
  public void delete_files() {
    SftpClient sftp = workingSftpClient();
    sftp.connect();

    sftp.upload(new File(uri("files/e-file")), "to-delete");
    assertThat(sftp.listDirectory("to-delete")).contains("e-file");

    sftp.delete("to-delete/e-file");
    assertThat(sftp.listDirectory("to-delete")).doesNotContain("e-file");
  }

  @Test
  public void bulk_delete_files() throws IOException {
    sftpServer.createDirectory("/bulk-delete-dir");
    String content = new String(content("files/file-in-root"), Charset.defaultCharset());
    for (int i = 0; i < 200; i++) {
      sftpServer.putFile("/bulk-delete-dir/file-" + i, content, UTF_8);
    }
    SftpClient sftp = workingSftpClient();
    List<String> before = sftp.listDirectory("/bulk-delete-dir");
    List<String> files = before.stream().map(fn -> "bulk-delete-dir/" + fn).collect(toList());

    sftp.delete(files);

    List<String> after = sftp.listDirectory("/bulk-delete-dir");
    assertThat(after).isEmpty();
  }

  @Test
  public void repeatable_bulk_delete_files_do_not_delete_the_same_files() {
    List<String> deletedFiles = new ArrayList<>();
    CustomJSch jsch = new CustomJSch() {
      @Override
      public Session getSession(String username, String host, int port) throws JSchException {
        return new DummySession() {
          @Override
          public Channel openChannel(String type) {
            return new DummyChannelSftp() {
              @Override
              public void rm(String path) throws SftpException {
                if (path.equals("4") && !deletedFiles.contains("4")) {
                  deletedFiles.add(path);
                  throw new SftpException(1, "Cannot drop 4th file");
                }
                deletedFiles.add(path);
              }
            };
          }
        };
      }
    };

    SftpClient sftp = sftpClient().host("localhost").port(2007).username("user")
      .privateKey(content("files/private-key")).jsch(jsch).build();

    sftp.connect();
    sftp.delete(list("1", "2", "3", "4", "5", "6"));
    assertThat(deletedFiles).containsOnly("1", "2", "3", "4", "4", "5", "6");
  }

  @Test
  public void retry_operations() {
    int[] getCount = {0};
    CustomJSch jsch = new CustomJSch() {
      @Override
      public Session getSession(String username, String host, int port) throws JSchException {
        return new DummySession() {
          @Override
          public Channel openChannel(String type) {
            return new DummyChannelSftp() {
              @Override
              public InputStream get(String src) {
                getCount[0]++;
                return new ThrowingInputStream();
              }
            };
          }
        };
      }
    };

    SftpClient sftp = sftpClient().host("localhost").port(2007).username("user")
      .privateKey(content("files/private-key")).jsch(jsch).build();

    sftp.connect();
    assertThatThrownBy(() -> sftp.download("remote-path", new File("target")))
      .isExactlyInstanceOf(SftpClientException.class);
    assertThat(getCount[0]).isGreaterThan(1);
  }

  @Test
  public void disconnect_without_connect_does_not_throw() {
    assertThatCode(workingSftpClient()::disconnect).doesNotThrowAnyException();
  }

  @Test
  public void check_sftp_client_thread_safety() throws IOException {
    sftpServer.createDirectory("/sub-sub-dir");
    String content = new String(content("files/file-in-root"));
    for (int i = 0; i < 200; i++) {
      sftpServer.putFile("/sub-sub-dir/file-" + i, content, UTF_8);
    }
    SftpClient sftp = workingSftpClient();

    List<String> before = sftp.listDirectory("/sub-sub-dir");
    before.stream().parallel()
      .forEach(filename -> sftp.delete("sub-sub-dir/" + filename));

    List<String> after = sftp.listDirectory("/sub-sub-dir");
    assertThat(after).isEmpty();
  }

  @Test
  public void reconnect_session__when_open_channel_throws_exception() {
    int[] connected = {0};
    CustomJSch jsch = new CustomJSch() {
      @Override
      public Session getSession(String username, String host, int port) throws JSchException {
        return new DummySession() {
          @Override
          public void connect() {
            connected[0]++;
          }

          @Override
          public Channel openChannel(String type) {
            if (type.equals("sftp")) {
              return new DummyChannelSftp() {
                @Override
                public void connect() throws JSchException {
                  if (connected[0] == 1) throw new JSchException("Suppose to be thrown");
                }
              };
            } else if (type.equals("exec")) {
              return new DummyChannelExec();
            } else {
              return null;
            }
          }
        };
      }
    };

    SftpClient sftp = sftpClient().privateKey(content("files/private-key")).jsch(jsch).build();
    sftp.connect();

    sftp.listDirectory(".");

    assertThat(connected[0]).isEqualTo(2);
  }

  private static SftpClient workingSftpClient() {
    return sftpClient()
      .host("localhost")
      .port(port)
      .username("user")
      .privateKey(content("files/private-key"))
      .build();
  }

  @SneakyThrows
  // Gets file content from file system
  private static String content(File file) {
    return Files.readString(Paths.get(file.toURI()));
  }

  @SneakyThrows
  // Gets file content from classpath
  private static byte[] content(String fileName) {
    return resource(fileName).openStream().readAllBytes();
  }

  @SneakyThrows
  private static URI uri(String fileName) {
    return resource(fileName).toURI();
  }

  @SneakyThrows
  private static URL resource(String fileName) {
    return SftpClientTest.class.getResource("/" + fileName);
  }
}