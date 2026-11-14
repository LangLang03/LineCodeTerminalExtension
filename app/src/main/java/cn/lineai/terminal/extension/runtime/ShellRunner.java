package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Executes commands inside the PRoot guest through {@code /bin/bash}. Each
 * invocation spawns one proot host process; stdout and stderr are merged and
 * streamed back to the caller as UTF-8 chunks.
 */
public final class ShellRunner {

    /**
     * Streaming callbacks for a guest command execution.
     */
    public interface Callback {

        /**
         * Delivers merged guest output decoded as UTF-8.
         *
         * @param text decoded output chunk
         */
        void onOutput(String text);

        /**
         * Delivers a diagnostic message (timeout, spawn failure, interruption).
         *
         * @param text human-readable failure description
         */
        void onError(String text);

        /**
         * Signals completion of the guest process.
         *
         * @param exitCode guest exit code; negative values indicate failure modes
         */
        void onComplete(int exitCode);
    }

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long MAX_TIMEOUT_MS = 30L * 60L * 1000L;

    private final RuntimePaths paths;
    private final Supplier<File> externalRoot;
    private final ExecutorService workers = WorkerPool.shared();

    /**
     * Creates a runner bound to the given runtime layout without shared-storage mounts.
     *
     * @param paths runtime path layout providing binary and rootfs locations
     */
    public ShellRunner(RuntimePaths paths) {
        this(paths, null);
    }

    /**
     * Creates a runner that bind-mounts the host shared volume into the guest whenever the
     * supplier returns a usable directory.
     *
     * @param paths runtime path layout providing binary and rootfs locations
     * @param externalRoot supplier of the shared storage root, or {@code null}
     */
    public ShellRunner(RuntimePaths paths, Supplier<File> externalRoot) {
        this.paths = paths;
        this.externalRoot = externalRoot;
    }

    /**
     * Assembles the full host argv for a guest command: PRoot flags, rootfs and
     * system mounts, a fixed guest environment, and {@code /bin/bash -lc}.
     *
     * @param command shell command line executed inside the guest
     * @param cwd guest working directory; the home directory is used when absent
     *            or invalid
     * @return the complete argv, starting with the proot binary
     */
    public List<String> buildCommand(String command, String cwd) {
        String guestCwd = normalizeCwd(cwd);
        List<String> argv = new ArrayList<>();
        argv.add(paths.prootBinary().getAbsolutePath());
        argv.add("--link2symlink");
        argv.add("-0");
        argv.add("--kill-on-exit");
        argv.add("-r");
        argv.add(paths.rootfsDir().getAbsolutePath());
        argv.add("-b");
        argv.add("/dev");
        argv.add("-b");
        argv.add("/proc");
        argv.add("-b");
        argv.add("/sys");
        argv.add("-b");
        argv.add("/dev/urandom:/dev/random");
        addExternalBinds(argv);
        argv.add("-w");
        argv.add(guestCwd);
        argv.add("/usr/bin/env");
        argv.add("-i");
        argv.add("HOME=/root");
        argv.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        argv.add("TERM=xterm-256color");
        argv.add("LANG=C.UTF-8");
        argv.add("LC_ALL=C.UTF-8");
        argv.add("TMPDIR=/tmp");
        argv.add("/bin/bash");
        argv.add("-lc");
        argv.add(command == null ? "" : command);
        return argv;
    }

    /**
     * Adds shared-storage bind mounts when the device permission is granted; the
     * guest then sees the same volume at {@code /sdcard}, {@code /storage/emulated/0}
     * and {@code /root/sdcard}.
     *
     * @param argv argv under construction
     */
    private void addExternalBinds(List<String> argv) {
        File external = externalRoot == null ? null : externalRoot.get();
        if (external == null || !external.isDirectory()) {
            return;
        }
        String path = external.getAbsolutePath();
        addBind(argv, path + ":" + ExternalStorage.GUEST_SDCARD);
        addBind(argv, path + ":" + ExternalStorage.GUEST_ANDROID_ROOT);
        addBind(argv, path + ":" + ExternalStorage.GUEST_HOME_LINK);
    }

    /**
     * Appends one {@code -b host:guest} mapping.
     *
     * @param argv argv under construction
     * @param mapping host-to-guest bind specification
     */
    private static void addBind(List<String> argv, String mapping) {
        argv.add("-b");
        argv.add(mapping);
    }

    /**
     * Builds the environment variables for the proot host process itself (as
     * opposed to the guest environment embedded in the argv).
     *
     * @return variables locating the loader, temporary directory and libraries
     */
    public java.util.Map<String, String> buildEnvironment() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("PROOT_LOADER", paths.prootLoader().getAbsolutePath());
        env.put("PROOT_TMP_DIR", paths.prootTmpDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", paths.prootLibDir().getAbsolutePath());
        return env;
    }

    /**
     * Runs a command in the guest and blocks until completion or timeout.
     *
     * @param command shell command line; an empty command is rejected
     * @param cwd guest working directory; the home directory is used when absent
     *            or invalid
     * @param timeoutMs wall-clock limit, clamped to 30 minutes; a non-positive
     *                  value selects the 30 second default
     * @param callback streaming callbacks; may be {@code null}
     * @return the guest exit code when non-negative; otherwise a sentinel:
     *         -1 when the command is empty or the process could not start,
     *         -2 when the timeout elapsed
     */
    public int execute(String command, String cwd, long timeoutMs, Callback callback) {
        if (command == null || command.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("command must not be empty");
            }
            return -1;
        }
        long timeout = timeoutMs > 0 ? Math.min(timeoutMs, MAX_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
        Process process = null;
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            ProcessBuilder builder = new ProcessBuilder(buildCommand(command, cwd));
            builder.redirectErrorStream(true);
            builder.environment().putAll(buildEnvironment());
            process = builder.start();
            final Process running = process;
            final AtomicBoolean done = finished;
            Future<?> reader = workers.submit(() -> pump(running, callback, done));
            boolean completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                done.set(true);
                ProcessTreeKiller.kill(process);
                reader.cancel(true);
                if (callback != null) {
                    callback.onError("命令执行超时（" + timeout + " ms）");
                }
                return -2;
            }
            try {
                reader.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The process has exited; a still-blocked reader returns at EOF shortly.
            }
            int exitCode = process.exitValue();
            if (callback != null) {
                callback.onComplete(exitCode);
            }
            return exitCode;
        } catch (IOException error) {
            if (callback != null) {
                callback.onError("无法启动 PRoot：" + describe(error));
            }
            return -1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (callback != null) {
                callback.onError("命令执行被中断");
            }
            return -1;
        } catch (RuntimeException error) {
            if (callback != null) {
                callback.onError(describe(error));
            }
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                ProcessTreeKiller.kill(process);
            }
        }
    }

    /** Pumps merged process output to the callback until EOF or cancellation. */
    private void pump(Process process, Callback callback, AtomicBoolean finished) {
        try (Reader reader = new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (finished.get() || read == 0) {
                    continue;
                }
                if (callback != null) {
                    callback.onOutput(new String(buffer, 0, read));
                }
            }
        } catch (IOException ignored) {
            // The process was killed or its stream closed; the exit code is authoritative.
        }
    }

    /** Falls back to the guest home for null, empty or invalid working directories. */
    private String normalizeCwd(String cwd) {
        String home = paths.homePath();
        if (cwd == null || cwd.trim().isEmpty()) {
            return home;
        }
        try {
            return GuestPaths.normalize(cwd.trim());
        } catch (IllegalArgumentException error) {
            return home;
        }
    }

    /** Returns the message of a throwable, falling back to its string form. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
