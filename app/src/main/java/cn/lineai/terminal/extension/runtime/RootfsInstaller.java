package cn.lineai.terminal.extension.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/**
 * Extracts the embedded Ubuntu Base image on first install or explicit
 * reinstall and applies the host-side overlay configuration. A reinstall
 * preserves {@code /root/workspace}; every other rootfs location is replaced
 * wholesale. The image is verified against a pinned SHA-256 digest before the
 * new rootfs is committed.
 */
public final class RootfsInstaller {

    /**
     * Receives coarse-grained installation progress.
     */
    public interface Progress {

        /**
         * Reports progress toward a fully installed rootfs.
         *
         * @param percent completion percentage between 0 and 100
         * @param message human-readable description of the current step
         */
        void onProgress(int percent, String message);
    }

    static final String UBUNTU_ASSET = "runtime/ubuntu-22.04.5-base-arm64.targz";
    static final String UBUNTU_SHA256 =
            "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f";
    static final long UBUNTU_SIZE = 27_671_306L;
    static final String ROOTFS_VERSION = "1";

    private final Context context;
    private final RuntimePaths paths;

    /**
     * Creates the installer.
     *
     * @param context context resolved to its application instance
     * @param paths runtime path layout providing asset and rootfs locations
     */
    public RootfsInstaller(Context context, RuntimePaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    /**
     * Checks the on-disk version marker and the presence of {@code /bin/bash}
     * in the rootfs.
     *
     * @return true when a rootfs of the expected version is installed
     */
    public boolean isInstalled() {
        if (!paths.rootfsMarker().isFile()) {
            return false;
        }
        try {
            String marker = new String(Files.readAllBytes(paths.rootfsMarker().toPath()),
                    StandardCharsets.UTF_8);
            return marker.contains("version=" + ROOTFS_VERSION)
                    && new File(paths.rootfsDir(), "bin/bash").isFile();
        } catch (IOException error) {
            return false;
        }
    }

    /**
     * Extracts the image into a staging directory, swaps it into place while
     * preserving {@code /root/workspace}, applies the overlay configuration and
     * writes the version marker. The staging directory is removed on any failure.
     *
     * @param dnsServers IPv4 resolver addresses written into the guest network
     *                   configuration
     * @param progress optional progress listener covering the whole install
     * @throws IOException when extraction, digest verification or the swap fails
     */
    public void install(List<String> dnsServers, Progress progress) throws IOException {
        report(progress, 0, "准备安装目录");
        File ubuntuDir = paths.ubuntuDir();
        mkdirs(ubuntuDir);
        File tempDir = paths.rootfsTempDir();
        deleteRecursively(tempDir);
        mkdirs(tempDir);
        try {
            report(progress, 1, "解压 Ubuntu 22.04.5 LTS");
            extractUbuntu(tempDir, progress);

            report(progress, 86, "切换新环境");
            preserveWorkspace();
            deleteRecursively(paths.rootfsDir());
            if (!tempDir.renameTo(paths.rootfsDir())) {
                throw new IOException("Cannot move extracted rootfs into place");
            }
            restoreWorkspace();

            report(progress, 90, "写入运行配置");
            RootfsOverlay.apply(context, paths.rootfsDir(), dnsServers,
                    (percent, message) -> report(progress, 90 + percent / 10, message));
            writeMarker();
            report(progress, 100, "Ubuntu 环境安装完成");
        } catch (IOException | RuntimeException error) {
            deleteRecursively(tempDir);
            throw error;
        }
    }

    /**
     * Removes the rootfs directory and the version marker.
     */
    public void uninstall() {
        deleteRecursively(paths.rootfsDir());
        deleteRecursively(paths.rootfsMarker());
    }

    /**
     * Returns the total on-disk size of the installed rootfs.
     *
     * @return size in bytes
     */
    public long installedSizeBytes() {
        return sizeOf(paths.rootfsDir());
    }

    /** Streams the image asset through SHA-256 and the tar extractor, verifying the digest. */
    private void extractUbuntu(File destination, Progress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 is not available", error);
        }
        TarGzExtractor extractor = new TarGzExtractor((entries, compressedBytes, currentEntry) -> {
            int percent = (int) Math.min(85L, 2L + compressedBytes * 84L / UBUNTU_SIZE);
            report(progress, percent, "解压 Ubuntu：" + shorten(currentEntry));
        });
        try (InputStream asset = context.getAssets().open(UBUNTU_ASSET)) {
            java.security.DigestInputStream checked = new java.security.DigestInputStream(asset,
                    digest);
            extractor.extract(checked, destination);
        }
        String actual = toHex(digest.digest());
        if (!UBUNTU_SHA256.equalsIgnoreCase(actual)) {
            throw new IOException("Ubuntu image checksum mismatch: " + actual);
        }
    }

    /** Moves /root/workspace aside (rename, falling back to a copy) before the swap. */
    private void preserveWorkspace() throws IOException {
        File workspace = new File(paths.rootfsDir(), "root/workspace");
        if (!workspace.isDirectory()) {
            return;
        }
        deleteRecursively(paths.workspaceBackupDir());
        mkdirs(paths.workspaceBackupDir().getParentFile());
        if (!workspace.renameTo(paths.workspaceBackupDir())) {
            copyRecursively(workspace, paths.workspaceBackupDir());
        }
    }

    /** Restores the workspace backup into the freshly extracted rootfs. */
    private void restoreWorkspace() throws IOException {
        File backup = paths.workspaceBackupDir();
        if (!backup.isDirectory()) {
            return;
        }
        File target = new File(paths.rootfsDir(), "root/workspace");
        deleteRecursively(target);
        mkdirs(target.getParentFile());
        if (!backup.renameTo(target)) {
            copyRecursively(backup, target);
            deleteRecursively(backup);
        }
    }

    /** Writes the marker file recording version, digest and installation time. */
    private void writeMarker() throws IOException {
        mkdirs(paths.rootfsMarker().getParentFile());
        try (java.io.OutputStream out = new java.io.FileOutputStream(paths.rootfsMarker())) {
            String text = "version=" + ROOTFS_VERSION + "\nsha256=" + UBUNTU_SHA256
                    + "\ninstalledAt=" + System.currentTimeMillis() + "\n";
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Invokes the listener when present. */
    private static void report(Progress progress, int percent, String message) {
        if (progress != null) {
            progress.onProgress(percent, message);
        }
    }

    /** Creates a directory, tolerating concurrent creation. */
    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    /** Deletes a file tree, never following symbolic links. */
    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Copies a file tree, replacing existing targets. */
    private static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            mkdirs(target);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        mkdirs(target.getParentFile());
        Files.copy(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Sums the file sizes of a tree; symbolic links count once, not their targets. */
    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    /** Truncates long entry names for the progress display. */
    private static String shorten(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 48 ? value : "…" + value.substring(value.length() - 46);
    }

    /** Renders a digest as lowercase hexadecimal. */
    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }
}
