package cn.lineai.terminal.extension;

import android.app.Application;
import android.content.Context;

import cn.lineai.terminal.extension.runtime.ProotEnvironment;

/**
 * Application entry point. Holds the process-wide {@link ProotEnvironment}
 * singleton so that the IPC service and the status activity drive a single
 * shared guest lifecycle.
 */
public final class TerminalExtensionApp extends Application {

    private ProotEnvironment environment;

    /**
     * Creates the shared {@link ProotEnvironment} before any component binds to it.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        environment = new ProotEnvironment(this);
    }

    /**
     * Returns the process-wide guest environment.
     *
     * @return the shared {@link ProotEnvironment} instance
     */
    public ProotEnvironment environment() {
        return environment;
    }

    /**
     * Returns the shared environment for an arbitrary context by resolving the
     * application singleton.
     *
     * @param context context whose application instance is inspected
     * @return the process-wide {@link ProotEnvironment}
     * @throws IllegalStateException when the application is not {@code TerminalExtensionApp}
     */
    public static ProotEnvironment environment(Context context) {
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof TerminalExtensionApp)) {
            throw new IllegalStateException("Application context is not TerminalExtensionApp");
        }
        return ((TerminalExtensionApp) appContext).environment();
    }
}
