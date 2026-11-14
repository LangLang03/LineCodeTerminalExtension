package cn.lineai.terminal.extension.runtime;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Writes the host-side overlay onto an already extracted Ubuntu Base image:
 * DNS with IPv4-only resolver policy, APT sandbox configuration, shell profile,
 * the workspace directory, CA certificates, and static busybox links providing
 * applets missing from the base image (vi, less, wget, unzip and others).
 */
final class RootfsOverlay {

    private static final String[] BUSYBOX_APPLETS = {
            "vi", "less", "wget", "nc", "unzip", "zip", "xz", "unxz", "nslookup", "telnet"
    };

    /**
     * Receives coarse-grained overlay installation progress.
     */
    interface Progress {

        /**
         * Reports progress toward a fully configured rootfs.
         *
         * @param percent completion percentage between 0 and 100
         * @param message human-readable description of the current step
         */
        void onProgress(int percent, String message);
    }

    /** Suppresses instantiation; all members are static. */
    private RootfsOverlay() {
    }

    /**
     * Applies the full overlay to a rootfs; used during installation only.
     *
     * @param context context supplying the bundled assets
     * @param rootfs host directory of the extracted rootfs
     * @param dnsServers IPv4 resolver addresses to publish
     * @param progress optional progress listener
     * @throws IOException when configuration files or assets cannot be written
     */
    static void apply(Context context, File rootfs, List<String> dnsServers, Progress progress)
            throws IOException {
        report(progress, 0, "配置网络与软件源");
        refreshNetwork(context, rootfs, dnsServers);
        ensureExternalMountPoints(rootfs);
        writeText(new File(rootfs, "etc/profile.d/linecode.sh"), PROFILE);
        writeText(new File(rootfs, "root/.bashrc"), BASHRC);
        writeText(new File(rootfs, "root/.profile"), PROFILE);

        File workspace = new File(rootfs, "root/workspace");
        mkdirs(workspace);
        File tmp = new File(rootfs, "tmp");
        mkdirs(tmp);
        chmod(tmp, 0777);
        chmod(workspace, 0700);

        report(progress, 20, "安装基础命令（busybox）");
        File busyboxDir = new File(rootfs, "usr/local/bin");
        mkdirs(busyboxDir);
        File busybox = new File(busyboxDir, "busybox");
        copyAsset(context.getAssets(), "runtime/busybox", busybox);
        chmod(busybox, 0755);
        for (String applet : BUSYBOX_APPLETS) {
            File link = new File(busyboxDir, applet);
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), Paths.get("busybox"));
        }

        report(progress, 55, "安装 CA 证书");
        File certs = new File(rootfs, "etc/ssl/certs");
        mkdirs(certs);
        File ca = new File(certs, "ca-certificates.crt");
        copyAsset(context.getAssets(), "runtime/cacert.pem", ca);
        chmod(ca, 0644);

        report(progress, 80, "写入环境配置");
        mkdirs(new File(rootfs, "root/.config"));
        report(progress, 100, "环境配置完成");
    }

    /**
     * Creates the guest directories that later receive the shared-storage bind mounts.
     * Idempotent and safe to call for an already installed rootfs.
     *
     * @param rootfs host directory of the extracted rootfs
     * @throws IOException when a mount point directory cannot be created
     */
    static void ensureExternalMountPoints(File rootfs) throws IOException {
        mkdirs(new File(rootfs, "sdcard"));
        mkdirs(new File(rootfs, "storage/emulated/0"));
        mkdirs(new File(rootfs, "root/sdcard"));
    }

    /**
     * Rewrites the guest network configuration; safe to call for an existing
     * rootfs without reinstalling, for example after the host network changed.
     *
     * @param context context supplying the bundled assets
     * @param rootfs host directory of the extracted rootfs
     * @param dnsServers IPv4 resolver addresses to publish
     * @throws IOException when the configuration files cannot be written
     */
    static void refreshNetwork(Context context, File rootfs, List<String> dnsServers)
            throws IOException {
        writeText(new File(rootfs, "etc/resolv.conf"), resolvConf(dnsServers));
        writeText(new File(rootfs, "etc/apt/apt.conf.d/99linecode"), APT_CONF);
        writeText(new File(rootfs, "etc/gai.conf"), GAI_CONF);
    }

    /**
     * Builds the {@code resolv.conf} content from IPv4 resolver addresses,
     * falling back to public resolvers when none remain.
     *
     * @param dnsServers candidate resolver addresses
     * @return the resolver configuration text
     */
    static String resolvConf(List<String> dnsServers) {
        StringBuilder out = new StringBuilder();
        if (dnsServers != null) {
            for (String server : dnsServers) {
                if (server == null || server.trim().isEmpty() || server.indexOf(':') >= 0) {
                    // IPv6 resolvers are unreachable from this container network.
                    continue;
                }
                out.append("nameserver ").append(server.trim()).append('\n');
            }
        }
        if (out.length() == 0) {
            out.append("nameserver 223.5.5.5\n")
                    .append("nameserver 1.1.1.1\n")
                    .append("nameserver 8.8.8.8\n");
        }
        return out.toString();
    }

    /** Copies a bundled asset to the target path. */
    private static void copyAsset(AssetManager assets, String assetPath, File target)
            throws IOException {
        mkdirs(target.getParentFile());
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Writes UTF-8 text with 0644 permissions, creating parent directories. */
    private static void writeText(File target, String content) throws IOException {
        mkdirs(target.getParentFile());
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        chmod(target, 0644);
    }

    /** Creates a directory, tolerating concurrent creation. */
    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    /** Applies POSIX permission bits with a java.io executable-bit fallback. */
    private static void chmod(File file, int mode) {
        try {
            Path path = file.toPath();
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    java.nio.file.attribute.PosixFilePermissions.fromString(
                            toPosixMode(mode));
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | RuntimeException ignored) {
            if ((mode & 0111) != 0) {
                //noinspection ResultOfMethodCallIgnored
                file.setExecutable(true, false);
            }
        }
    }

    /** Renders an octal mode as the nine-character POSIX permission string. */
    private static String toPosixMode(int mode) {
        StringBuilder out = new StringBuilder(9);
        out.append((mode & 0400) != 0 ? 'r' : '-');
        out.append((mode & 0200) != 0 ? 'w' : '-');
        out.append((mode & 0100) != 0 ? 'x' : '-');
        out.append((mode & 0040) != 0 ? 'r' : '-');
        out.append((mode & 0020) != 0 ? 'w' : '-');
        out.append((mode & 0010) != 0 ? 'x' : '-');
        out.append((mode & 0004) != 0 ? 'r' : '-');
        out.append((mode & 0002) != 0 ? 'w' : '-');
        out.append((mode & 0001) != 0 ? 'x' : '-');
        return out.toString();
    }

    /** Invokes the listener when present. */
    private static void report(Progress progress, int percent, String message) {
        if (progress != null) {
            progress.onProgress(percent, message);
        }
    }

    private static final String APT_CONF =
            "APT::Sandbox::User \"root\";\n"
                    + "Acquire::ForceIPv4 \"true\";\n";

    private static final String GAI_CONF =
            "# Prefer IPv4 addresses for getaddrinfo(); IPv6 is not usable in this container.\n"
                    + "precedence ::ffff:0:0/96 100\n";

    private static final String PROFILE =
            "export LANG=C.UTF-8\n"
                    + "export LC_ALL=C.UTF-8\n"
                    + "export TERM=xterm-256color\n"
                    + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                    + "export PS1='\\u@linecode:\\w\\$ '\n"
                    + "alias ll='ls -alF'\n"
                    + "alias la='ls -A'\n"
                    + "alias grep='grep --color=auto'\n";

    private static final String BASHRC =
            "[ -f /etc/profile.d/linecode.sh ] && . /etc/profile.d/linecode.sh\n";
}
