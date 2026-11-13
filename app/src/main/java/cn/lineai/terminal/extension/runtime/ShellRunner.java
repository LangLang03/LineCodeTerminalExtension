package cn.lineai.terminal.extension.runtime;

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

/**
 * 通过 /bin/bash 在 PRoot guest 内执行命令。每次调用启动一个 proot 进程，
 * stdout 与 stderr 合并后按 UTF-8 分块回传。
 */
public final class ShellRunner {

    public interface Callback {
        void onOutput(String text);

        void onError(String text);

        void onComplete(int exitCode);
    }

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long MAX_TIMEOUT_MS = 30L * 60L * 1000L;

    private final RuntimePaths paths;
    private final ExecutorService workers = WorkerPool.shared();

    public ShellRunner(RuntimePaths paths) {
        this.paths = paths;
    }

    /** 构建 guest 命令使用的完整 argv。 */
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

    /** proot 宿主进程自身的环境变量。 */
    public java.util.Map<String, String> buildEnvironment() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("PROOT_LOADER", paths.prootLoader().getAbsolutePath());
        env.put("PROOT_TMP_DIR", paths.prootTmpDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", paths.prootLibDir().getAbsolutePath());
        return env;
    }

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
                // 进程已退出，仍阻塞的读取线程会随 EOF 很快返回。
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
            // 进程被杀或已关闭流；执行结果以退出码为准。
        }
    }

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

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
