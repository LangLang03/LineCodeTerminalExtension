package cn.lineai.terminal.extension.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理内置 PRoot 运行时与 Ubuntu rootfs 的生命周期。安装幂等且串行执行，
 * 可由 IPC 服务与状态页任意一方安全触发。
 */
public final class ProotEnvironment {

    public interface Progress {
        void onProgress(int percent, String message);
    }

    private static final String ASSET_PROOT = "runtime/proot";
    private static final String ASSET_LOADER = "runtime/proot-loader";
    private static final String ASSET_TALLOC = "runtime/libtalloc.so.2";
    private static final String ASSET_SHMEM = "runtime/libandroid-shmem.so";

    private final Context context;
    private final RuntimePaths paths;
    private final RootfsInstaller installer;
    private final GuestFileSystem files;
    private final ShellRunner shell;

    private final Object installLock = new Object();
    private final Object stateLock = new Object();
    private volatile boolean ready;
    private volatile boolean installing;
    private volatile int percent;
    private volatile String message = "";
    private volatile String lastError;

    public ProotEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.paths = new RuntimePaths(this.context.getFilesDir());
        this.installer = new RootfsInstaller(this.context, paths);
        this.files = new GuestFileSystem(paths.rootfsDir(), paths.homePath());
        this.shell = new ShellRunner(paths);
    }

    public RuntimePaths paths() {
        return paths;
    }

    public GuestFileSystem files() {
        return files;
    }

    public ShellRunner shellRunner() {
        return shell;
    }

    public boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    public boolean isReady() {
        synchronized (stateLock) {
            if (!ready) {
                return false;
            }
        }
        return installer.isInstalled();
    }

    public boolean isInstalling() {
        synchronized (stateLock) {
            return installing;
        }
    }

    public int progressPercent() {
        synchronized (stateLock) {
            return percent;
        }
    }

    public String progressMessage() {
        synchronized (stateLock) {
            return message;
        }
    }

    public String lastError() {
        synchronized (stateLock) {
            return lastError;
        }
    }

    public long installedSizeBytes() {
        return installer.installedSizeBytes();
    }

    public String providerInfoJson() {
        return ProviderInfo.json(paths.homePath());
    }

    /** 阻塞式安装；环境已就绪时立即返回。 */
    public void ensureInstalled(Progress listener) throws IOException {
        install(false, listener);
    }

    /** 显式重装；保留 /root/workspace 下的内容。 */
    public void reinstall(Progress listener) throws IOException {
        install(true, listener);
    }

    public void startBackgroundInstall() {
        synchronized (stateLock) {
            if (ready || installing) {
                return;
            }
        }
        WorkerPool.shared().submit(() -> {
            try {
                ensureInstalled(null);
            } catch (IOException error) {
                // 失败状态已记录，由状态页展示。
            }
        });
    }

    public boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (stateLock) {
            while (!ready) {
                if (lastError != null && !installing) {
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                if (!installing) {
                    startBackgroundInstall();
                }
                try {
                    stateLock.wait(Math.min(remaining, 200L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void install(boolean force, Progress listener) throws IOException {
        synchronized (installLock) {
            synchronized (stateLock) {
                if (!force && installer.isInstalled()) {
                    ready = true;
                    installing = false;
                    percent = 100;
                    message = "Ubuntu 环境已就绪";
                    lastError = null;
                    refreshNetworkConfig();
                    report(listener, 100, message);
                    return;
                }
                installing = true;
                lastError = null;
                percent = 0;
                message = "准备安装 PRoot 运行时";
            }
            try {
                copyProotRuntime(listener);
                installer.install(dnsServers(), (installPercent, installMessage) ->
                        update(5 + installPercent * 95 / 100, installMessage, listener));
                synchronized (stateLock) {
                    ready = true;
                    installing = false;
                    percent = 100;
                    message = "Ubuntu 环境已就绪";
                    stateLock.notifyAll();
                }
            } catch (IOException | RuntimeException error) {
                String detail = describe(error);
                synchronized (stateLock) {
                    ready = false;
                    installing = false;
                    lastError = detail;
                    message = "安装失败";
                    stateLock.notifyAll();
                }
                if (error instanceof IOException) {
                    throw (IOException) error;
                }
                throw new IOException(detail, error);
            }
        }
    }

    private void refreshNetworkConfig() {
        try {
            RootfsOverlay.refreshNetwork(context, paths.rootfsDir(), dnsServers());
        } catch (IOException ignored) {
            // Network configuration is best-effort; the environment itself stays usable.
        }
    }

    private void copyProotRuntime(Progress listener) throws IOException {
        mkdirs(paths.baseDir());
        mkdirs(paths.prootLibDir());
        mkdirs(paths.prootTmpDir());
        copyAsset(ASSET_PROOT, paths.prootBinary(), 0755);
        update(2, "写入 PRoot", listener);
        copyAsset(ASSET_LOADER, paths.prootLoader(), 0755);
        copyAsset(ASSET_TALLOC, new File(paths.prootLibDir(), "libtalloc.so.2"), 0644);
        copyAsset(ASSET_SHMEM, new File(paths.prootLibDir(), "libandroid-shmem.so"), 0644);
        update(5, "PRoot 运行时已就绪", listener);
    }

    private void copyAsset(String assetPath, File target, int mode) throws IOException {
        mkdirs(target.getParentFile());
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    java.nio.file.attribute.PosixFilePermissions.fromString(
                            mode == 0755 ? "rwxr-xr-x" : "rw-r--r--");
            java.nio.file.Files.setPosixFilePermissions(target.toPath(), permissions);
        } catch (IOException | RuntimeException ignored) {
            if (mode == 0755) {
                //noinspection ResultOfMethodCallIgnored
                target.setExecutable(true, false);
            }
        }
    }

    private void update(int newPercent, String newMessage, Progress listener) {
        synchronized (stateLock) {
            percent = Math.max(0, Math.min(100, newPercent));
            message = newMessage == null ? "" : newMessage;
            stateLock.notifyAll();
        }
        report(listener, percent, message);
    }

    private void report(Progress listener, int value, String text) {
        if (listener != null) {
            listener.onProgress(value, text);
        }
    }

    public List<String> dnsServers() {
        List<String> result = new ArrayList<>();
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                Network network = manager.getActiveNetwork();
                LinkProperties properties = network == null ? null
                        : manager.getLinkProperties(network);
                if (properties != null) {
                    for (java.net.InetAddress address : properties.getDnsServers()) {
                        String host = address.getHostAddress();
                        if (host == null || host.isEmpty() || host.indexOf(':') >= 0) {
                            // This container network stack only has reliable IPv4 connectivity;
                            // IPv6 resolvers make apt/curl fail immediately.
                            continue;
                        }
                        if (!result.contains(host)) {
                            result.add(host);
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            // 无 ACCESS_NETWORK_STATE 权限或无活动网络时，使用回退 DNS。
        }
        if (result.isEmpty()) {
            result.add("223.5.5.5");
            result.add("1.1.1.1");
            result.add("8.8.8.8");
        }
        return result;
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    private static String describe(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.toString() : text;
    }
}
