package cn.lineai.terminal.extension.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import cn.lineai.ipc.terminal.ITerminalProviderService;
import cn.lineai.terminal.extension.runtime.ProviderInfo;

/**
 * LineCode IPC 契约的端上自检：通过 provider action 发现服务、绑定导出的 Service，
 * 随后读取 providerInfo 并执行一次 guest 文件查询。
 */
final class IpcSelfTest {

    interface Callback {
        void onResult(String text);
    }

    private static final String ACTION = "cn.lineai.action.IPC_TERMINAL_PROVIDER";

    private IpcSelfTest() {
    }

    static void run(Context context, ExecutorService workers, Callback callback) {
        workers.submit(() -> {
            StringBuilder out = new StringBuilder();
            out.append("IPC 自检\n");
            try {
                PackageManager manager = context.getPackageManager();
                List<ResolveInfo> services = manager.queryIntentServices(
                        new Intent(ACTION), 0);
                out.append("· 可发现服务数：").append(services == null ? 0 : services.size())
                        .append('\n');
                if (services != null) {
                    for (ResolveInfo info : services) {
                        if (info.serviceInfo == null) {
                            continue;
                        }
                        out.append("  - ").append(info.serviceInfo.packageName).append('/')
                                .append(info.serviceInfo.name)
                                .append("  label=").append(info.loadLabel(manager))
                                .append('\n');
                    }
                }
                String bindResult = bindAndProbe(context, out);
                out.append("· 绑定：" ).append(bindResult).append('\n');
            } catch (RuntimeException error) {
                out.append("· 失败：").append(describe(error)).append('\n');
            }
            String text = out.toString();
            callback.onResult(text);
        });
    }

    private static String bindAndProbe(Context context, StringBuilder out) throws RuntimeException {
        CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] binder = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder[0] = service;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latch.countDown();
            }
        };
        Intent intent = new Intent(ACTION).setPackage(context.getPackageName());
        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            return "bindService 返回 false";
        }
        try {
            boolean connected;
            try {
                connected = latch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "绑定被中断";
            }
            if (!connected || binder[0] == null) {
                return "绑定超时";
            }
            ITerminalProviderService service =
                    ITerminalProviderService.Stub.asInterface(binder[0]);
            out.append("· providerType：").append(service.getProviderType()).append('\n');
            String info = service.getProviderInfo();
            out.append("· providerInfo：").append(info).append('\n');
            out.append("· isAvailable：").append(service.isAvailable()).append('\n');
            out.append("· fileExists(/bin/bash)：").append(service.fileExists("/bin/bash"))
                    .append('\n');
            out.append("· listDir(/root/workspace)：")
                    .append(service.listDir("/root/workspace").length).append(" 项\n");
            out.append("· getFileSize(/bin/bash)：").append(service.getFileSize("/bin/bash"))
                    .append('\n');
            out.append("· home：").append(ProviderInfo.json("/root/workspace").length())
                    .append(" 字节 JSON\n");
            return "成功";
        } catch (RuntimeException | android.os.RemoteException failure) {
            return "失败：" + describe(failure);
        } finally {
            context.unbindService(connection);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
