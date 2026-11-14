package cn.lineai.terminal.extension.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide daemon executor shared by shell execution, archive extraction and
 * status page tasks. Daemon threads keep VM exit unblocked regardless of the
 * components that submitted work.
 */
public final class WorkerPool {

    private static final ExecutorService SHARED;

    static {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "terminal-extension-worker-"
                    + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        SHARED = Executors.newCachedThreadPool(factory);
    }

    /** Suppresses instantiation; the pool is process-wide and static. */
    private WorkerPool() {
    }

    /**
     * Returns the shared executor.
     *
     * @return a cached thread pool of named daemon worker threads
     */
    public static ExecutorService shared() {
        return SHARED;
    }
}
