package cn.lineai.terminal.extension.runtime;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShellRunnerTest {

    private ShellRunner runner() {
        RuntimePaths paths = new RuntimePaths(new File("/tmp/linecode-extension-test"));
        return new ShellRunner(paths);
    }

    @Test
    public void buildsTheProotCommandWithBundledPaths() {
        List<String> command = runner().buildCommand("uname -a", "/root/workspace");
        assertEquals("/tmp/linecode-extension-test/runtime/proot", command.get(0));
        assertTrue(command.contains("--link2symlink"));
        assertTrue(command.contains("-0"));
        assertTrue(command.contains("--kill-on-exit"));
        assertEquals("/tmp/linecode-extension-test/ubuntu/rootfs",
                command.get(command.indexOf("-r") + 1));
        assertTrue(command.contains("-b"));
        assertTrue(command.contains("/dev/urandom:/dev/random"));
        assertEquals("/root/workspace", command.get(command.indexOf("-w") + 1));
        assertEquals("/usr/bin/env", command.get(command.size() - 11));
        assertEquals("/bin/bash", command.get(command.size() - 3));
        assertEquals("-lc", command.get(command.size() - 2));
        assertEquals("uname -a", command.get(command.size() - 1));
    }

    @Test
    public void fallsBackToTheProviderHomeForMissingOrRelativeCwd() {
        List<String> command = runner().buildCommand("ls", "");
        assertEquals("/root/workspace", command.get(command.indexOf("-w") + 1));
        command = runner().buildCommand("ls", "relative/dir");
        assertEquals("/root/workspace", command.get(command.indexOf("-w") + 1));
        command = runner().buildCommand("ls", "/tmp/../root");
        assertEquals("/root", command.get(command.indexOf("-w") + 1));
    }

    @Test
    public void exportsTheProotLoaderAndLibrarySearchPath() {
        java.util.Map<String, String> environment = runner().buildEnvironment();
        assertEquals("/tmp/linecode-extension-test/runtime/proot-loader",
                environment.get("PROOT_LOADER"));
        assertEquals("/tmp/linecode-extension-test/runtime/tmp",
                environment.get("PROOT_TMP_DIR"));
        assertEquals("/tmp/linecode-extension-test/runtime/lib",
                environment.get("LD_LIBRARY_PATH"));
        assertFalse(environment.containsKey("LD_PRELOAD"));
    }

    @Test
    public void rejectsEmptyCommandsWithoutStartingProot() {
        int[] completed = {-1};
        int exit = runner().execute("   ", "/root/workspace", 1000L,
                new ShellRunner.Callback() {
                    @Override
                    public void onOutput(String text) {
                    }

                    @Override
                    public void onError(String text) {
                        completed[0] = 0;
                    }

                    @Override
                    public void onComplete(int exitCode) {
                        completed[0] = 1;
                    }
                });
        assertEquals(-1, exit);
        assertEquals(0, completed[0]);
    }
}
