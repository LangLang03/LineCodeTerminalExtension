package cn.lineai.terminal.extension.runtime;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Terminates a PRoot host process together with all of its guest descendants.
 * Guest processes inherit the PRoot ptrace chain, so destroying only the
 * launcher would leave guest shells alive; this class therefore walks
 * {@code /proc} to collect the descendant set and delivers SIGKILL before the
 * {@link Process} handle itself is destroyed.
 */
final class ProcessTreeKiller {

    private static final String TAG = "ProcessTreeKiller";

    /** Suppresses instantiation; all members are static. */
    private ProcessTreeKiller() {
    }

    /**
     * Kills the process and every descendant visible in {@code /proc}, then
     * destroys the {@link Process} handle. Falls back to {@link Process#destroy()}
     * when the platform pid cannot be determined reflectively.
     *
     * @param process process to terminate; ignored when {@code null}
     */
    static void kill(Process process) {
        if (process == null) {
            return;
        }
        int pid = processPid(process);
        if (pid <= 0) {
            process.destroy();
            return;
        }
        for (int descendant : collectDescendants(pid)) {
            safeKill(descendant);
        }
        safeKill(pid);
        process.destroy();
    }

    /** Reads the platform pid via reflection; returns -1 when unavailable. */
    private static int processPid(Process process) {
        try {
            Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object value = field.get(process);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
            // The Android Process implementation may change; fall back to destroy()
            // when the pid cannot be read.
        }
        return -1;
    }

    /**
     * Walks {@code /proc} and resolves the transitive descendant set of the root
     * pid by iterating the parent-child relation until it is closed.
     */
    private static Set<Integer> collectDescendants(int rootPid) {
        Map<Integer, Integer> parentByPid = new HashMap<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                int pid;
                try {
                    pid = Integer.parseInt(dir.getName());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                String stat = readStatLine(dir);
                if (stat == null) {
                    continue;
                }
                int parent = parseParent(stat);
                if (parent > 0 && pid != rootPid) {
                    parentByPid.put(pid, parent);
                }
            }
        }
        Set<Integer> descendants = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Integer, Integer> entry : parentByPid.entrySet()) {
                int child = entry.getKey();
                int parent = entry.getValue();
                if ((parent == rootPid || descendants.contains(parent)) && descendants.add(child)) {
                    changed = true;
                }
            }
        } while (changed);
        return descendants;
    }

    /** Extracts the parent pid from a procfs stat line, skipping the comm field. */
    private static int parseParent(String stat) {
        int close = stat.lastIndexOf(')');
        if (close < 0) {
            return -1;
        }
        String rest = stat.substring(close + 1).trim();
        if (rest.isEmpty()) {
            return -1;
        }
        String[] fields = rest.split("\\s+");
        if (fields.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Reads the first line of a procfs stat file; returns null when unreadable. */
    private static String readStatLine(File procDir) {
        File stat = new File(procDir, "stat");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(stat), StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Delivers SIGKILL, tolerating already exited or protected pids. */
    private static void safeKill(int pid) {
        if (pid <= 0) {
            return;
        }
        try {
            Os.kill(pid, OsConstants.SIGKILL);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot kill pid " + pid + ": " + error.getMessage());
        }
    }
}
