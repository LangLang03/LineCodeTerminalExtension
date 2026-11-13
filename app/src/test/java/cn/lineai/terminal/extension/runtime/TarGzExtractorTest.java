package cn.lineai.terminal.extension.runtime;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TarGzExtractorTest {

    @Test
    public void extractsFilesDirectoriesSymlinksHardlinksAndModes() throws Exception {
        Path root = Files.createTempDirectory("tar-extract");
        File destination = new File(root.toFile(), "rootfs");
        byte[] archive = tarGz(
                dir("./usr/", 0755),
                dir("./usr/bin/", 0755),
                dir("./usr/lib/", 0755),
                file("./usr/bin/hello", "hello world\n", 0755),
                symlink("./usr/bin/hi", "hello"),
                symlink("./etc-link", "usr/lib"),
                hardlink("./usr/bin/alias", "usr/bin/hello"),
                file("./usr/lib/libdata.txt", "data", 0644)
        );
        int entries = new TarGzExtractor(null).extract(input(archive), destination);
        assertEquals(8, entries);

        File hello = new File(destination, "usr/bin/hello");
        assertTrue(hello.isFile());
        assertTrue(hello.canExecute());
        assertEquals("hello world\n", read(hello));

        Path hi = new File(destination, "usr/bin/hi").toPath();
        assertTrue(Files.isSymbolicLink(hi));
        assertEquals("hello", Files.readSymbolicLink(hi).toString());

        Path etcLink = new File(destination, "etc-link").toPath();
        assertTrue(Files.isSymbolicLink(etcLink));
        assertEquals("usr/lib", Files.readSymbolicLink(etcLink).toString());

        File alias = new File(destination, "usr/bin/alias");
        assertTrue(alias.isFile());
        assertEquals("hello world\n", read(alias));
    }

    @Test
    public void rejectsParentTraversal() throws Exception {
        Path root = Files.createTempDirectory("tar-escape");
        File destination = new File(root.toFile(), "rootfs");
        byte[] archive = tarGz(file("../evil.txt", "boom", 0644));
        try {
            new TarGzExtractor(null).extract(input(archive), destination);
            fail("expected traversal rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsafe"));
        }
        assertFalse(new File(root.toFile(), "evil.txt").exists());
    }

    @Test
    public void rejectsAbsoluteEntryPaths() throws Exception {
        Path root = Files.createTempDirectory("tar-absolute");
        File destination = new File(root.toFile(), "rootfs");
        byte[] archive = tarGz(file("/tmp/evil.txt", "boom", 0644));
        try {
            new TarGzExtractor(null).extract(input(archive), destination);
            fail("expected absolute path rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unsafe"));
        }
    }

    @Test
    public void absoluteSymlinkStaysInsideTheExtractionRoot() throws Exception {
        Path root = Files.createTempDirectory("tar-symlink");
        File destination = new File(root.toFile(), "rootfs");
        byte[] archive = tarGz(
                dir("./inside/", 0755),
                symlink("./link", "/inside"),
                file("./link/written.txt", "inside", 0644)
        );
        new TarGzExtractor(null).extract(input(archive), destination);
        // 绝对符号链接必须以解压根目录为基准解析，而不是宿主根目录。
        File written = new File(destination, "inside/written.txt");
        assertTrue(written.isFile());
        assertEquals("inside", read(written));
        assertFalse(new File("/inside/written.txt").exists());
    }

    @Test
    public void extractsTheBundledUbuntuImage() throws Exception {
        File asset = new File("src/main/assets/runtime/ubuntu-22.04.5-base-arm64.targz");
        Assume.assumeTrue("bundled Ubuntu image is not available", asset.isFile());
        Path root = Files.createTempDirectory("tar-ubuntu");
        File destination = new File(root.toFile(), "rootfs");
        try (InputStream in = new FileInputStream(asset)) {
            int entries = new TarGzExtractor(null).extract(in, destination);
            assertTrue("expected a full Ubuntu Base image, got " + entries + " entries",
                    entries > 3000);
        }
        assertTrue(new File(destination, "bin/bash").isFile());
        assertTrue(new File(destination, "usr/bin/apt").isFile());
        Path bin = new File(destination, "bin").toPath();
        assertTrue(Files.isSymbolicLink(bin));
        assertEquals("usr/bin", Files.readSymbolicLink(bin).toString());
        assertTrue(new File(destination, "usr/bin/perl").isFile());
        assertTrue(new File(destination, "usr/bin/perl5.34.0").isFile());
        assertFalse(Files.isSymbolicLink(new File(destination, "usr/bin/perl").toPath()));
    }

    private static InputStream input(byte[] data) {
        return new java.io.ByteArrayInputStream(data);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] tarGz(TarEntry... entries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (TarEntry entry : entries) {
            raw.write(header(entry.name, entry.size, entry.mode, entry.type, entry.link));
            if (entry.type == '0') {
                raw.write(entry.data);
                raw.write(new byte[padding(entry.data.length)]);
            }
        }
        raw.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(raw.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static TarEntry file(String name, String content, int mode) {
        return new TarEntry(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                mode, '0', "");
    }

    private static TarEntry dir(String name, int mode) {
        return new TarEntry(name, new byte[0], mode, '5', "");
    }

    private static TarEntry symlink(String name, String target) {
        return new TarEntry(name, new byte[0], 0777, '2', target);
    }

    private static TarEntry hardlink(String name, String target) {
        return new TarEntry(name, new byte[0], 0777, '1', target);
    }

    private static int padding(long size) {
        return (int) ((512 - (size % 512)) % 512);
    }

    private static byte[] header(String name, long size, int mode, char type, String link) {
        byte[] header = new byte[512];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        putString(header, 157, 100, link);
        putString(header, 257, 6, "ustar");
        header[263] = '0';
        header[264] = '0';
        long checksum = 0;
        for (byte value : header) {
            checksum += value & 0xff;
        }
        putOctal(header, 148, 7, checksum);
        header[155] = ' ';
        return header;
    }

    private static void putString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String digits = String.format("%0" + (length - 1) + "o", value);
        byte[] bytes = digits.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
    }

    private static final class TarEntry {
        final String name;
        final byte[] data;
        final int mode;
        final char type;
        final String link;

        final long size;

        TarEntry(String name, byte[] data, int mode, char type, String link) {
            this.name = name;
            this.data = data;
            this.mode = mode;
            this.type = type;
            this.link = link;
            this.size = data.length;
        }
    }
}
