package cn.lineai.terminal.extension.runtime;

import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import cn.lineai.ipc.terminal.ITerminalProviderCallback;
import cn.lineai.ipc.terminal.TerminalShellBackgroundState;

/**
 * Provider-side registry of background guest tasks behind the AIDL v2 contract.
 *
 * <p>A task is a PRoot guest process launched detached: the caller receives a handle
 * immediately, output streams to the IPC callback and is also retained in an
 * {@link OutputBuffer} so {@code readShellBackground} can serve polled reads by offset.
 * Handles stay registered until {@code killShellBackground} / {@code forgetShellBackground}
 * frees them; {@link #shutdown} kills everything still running when the service dies.
 */
public final class BackgroundGuestTasks {

    private static final String TAG = "TerminalExtension";

    private final ExecutorService executor = WorkerPool.shared();
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong handleSeq = new AtomicLong();
    private final ShellRunner shellRunner;

    /**
     * Creates the registry.
     *
     * @param shellRunner guest command builder whose argv and environment launch PRoot
     */
    public BackgroundGuestTasks(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
    }

    /** One background task: process handle, output buffer, callback and lifecycle flags. */
    static final class Task {
        final long handleId;
        final Process process;
        final ITerminalProviderCallback callback;
        final OutputBuffer buffer = new OutputBuffer();
        volatile boolean running = true;
        volatile int exitCode = -1;

        Task(long handleId, Process process, ITerminalProviderCallback callback) {
            this.handleId = handleId;
            this.process = process;
            this.callback = callback;
        }
    }

    /**
     * Launches a guest command in the background and registers it.
     *
     * @param command guest shell command line; empty commands are rejected
     * @param cwd guest working directory; the home directory is used when absent or invalid
     * @param callback streaming callback; may be {@code null}
     * @return the task handle, or -1 when the process could not be started
     */
    public long start(String command, String cwd, ITerminalProviderCallback callback) {
        ProcessBuilder builder = new ProcessBuilder(shellRunner.buildCommand(command, cwd));
        builder.redirectErrorStream(false);
        Map<String, String> env = shellRunner.buildEnvironment();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            builder.environment().put(entry.getKey(), entry.getValue());
        }
        long handle = handleSeq.incrementAndGet();
        try {
            Task task = new Task(handle, builder.start(), callback);
            tasks.put(handle, task);
            executor.submit(() -> run(task));
            return handle;
        } catch (Exception error) {
            Log.e(TAG, "startShellBackground failed", error);
            tasks.remove(handle);
            notifyError(callback, error.getMessage() == null
                    ? error.toString() : error.getMessage());
            return -1;
        }
    }

    /**
     * Serves a polled read: the running flag, the exit code once finished, and every output
     * byte recorded after the host-consumed offset.
     *
     * @param handleId task handle returned by {@link #start}
     * @param offset output bytes already consumed by the host
     * @return the task snapshot; a finished-unknown snapshot when the handle is stale
     */
    public TerminalShellBackgroundState read(long handleId, long offset) {
        Task task = tasks.get(handleId);
        if (task == null) {
            return new TerminalShellBackgroundState(handleId, false, -1, "");
        }
        String chunk;
        synchronized (task.buffer) {
            chunk = task.buffer.chunkFrom(offset);
        }
        return new TerminalShellBackgroundState(handleId, task.running, task.exitCode, chunk);
    }

    /**
     * Kills a task and its whole guest process tree, freeing the provider-side buffer.
     *
     * @param handleId task handle
     * @return true when the handle was known; false for a stale or unknown handle
     */
    public boolean kill(long handleId) {
        Task task = tasks.remove(handleId);
        if (task == null) {
            return false;
        }
        task.running = false;
        ProcessTreeKiller.kill(task.process);
        return true;
    }

    /** Frees the buffer of a finished task without touching its process. */
    public void forget(long handleId) {
        tasks.remove(handleId);
    }

    /** Registers a prepared task; visible for tests driving the pump directly. */
    void register(Task task) {
        tasks.put(task.handleId, task);
    }

    /** Kills every still-running task; called when the provider service is destroyed. */
    public void shutdown() {
        for (Long handle : tasks.keySet().toArray(new Long[0])) {
            Task task = tasks.remove(handle);
            if (task != null) {
                task.running = false;
                ProcessTreeKiller.kill(task.process);
            }
        }
        Log.i(TAG, "background guest tasks shut down");
    }

    /**
     * Pump-and-wait body of one task: streams both output pipes into the buffer and the
     * IPC callback, records the exit code after the process exits and completes the
     * callback. The dedicated per-stream threads are the sanctioned drain pattern; a
     * blocking guest pipe cannot be drained with a timeout otherwise.
     *
     * @param task the registered task to drive to completion
     */
    void run(Task task) {
        Thread stdoutThread = pump(task, task.process.getInputStream(), "bg-stdout-"
                + task.handleId);
        Thread stderrThread = pump(task, task.process.getErrorStream(), "bg-stderr-"
                + task.handleId);
        stdoutThread.start();
        stderrThread.start();
        try {
            int exit = task.process.waitFor();
            stdoutThread.join(1000);
            stderrThread.join(1000);
            task.exitCode = exit;
            task.running = false;
            try {
                if (task.callback != null) {
                    task.callback.onComplete(exit);
                }
            } catch (RemoteException callbackGone) {
                Log.w(TAG, "background onComplete not delivered", callbackGone);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            Log.e(TAG, "background task failed", error);
            task.exitCode = -1;
            task.running = false;
            notifyError(task.callback, error.getMessage() == null
                    ? error.toString() : error.getMessage());
        }
    }

    /** Builds one daemon pump thread forwarding guest output to the buffer and callback. */
    private Thread pump(Task task, java.io.InputStream stream, String name) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String chunk = line + "\n";
                    synchronized (task.buffer) {
                        task.buffer.append(chunk);
                    }
                    try {
                        if (task.callback != null) {
                            task.callback.onOutput(chunk);
                        }
                    } catch (RemoteException callbackGone) {
                        Log.w(TAG, "background onOutput not delivered", callbackGone);
                    }
                }
            } catch (IOException streamClosed) {
                Log.w(TAG, "background stream closed", streamClosed);
            }
        }, name);
    }

    private static void notifyError(ITerminalProviderCallback callback, String message) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(message);
        } catch (RemoteException callbackGone) {
            Log.w(TAG, "background onError not delivered", callbackGone);
        }
    }
}
