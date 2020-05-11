# sftp-client

Simple wrapper on jsch to provide convenient API without low level operations like connect, disconnect, etc.

It works fine with relative paths. When sftp client connects, it assumes it's in `home` directory, which
suppose to be configured on server side. So, if sftp client connects to a server with following directories

    /root/home/dir1/file1 
    /root/home/dir1/file2 
    /root/home/dir2/file1
    /root/home/dir2/file2

it is assumed that default directory is `home`. Then, when  

    List<String> filenames = sftp.listDirectory("dir1");
    
Then `filenames` contains `file1`, `file2`    


### features

- supports reconnect on channel opening: it helps a lot with long living sessions as it's not possible
to identify in time that session is actually done - most of the time server just closes connection. And
so when on "live" session we're trying to open channel it throws exception saying it's not possible, 
because session is done. 

- supports retry operation: in case something doesn't work, there is a retry. At the moment it's not 
configurable, simply repeats the same operation (exception bulk deletion) 2 times more. Bulk deletion
is a bit more complex, because few files might already be deleted, thus repeat should happen without
successfully deleted files.

- support initialization with private key from in memory string, it doesn't create any temporary files 

To create an sftp client 

    SftpClient sftp = sftpClient()
      .host("localhost")
      .port(port)
      .username("user")
      .privateKey(content("private-key-file"))
      .build() 
      
Usage

    List<String> filenames = sftp.listDirectory("/sub-sub-dir");
    
    File f = sftp.download("remote-path", new File("target"))