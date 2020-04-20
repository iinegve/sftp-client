package org.iinegve.sftp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.stefanbirkner.fakesftpserver.rule.FakeSftpServerRule;
import com.jcraft.jsch.Session;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SftpClientTest {

  private static final int port = 2000;

  @Rule
  public final FakeSftpServerRule sftpServer = new FakeSftpServerRule()
      .setPort(port)
      .addUser("user", "");

  @Before
  public void setUp() throws Exception {
    sftpServer.createDirectory("/subdir");

    sftpServer.putFile("/file-in-root", content("files/file-in-root"), UTF_8);
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
    SftpClient sftp = workingSftpClient();
    sftp.connect();
    Session jschSessionBefore = readJschSession(sftp);
    sftp.connect();
    Session jschSessionAfter = readJschSession(sftp);

    assertThat(jschSessionBefore).isNotSameAs(jschSessionAfter);
    assertThat(jschSessionBefore.isConnected()).isFalse();
  }

  @SuppressWarnings("unchecked")
  private Session readJschSession(SftpClient sftp) {
    try {
      Field f = SftpClient.class.getDeclaredField("jschSession");
      f.setAccessible(true);
      return ((ThreadLocal<Session>) f.get(sftp)).get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
    File firstFile = sftp
        .download("list-files/sublist-files/first-file", new File(tempDir, "first-file"));

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
    String content = content("files/file-in-root");
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
  public void check_sftp_client_thread_safety() throws IOException {
    sftpServer.createDirectory("/sub-sub-dir");
    String content = content("files/file-in-root");
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


  private static SftpClient workingSftpClient() {
    return SftpClient.sftpClient()
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
  private static String content(String fileName) {
    return new String(resource(fileName).openStream().readAllBytes());
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