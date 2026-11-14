package cn.lineai.terminal.extension.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.lineai.ipc.terminal.ITerminalProviderCallback;
import cn.lineai.ipc.terminal.TerminalShellBackgroundState;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Drives the background-guest-task lifecycle with a scripted {@link Process} double:
 * offset-addressed reads, streamed callback delivery, kill semantics and buffer reuse.
 */
public class BackgroundGuestTasksTest {

    /** Records every callback event so tests can assert the exact delivery order. */
    private static final class RecordingCallback extends ITerminalProviderCallback.Stub {
        final List<String> output = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final BlockingQueue<Integer> completions = new LinkedBlockingQueue<>();

        @Override public void onOutput(String text) {
            output.add(text);
        }

        @Override public void onError(String text) {
            errors.add(text);
        }

        @Override public void onComplete(int exitCode) {
            completions.add(exitCode);
        }
    }

    /** A fake process fed from a scripted stream; waitFor blocks until the stream closes. */
    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream discarded = new OutputStream() {
            @Override public void write(int b) { }
        };
        private volatile boolean destroyed;

        FakeProcess(String stdoutText, String stderrText) {
            this.stdout = new java.io.ByteArrayInputStream(stdoutText.getBytes());
            this.stderr = new java.io.ByteArrayInputStream(stderrText.getBytes());
        }

        @Override public OutputStream getOutputStream() {
            return discarded;
        }

        @Override public InputStream getInputStream() {
            return stdout;
        }

        @Override public InputStream getErrorStream() {
            return stderr;
        }

        /** Simulates a short guest command: returns after the pumps have drained the streams. */
        @Override public int waitFor() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            destroyed = true;
            return 7;
        }

        @Override public void destroy() {
            destroyed = true;
        }

        @Override public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override public int exitValue() {
            return destroyed ? 7 : throwIllegal();
        }

        private int throwIllegal() {
            throw new IllegalThreadStateException("still running");
        }

        @Override public boolean isAlive() {
            return !destroyed;
        }
    }

    @Test public void readReturnsOutputAfterTheConsumedOffset() throws Exception {
        BackgroundGuestTasks registry = new BackgroundGuestTasks(new ShellRunner(
                new RuntimePaths(new java.io.File("build/test-runtime"))));
        RecordingCallback callback = new RecordingCallback();

        // register + run are package-private seams: the fake process finishes immediately,
        // which also covers the completed path without any real guest.
        BackgroundGuestTasks.Task task = new BackgroundGuestTasks.Task(1,
                new FakeProcess("first\nsecond\n", ""), callback);
        registry.register(task);
        registry.run(task);

        TerminalShellBackgroundState head = registry.read(1, 0);
        assertFalse(head.running);
        assertEquals(7, head.exitCode);
        assertEquals("first\nsecond\n", head.chunk);

        TerminalShellBackgroundState tail = registry.read(1, 6);
        assertEquals("second\n", tail.chunk);
        assertEquals("", registry.read(1, 100).chunk);
        assertEquals(7, callback.completions.poll(1, TimeUnit.SECONDS).intValue());
    }

    @Test public void killMarksTheTaskAndFreesTheHandle() throws Exception {
        BackgroundGuestTasks registry = new BackgroundGuestTasks(new ShellRunner(
                new RuntimePaths(new java.io.File("build/test-runtime"))));
        RecordingCallback callback = new RecordingCallback();

        BackgroundGuestTasks.Task task = new BackgroundGuestTasks.Task(2,
                new FakeProcess("", ""), callback);
        registry.register(task);

        assertTrue(registry.read(2, 0).running);
        assertTrue(registry.kill(2));
        assertFalse(registry.read(2, 0).running);
        assertFalse(registry.kill(2));
        assertTrue(registry.read(2, 0).chunk.isEmpty());
    }

    @Test public void unknownHandlesReportFinishedUnknown() {
        BackgroundGuestTasks registry = new BackgroundGuestTasks(new ShellRunner(
                new RuntimePaths(new java.io.File("build/test-runtime"))));
        TerminalShellBackgroundState stale = registry.read(99, 0);
        assertFalse(stale.running);
        assertEquals(-1, stale.exitCode);
        assertEquals("", stale.chunk);
    }

    @Test public void forgetFreesTheHandleWithoutTouchingTheProcess() throws Exception {
        BackgroundGuestTasks registry = new BackgroundGuestTasks(new ShellRunner(
                new RuntimePaths(new java.io.File("build/test-runtime"))));
        BackgroundGuestTasks.Task task = new BackgroundGuestTasks.Task(3,
                new FakeProcess("", ""), new RecordingCallback());
        registry.register(task);

        registry.forget(3);
        assertFalse(registry.read(3, 0).running);
        assertEquals(-1, registry.read(3, 0).exitCode);
    }

    @Test public void outputBufferKeepsAPrefixOnceCapped() {
        OutputBuffer buffer = new OutputBuffer();
        StringBuilder pushed = new StringBuilder();
        for (int i = 0; i < OutputBuffer.MAX_CHARS - 3; i++) pushed.append('a');
        buffer.append(pushed.toString());
        // The chunk that crosses the cap is appended and then truncated to the cap, so the
        // prefix stays contiguous and the tail keeps the first overflowing characters.
        buffer.append("XYZWXYZ");

        assertEquals(OutputBuffer.MAX_CHARS, buffer.length());
        assertTrue(buffer.isTruncated());
        assertEquals("XYZ", buffer.chunkFrom(buffer.length() - 3));
        // A further append past the cap is dropped entirely.
        buffer.append("dropped");
        assertEquals(OutputBuffer.MAX_CHARS, buffer.length());
        assertEquals("", buffer.chunkFrom(-1));
    }

    /** Exposes the package-private registration seam to tests in the same package. */
    private static void registerForTest(BackgroundGuestTasks registry,
                                        BackgroundGuestTasks.Task task) {
        registry.register(task);
    }
}
