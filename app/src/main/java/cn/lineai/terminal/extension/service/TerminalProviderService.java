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
 * 经 IPC 暴露的 LineCode 终端 provider。AIDL 契约与 LineCode {@code :ipc} 模块逐字一致；
 * 服务仅暴露 guest shell 与 guest 文件操作。
 */
public final class TerminalProviderService extends Service {

    private static final String TAG = "TerminalProvider";
    private static final long READY_WAIT_MS = 180_000L;
    private static final int EXIT_NOT_READY = -3;

    private ProotEnvironment environment;
    private IBinder binder;

    @Override
    public void onCreate() {
        super.onCreate();
        environment = TerminalExtensionApp.environment(this);
        environment.startBackgroundInstall();
        binder = new ProviderBinder();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind: " + (intent == null ? "null" : intent.getAction()));
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    private final class ProviderBinder extends ITerminalProviderService.Stub {

        @Override
        public String getProviderType() {
            return "terminal";
        }

        @Override
        public String getProviderInfo() {
            return environment.providerInfoJson();
        }

        @Override
        public boolean isAvailable() {
            return environment.isReady();
        }

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
                        @Override
                        public void onOutput(String text) {
                            notifyOutput(callback, text);
                        }

                        @Override
                        public void onError(String text) {
                            notifyError(callback, text);
                        }

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

        @Override
        public byte[] readFile(String path) {
            return environment.files().readFile(path);
        }

        @Override
        public boolean writeFile(String path, byte[] data) {
            return environment.files().writeFile(path, data);
        }

        @Override
        public boolean deleteFile(String path) {
            return environment.files().deleteFile(path);
        }

        @Override
        public String[] listDir(String path) {
            return environment.files().listDir(path);
        }

        @Override
        public boolean fileExists(String path) {
            return environment.files().fileExists(path);
        }

        @Override
        public long fileSize(String path) {
            return environment.files().fileSize(path);
        }

        @Override
        public byte[] readFileChunk(String path, long offset, int size) {
            return environment.files().readFileChunk(path, offset, size);
        }

        @Override
        public boolean writeFileChunk(String path, long offset, byte[] data) {
            return environment.files().writeFileChunk(path, offset, data);
        }

        @Override
        public long getFileSize(String path) {
            return environment.files().getFileSize(path);
        }

        @Override
        public String listDirDetailed(String path) {
            return environment.files().listDirDetailed(path);
        }
    }

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
