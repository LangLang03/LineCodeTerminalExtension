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
 * Owns the lifecycle of the embedded PRoot runtime and the Ubuntu rootfs.
 * Installation is idempotent and serialized behind an internal lock, so it is
 * safe to trigger from both the IPC service and the status activity. Readiness
 * and progress are published as monitor-guarded state, letting callers either
 * poll the accessors or block in {@link #awaitReady(long)}.
 */
public final class ProotEnvironment {

    /**
     * Receives coarse-grained installation progress.
     */
    public interface Progress {

        /**
         * Reports progress toward a fully installed environment.
         *
         * @param percent completion percentage between 0 and 100
         * @param message human-readable description of the current step
         */
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

    /**
     * Creates the environment bound to the application context.
     *
     * @param context context resolved to its application instance
     */
    public ProotEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.paths = new RuntimePaths(this.context.getFilesDir());
        this.installer = new RootfsInstaller(this.context, paths);
        this.files = new GuestFileSystem(paths.rootfsDir(), paths.homePath());
        this.shell = new ShellRunner(paths);
    }

    /**
     * Returns the runtime path layout.
     *
     * @return the shared path layout
     */
    public RuntimePaths paths() {
        return paths;
    }

    /**
     * Returns the guest file facade backing the AIDL file operations.
     *
     * @return the guest file facade
     */
    public GuestFileSystem files() {
        return files;
    }

    /**
     * Returns the guest command executor.
     *
     * @return the shell runner
     */
    public ShellRunner shellRunner() {
        return shell;
    }

    /**
     * Checks whether the device can execute the embedded arm64 PRoot binary.
     *
     * @return true when the device reports an {@code arm64-v8a} ABI
     */
    public boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports readiness; the in-memory flag and the on-disk rootfs marker must
     * both agree so a wiped installation is never reported as usable.
     *
     * @return true when the guest environment is installed and usable
     */
    public boolean isReady() {
        synchronized (stateLock) {
            if (!ready) {
                return false;
            }
        }
        return installer.isInstalled();
    }

    /**
     * Reports whether an installation is currently running.
     *
     * @return true while an install is in progress
     */
    public boolean isInstalling() {
        synchronized (stateLock) {
            return installing;
        }
    }

    /**
     * Returns the current installation percentage.
     *
     * @return progress percentage between 0 and 100
     */
    public int progressPercent() {
        synchronized (stateLock) {
            return percent;
        }
    }

    /**
     * Returns the description of the current installation step.
     *
     * @return current progress message, empty when none
     */
    public String progressMessage() {
        synchronized (stateLock) {
            return message;
        }
    }

    /**
     * Returns the last fatal installation error.
     *
     * @return error description, or null when no failure has occurred
     */
    public String lastError() {
        synchronized (stateLock) {
            return lastError;
        }
    }

    /**
     * Returns the total on-disk size of the installed rootfs.
     *
     * @return size in bytes
     */
    public long installedSizeBytes() {
        return installer.installedSizeBytes();
    }

    /**
     * Returns the provider descriptor JSON document.
     *
     * @return the JSON document served by {@code getProviderInfo()}
     */
    public String providerInfoJson() {
        return ProviderInfo.json(paths.homePath());
    }

    /**
     * Installs the environment if necessary and blocks until done; returns
     * immediately when the environment is already ready.
     *
     * @param listener optional progress listener
     * @throws IOException when runtime assets cannot be written or the image
     *                     fails verification
     */
    public void ensureInstalled(Progress listener) throws IOException {
        install(false, listener);
    }

    /**
     * Forces a full re-extraction of the image; the contents of
     * {@code /root/workspace} are preserved across the swap.
     *
     * @param listener optional progress listener
     * @throws IOException when the reinstall fails
     */
    public void reinstall(Progress listener) throws IOException {
        install(true, listener);
    }

    /**
     * Schedules installation on the shared worker pool unless the environment
     * is already ready or an install is running; failures are recorded in
     * {@link #lastError()} for the status activity.
     */
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
                // The failure state is recorded in lastError and shown by the
                // status activity.
            }
        });
    }

    /**
     * Blocks until the environment is ready, the installation fails or the
     * timeout elapses; kicks off a background install when none is running.
     *
     * @param timeoutMs maximum wait duration; zero or negative disables waiting
     * @return true when ready, false on timeout, failure or interruption
     */
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

    /** Serialized install entry point; see {@link #ensureInstalled} and {@link #reinstall}. */
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

    /** Re-writes guest network configuration for the current DNS servers; best-effort. */
    private void refreshNetworkConfig() {
        try {
            RootfsOverlay.refreshNetwork(context, paths.rootfsDir(), dnsServers());
        } catch (IOException ignored) {
            // Network configuration is best-effort; the environment itself stays usable.
        }
    }

    /** Copies PRoot binaries and libraries from assets into the runtime directory. */
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

    /** Copies one asset and applies the requested POSIX permission bits. */
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

    /** Publishes install progress and wakes threads waiting in {@link #awaitReady}. */
    private void update(int newPercent, String newMessage, Progress listener) {
        synchronized (stateLock) {
            percent = Math.max(0, Math.min(100, newPercent));
            message = newMessage == null ? "" : newMessage;
            stateLock.notifyAll();
        }
        report(listener, percent, message);
    }

    /** Invokes the listener when present. */
    private void report(Progress listener, int value, String text) {
        if (listener != null) {
            listener.onProgress(value, text);
        }
    }

    /**
     * Collects the IPv4 DNS servers of the active network, falling back to
     * public resolvers when unavailable. IPv6 servers are dropped because the
     * container network stack has no reliable IPv6 connectivity and IPv6
     * resolvers make apt and curl fail immediately.
     *
     * @return deduplicated IPv4 resolver addresses
     */
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
            // Without ACCESS_NETWORK_STATE or an active network, use fallback DNS.
        }
        if (result.isEmpty()) {
            result.add("223.5.5.5");
            result.add("1.1.1.1");
            result.add("8.8.8.8");
        }
        return result;
    }

    /** Creates a directory, tolerating concurrent creation. */
    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    /** Returns the message of a throwable, falling back to its string form. */
    private static String describe(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.toString() : text;
    }
}
