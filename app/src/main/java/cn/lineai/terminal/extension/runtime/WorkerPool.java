package cn.lineai.terminal.extension.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 进程级共享守护线程池，供 shell 执行、解压与状态页任务使用。 */
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

    private WorkerPool() {
    }

    public static ExecutorService shared() {
        return SHARED;
    }
}
