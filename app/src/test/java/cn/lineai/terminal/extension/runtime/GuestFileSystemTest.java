package cn.lineai.terminal.extension.runtime;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuestFileSystemTest {

    private File rootfs;
    private GuestFileSystem files;

    @Before
    public void setUp() throws Exception {
        rootfs = Files.createTempDirectory("guest-fs").toFile();
        Files.createDirectories(new File(rootfs, "root/workspace").toPath());
        files = new GuestFileSystem(rootfs, "/root/workspace");
    }

    @Test
    public void writesReadsAndChunksFiles() {
        assertTrue(files.writeFile("/root/workspace/hello.txt", bytes("hello world")));
        assertTrue(files.fileExists("/root/workspace/hello.txt"));
        assertEquals(11L, files.fileSize("/root/workspace/hello.txt"));
        assertArrayEquals(bytes("hello world"), files.readFile("/root/workspace/hello.txt"));
        assertTrue(files.writeFileChunk("/root/workspace/created-by-chunk.txt", 0L, bytes("x")));
        assertEquals("x", new String(files.readFile("/root/workspace/created-by-chunk.txt"),
                StandardCharsets.UTF_8));
    }

    @Test
    public void chunkReadsRespectOffsetsAndFileSize() {
        files.writeFile("/root/workspace/data.bin", bytes("0123456789"));
        assertArrayEquals(bytes("3456"), files.readFileChunk("/root/workspace/data.bin", 3L, 4));
        assertArrayEquals(bytes("89"), files.readFileChunk("/root/workspace/data.bin", 8L, 100));
        assertEquals(0, files.readFileChunk("/root/workspace/data.bin", 20L, 5).length);
        assertEquals(0, files.readFileChunk("/root/workspace/none.bin", 0L, 5).length);
    }

    @Test
    public void chunkWritesSupportOffsetAndAppend() {
        files.writeFileChunk("/root/workspace/chunk.txt", 0L, bytes("abc"));
        files.writeFileChunk("/root/workspace/chunk.txt", 3L, bytes("def"));
        files.writeFileChunk("/root/workspace/chunk.txt", -1L, bytes("!"));
        assertEquals("abcdef!", new String(files.readFile("/root/workspace/chunk.txt"),
                StandardCharsets.UTF_8));
    }

    @Test
    public void listsDirectoriesAndDetails() throws Exception {
        Files.createDirectories(new File(rootfs, "root/workspace/sub").toPath());
        files.writeFile("/root/workspace/a.txt", bytes("a"));
        String[] names = files.listDir("/root/workspace");
        assertEquals(2, names.length);
        String detailed = files.listDirDetailed("/root/workspace");
        assertTrue(detailed.startsWith("["));
        assertTrue(detailed.contains("\"name\":\"a.txt\""));
        assertTrue(detailed.contains("\"dir\":true"));
        assertTrue(detailed.contains("\"name\":\"sub\""));
        assertEquals("[]", files.listDirDetailed("/root/workspace/nope"));
    }

    @Test
    public void deletesFilesAndKeepsWritesInsideTheRootfs() throws Exception {
        files.writeFile("/root/workspace/remove.txt", bytes("x"));
        assertTrue(files.deleteFile("/root/workspace/remove.txt"));
        assertFalse(files.fileExists("/root/workspace/remove.txt"));

        // guest 绝对路径映射到 rootfs 内，不会触碰宿主文件。
        assertTrue(files.writeFile("/etc/hostname-linecode-test", bytes("guest")));
        assertTrue(new File(rootfs, "etc/hostname-linecode-test").isFile());
        assertEquals("guest", new String(files.readFile("/etc/hostname-linecode-test"),
                StandardCharsets.UTF_8));

        // 契约要求绝对路径，相对路径被拒绝。
        assertFalse(files.writeFile("../../outside", bytes("escape")));
        assertEquals(0, files.readFile("relative").length);

        // 绝对符号链接仍在 guest rootfs 内解析，不会落到宿主。
        Files.createDirectories(new File(rootfs, "tmp").toPath());
        Files.createSymbolicLink(new File(rootfs, "escape").toPath(),
                java.nio.file.Paths.get("/tmp/linecode-outside"));
        assertTrue(files.writeFile("/escape/written.txt", bytes("inside")));
        assertTrue(new File(rootfs, "tmp/linecode-outside/written.txt").isFile());
        assertFalse(new File("/tmp/linecode-outside/written.txt").exists());
    }

    @Test
    public void symlinksAreResolvedInsideTheRootfs() throws Exception {
        Files.createDirectories(new File(rootfs, "usr/bin").toPath());
        try (FileOutputStream out = new FileOutputStream(new File(rootfs, "usr/bin/tool"))) {
            out.write(bytes("tool"));
        }
        Files.createSymbolicLink(new File(rootfs, "bin").toPath(),
                java.nio.file.Paths.get("/usr/bin"));
        assertArrayEquals(bytes("tool"), files.readFile("/bin/tool"));
        assertEquals(4L, files.fileSize("/bin/tool"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
