package cn.lineai.terminal.extension.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import cn.lineai.ipc.terminal.ITerminalProviderCallback;
import cn.lineai.ipc.terminal.ITerminalProviderService;
import cn.lineai.terminal.extension.TerminalExtensionApp;
import cn.lineai.terminal.extension.runtime.ProotEnvironment;
import cn.lineai.terminal.extension.runtime.ShellRunner;

/**
 * LineCode terminal provider exposed over IPC. The AIDL contract matches the
 * LineCode {@code :ipc} module verbatim; the service exposes only the guest
 * shell and guest file operations. The application keeps {@code targetSdk 28}
 * deliberately, because Android 10 and later forbid executing binaries from
 * app private storage on API 29 and above.
 */
public final class TerminalProviderService extends Service {

    private static final String TAG = "TerminalProvider";
    private static final long READY_WAIT_MS = 180_000L;
    private static final int EXIT_NOT_READY = -3;

    private ProotEnvironment environment;
    private IBinder binder;

    /** Acquires the shared environment, starts the install and creates the binder. */
    @Override
    public void onCreate() {
        super.onCreate();
        environment = TerminalExtensionApp.environment(this);
        environment.startBackgroundInstall();
        binder = new ProviderBinder();
    }

    /**
     * Returns the provider binder.
     *
     * @param intent binding intent
     * @return the AIDL stub of the terminal provider
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind: " + (intent == null ? "null" : intent.getAction()));
        return binder;
    }

    /** Logs teardown; the shared environment outlives this service instance. */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    /**
     * AIDL stub forwarding every operation to the shared {@link ProotEnvironment}.
     */
    private final class ProviderBinder extends ITerminalProviderService.Stub {

        /** @return the provider type identifier */
        @Override
        public String getProviderType() {
            return "terminal";
        }

        /** @return the provider descriptor JSON document */
        @Override
        public String getProviderInfo() {
            return environment.providerInfoJson();
        }

        /** @return true when the guest environment is installed and ready */
        @Override
        public boolean isAvailable() {
            return environment.isReady();
        }

        /**
         * Executes a command in the guest shell after waiting for the
         * environment to become ready.
         *
         * @param command shell command line to execute
         * @param cwd guest working directory
         * @param timeoutMs wall-clock limit for the guest command
         * @param callback streaming callback for output, errors and completion
         * @return the guest exit code when non-negative; otherwise a sentinel:
         *         -1 on empty command or spawn failure, -2 on timeout, and
         *         -3 when the environment never became ready
         */
        @Override
        public int executeShell(String command, String cwd, long timeoutMs,
                                ITerminalProviderCallback callback) {
            if (!environment.awaitReady(READY_WAIT_MS)) {
                String reason = environment.lastError();
                notifyError(callback, reason == null || reason.isEmpty()
                        ? "Ubuntu 环境尚未就绪" : "Ubuntu 环境尚未就绪：" + reason);
                return EXIT_NOT_READY;
            }
            return environment.shellRunner().execute(command, cwd, timeoutMs,
                    new ShellRunner.Callback() {
                        /** Forwards guest output to the IPC callback. */
                        @Override
                        public void onOutput(String text) {
                            notifyOutput(callback, text);
                        }

                        /** Forwards diagnostics to the IPC callback. */
                        @Override
                        public void onError(String text) {
                            notifyError(callback, text);
                        }

                        /** Forwards the guest exit code to the IPC callback. */
                        @Override
                        public void onComplete(int exitCode) {
                            try {
                                if (callback != null) {
                                    callback.onComplete(exitCode);
                                }
                            } catch (RemoteException error) {
                                Log.w(TAG, "callback.onComplete failed: " + error.getMessage());
                            }
                        }
                    });
        }

        /** Reads a whole guest file; see the guest file facade semantics. */
        @Override
        public byte[] readFile(String path) {
            return environment.files().readFile(path);
        }

        /** Writes or truncates a guest file at offset zero. */
        @Override
        public boolean writeFile(String path, byte[] data) {
            return environment.files().writeFile(path, data);
        }

        /** Deletes a single guest file without recursing into directories. */
        @Override
        public boolean deleteFile(String path) {
            return environment.files().deleteFile(path);
        }

        /** Lists the entry names of a guest directory. */
        @Override
        public String[] listDir(String path) {
            return environment.files().listDir(path);
        }

        /** Tests existence of a guest path. */
        @Override
        public boolean fileExists(String path) {
            return environment.files().fileExists(path);
        }

        /** Returns the size of a guest file, or -1 when unavailable. */
        @Override
        public long fileSize(String path) {
            return environment.files().fileSize(path);
        }

        /** Reads one chunk of a guest file at an explicit offset. */
        @Override
        public byte[] readFileChunk(String path, long offset, int size) {
            return environment.files().readFileChunk(path, offset, size);
        }

        /** Writes one chunk of a guest file, appending for negative offsets. */
        @Override
        public boolean writeFileChunk(String path, long offset, byte[] data) {
            return environment.files().writeFileChunk(path, offset, data);
        }

        /** Alias of {@code fileSize}; kept for AIDL contract symmetry. */
        @Override
        public long getFileSize(String path) {
            return environment.files().getFileSize(path);
        }

        /** Lists a guest directory as a JSON array of detailed entries. */
        @Override
        public String listDirDetailed(String path) {
            return environment.files().listDirDetailed(path);
        }
    }

    /** Forwards an output chunk to the callback, swallowing remote failures. */
    private void notifyOutput(ITerminalProviderCallback callback, String text) {
        if (callback == null) {
            return;
        }
        try {
            callback.onOutput(text);
        } catch (RemoteException error) {
            Log.w(TAG, "callback.onOutput failed: " + error.getMessage());
        }
    }

    /** Forwards a diagnostic message to the callback, swallowing remote failures. */
    private void notifyError(ITerminalProviderCallback callback, String text) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(text);
        } catch (RemoteException error) {
            Log.w(TAG, "callback.onError failed: " + error.getMessage());
        }
    }
}
