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
 * 首次安装或显式重装时解压内置 Ubuntu Base 镜像并写入 overlay 配置。
 * 重装保留 /root/workspace，其余内容全部替换。
 */
public final class RootfsInstaller {

    public interface Progress {
        void onProgress(int percent, String message);
    }

    static final String UBUNTU_ASSET = "runtime/ubuntu-22.04.5-base-arm64.targz";
    static final String UBUNTU_SHA256 =
            "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f";
    static final long UBUNTU_SIZE = 27_671_306L;
    static final String ROOTFS_VERSION = "1";

    private final Context context;
    private final RuntimePaths paths;

    public RootfsInstaller(Context context, RuntimePaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

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

    public void uninstall() {
        deleteRecursively(paths.rootfsDir());
        deleteRecursively(paths.rootfsMarker());
    }

    public long installedSizeBytes() {
        return sizeOf(paths.rootfsDir());
    }

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

    private void writeMarker() throws IOException {
        mkdirs(paths.rootfsMarker().getParentFile());
        try (java.io.OutputStream out = new java.io.FileOutputStream(paths.rootfsMarker())) {
            String text = "version=" + ROOTFS_VERSION + "\nsha256=" + UBUNTU_SHA256
                    + "\ninstalledAt=" + System.currentTimeMillis() + "\n";
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void report(Progress progress, int percent, String message) {
        if (progress != null) {
            progress.onProgress(percent, message);
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

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

    private static String shorten(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 48 ? value : "…" + value.substring(value.length() - 46);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }
}
