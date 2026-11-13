package cn.lineai.terminal.extension.runtime;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GuestPathsTest {

    private File rootfs;

    @Before
    public void setUp() throws Exception {
        rootfs = Files.createTempDirectory("guest-paths").toFile();
        Files.createDirectories(new File(rootfs, "usr/bin").toPath());
        Files.createDirectories(new File(rootfs, "root/workspace").toPath());
        Files.write(new File(rootfs, "usr/bin/bash").toPath(), "bash".getBytes());
    }

    @Test
    public void normalizesAndClampsParentTraversal() {
        assertEquals("/root/workspace", GuestPaths.normalize("/root/workspace"));
        assertEquals("/root", GuestPaths.normalize("/root/../root/."));
        assertEquals("/", GuestPaths.normalize("/../../"));
        assertEquals("/a/b", GuestPaths.normalize("/a/./b/"));
        try {
            GuestPaths.normalize("relative/path");
            fail("relative paths must be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期抛出异常
        }
    }

    @Test
    public void resolvesRelativeSymlinksInsideTheRootfs() throws Exception {
        Path bin = new File(rootfs, "bin").toPath();
        Files.createSymbolicLink(bin, java.nio.file.Paths.get("usr/bin"));
        GuestPaths paths = new GuestPaths(rootfs);
        File resolved = paths.resolve("/bin/bash");
        assertTrue(resolved.isFile());
        assertEquals(new File(rootfs, "usr/bin/bash").getCanonicalPath(),
                resolved.getCanonicalFile().getPath());
    }

    @Test
    public void resolvesAbsoluteSymlinksInsideTheRootfs() throws Exception {
        Path bin = new File(rootfs, "bin").toPath();
        Files.createSymbolicLink(bin, java.nio.file.Paths.get("/usr/bin"));
        GuestPaths paths = new GuestPaths(rootfs);
        File resolved = paths.resolve("/bin/bash");
        assertTrue(resolved.isFile());
        assertEquals(new File(rootfs, "usr/bin/bash").getCanonicalPath(),
                resolved.getCanonicalFile().getPath());
    }

    @Test
    public void reportsHostPathsInsideTheRootfsAsGuestPaths() throws Exception {
        GuestPaths paths = new GuestPaths(rootfs);
        assertEquals("/root/workspace", paths.toGuest(new File(rootfs, "root/workspace")));
        assertNull(paths.toGuest(new File(rootfs.getParentFile(), "outside")));
        assertNotNull(paths.resolve("/root/workspace"));
    }
}
