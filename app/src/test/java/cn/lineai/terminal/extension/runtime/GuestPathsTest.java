package cn.lineai.terminal.extension.runtime;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void mapsSharedStoragePrefixesToTheExternalRoot() throws Exception {
        File external = Files.createTempDirectory("guest-ext").toFile();
        Files.createDirectories(new File(external, "Download").toPath());
        Files.write(new File(external, "Download/note.txt").toPath(), "note".getBytes());
        GuestPaths paths = new GuestPaths(rootfs, () -> external);

        assertTrue(GuestPaths.isExternalPath("/sdcard/Download/note.txt"));
        assertEquals(new File(external, "Download/note.txt").getCanonicalPath(),
                paths.resolve("/sdcard/Download/note.txt").getCanonicalPath());
        assertEquals(new File(external, "Download/note.txt").getCanonicalPath(),
                paths.resolve("/storage/emulated/0/Download/note.txt").getCanonicalPath());
        assertEquals(new File(external, "Download/note.txt").getCanonicalPath(),
                paths.resolve("/storage/self/primary/Download/note.txt").getCanonicalPath());
        assertEquals("/sdcard/Download/note.txt",
                paths.toGuest(new File(external, "Download/note.txt")));
    }

    @Test
    public void rejectsSharedStorageSymlinksThatEscapeTheVolume() throws Exception {
        File external = Files.createTempDirectory("guest-ext-escape").toFile();
        File outside = Files.createTempDirectory("guest-outside").toFile();
        Files.createSymbolicLink(new File(external, "escape").toPath(),
                outside.toPath());
        GuestPaths paths = new GuestPaths(rootfs, () -> external);
        try {
            paths.resolve("/sdcard/escape/secret.txt");
            fail("expected the shared-storage symlink escape to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
    }

    @Test
    public void keepsSharedStorageInsideTheRootfsWhenNoVolumeIsMounted() throws Exception {
        GuestPaths paths = new GuestPaths(rootfs);
        assertFalse(GuestPaths.isExternalPath("/root/workspace"));
        assertTrue(paths.resolve("/sdcard/x").getPath().startsWith(rootfs.getPath()));
    }

    @Test
    public void reportsHostPathsInsideTheRootfsAsGuestPaths() throws Exception {
        GuestPaths paths = new GuestPaths(rootfs);
        assertEquals("/root/workspace", paths.toGuest(new File(rootfs, "root/workspace")));
        assertNull(paths.toGuest(new File(rootfs.getParentFile(), "outside")));
        assertNotNull(paths.resolve("/root/workspace"));
    }
}
