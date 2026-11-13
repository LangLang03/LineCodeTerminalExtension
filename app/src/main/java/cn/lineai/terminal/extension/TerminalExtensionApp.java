package cn.lineai.terminal.extension;

import android.app.Application;
import android.content.Context;

import cn.lineai.terminal.extension.runtime.ProotEnvironment;

/** 应用入口，持有进程内唯一的 {@link ProotEnvironment} 实例。 */
public final class TerminalExtensionApp extends Application {

    private ProotEnvironment environment;

    @Override
    public void onCreate() {
        super.onCreate();
        environment = new ProotEnvironment(this);
    }

    public ProotEnvironment environment() {
        return environment;
    }

    public static ProotEnvironment environment(Context context) {
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof TerminalExtensionApp)) {
            throw new IllegalStateException("Application context is not TerminalExtensionApp");
        }
        return ((TerminalExtensionApp) appContext).environment();
    }
}
