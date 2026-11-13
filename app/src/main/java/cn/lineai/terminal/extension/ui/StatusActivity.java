package cn.lineai.terminal.extension.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

import cn.lineai.terminal.extension.R;
import cn.lineai.terminal.extension.TerminalExtensionApp;
import cn.lineai.terminal.extension.runtime.ProotEnvironment;
import cn.lineai.terminal.extension.runtime.ShellRunner;
import cn.lineai.terminal.extension.runtime.WorkerPool;

/**
 * Status and installation page; deliberately has no launcher entry. Opened via
 * {@code adb shell am start -n cn.lineai.terminal.extension/.ui.StatusActivity}
 * or the exported {@code cn.lineai.action.OPEN_TERMINAL_EXTENSION} action. The
 * page polls the shared {@link ProotEnvironment} state and renders install
 * progress, paths and diagnostics.
 */
public final class StatusActivity extends Activity {

    /**
     * Optional intent extra enabling headless installation; set it to
     * {@code true} with, for example,
     * {@code adb ... am start --ez auto_install true}.
     */
    public static final String EXTRA_AUTO_INSTALL = "auto_install";

    private ProotEnvironment environment;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView progressView;
    private TextView pathView;
    private TextView prootView;
    private TextView sizeView;
    private TextView abiView;
    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progressBar;
    private Button installButton;
    private boolean testRunning;

    private final Runnable poll = new Runnable() {
        /** Repaints the environment state and reschedules itself at a fixed interval. */
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 500L);
        }
    };

    /**
     * Builds the interface, honors the {@link #EXTRA_AUTO_INSTALL} extra and
     * triggers installation when appropriate.
     *
     * @param savedInstanceState previously saved state, unused by this page
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        environment = TerminalExtensionApp.environment(this);
        setContentView(buildUi());
        appendLog(getString(R.string.log_empty));
        if (!environment.isArm64()) {
            appendLog(getString(R.string.status_abi_missing));
        }
        boolean autoInstall = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_INSTALL, false);
        if (autoInstall && !environment.isReady() && !environment.isInstalling()) {
            startInstall(false);
        } else if (environment.isArm64() && !environment.isReady()
                && !environment.isInstalling()) {
            environment.startBackgroundInstall();
        }
        render();
    }

    /** Starts the periodic state poll while the page is visible. */
    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    /** Stops the periodic state poll. */
    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    /** Constructs the whole interface programmatically. */
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(titles, titleParams);
        TextView title = new TextView(this);
        title.setText(R.string.status_title);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.status_subtitle);
        subtitle.setTextSize(12f);
        subtitle.setTextColor(0xFF666666);
        titles.addView(subtitle);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(20);
        root.addView(statusView, statusParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(8));
        barParams.topMargin = dp(10);
        root.addView(progressBar, barParams);

        progressView = new TextView(this);
        progressView.setTextSize(12f);
        progressView.setTextColor(0xFF666666);
        root.addView(progressView, new LinearLayout.LayoutParams(-1, -2));

        pathView = new TextView(this);
        pathView.setTextSize(12f);
        pathView.setTextColor(0xFF444444);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(12);
        root.addView(pathView, infoParams);

        prootView = new TextView(this);
        prootView.setTextSize(12f);
        prootView.setTextColor(0xFF444444);
        root.addView(prootView, new LinearLayout.LayoutParams(-1, -2));

        sizeView = new TextView(this);
        sizeView.setTextSize(12f);
        sizeView.setTextColor(0xFF444444);
        root.addView(sizeView, new LinearLayout.LayoutParams(-1, -2));

        abiView = new TextView(this);
        abiView.setTextSize(12f);
        abiView.setTextColor(0xFFB3261E);
        LinearLayout.LayoutParams abiParams = new LinearLayout.LayoutParams(-1, -2);
        abiParams.topMargin = dp(12);
        root.addView(abiView, abiParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(16);
        root.addView(actions, actionsParams);
        installButton = new Button(this);
        installButton.setText(R.string.status_install);
        installButton.setOnClickListener(v -> onInstallClicked());
        actions.addView(installButton, new LinearLayout.LayoutParams(0, -2, 1f));
        Button testButton = new Button(this);
        testButton.setText(R.string.status_test);
        testButton.setOnClickListener(v -> onTestClicked());
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(0, -2, 1f);
        testParams.leftMargin = dp(8);
        actions.addView(testButton, testParams);
        Button clearButton = new Button(this);
        clearButton.setText(R.string.status_clear_log);
        clearButton.setOnClickListener(v -> clearLog());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, -2, 1f);
        clearParams.leftMargin = dp(8);
        actions.addView(clearButton, clearParams);

        Button ipcButton = new Button(this);
        ipcButton.setText(R.string.status_ipc_test);
        ipcButton.setOnClickListener(v -> onIpcSelfTestClicked());
        LinearLayout.LayoutParams ipcParams = new LinearLayout.LayoutParams(-1, -2);
        ipcParams.topMargin = dp(8);
        root.addView(ipcButton, ipcParams);

        TextView logLabel = new TextView(this);
        logLabel.setText(R.string.status_log_label);
        logLabel.setTextSize(13f);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams logLabelParams = new LinearLayout.LayoutParams(-1, -2);
        logLabelParams.topMargin = dp(20);
        root.addView(logLabel, logLabelParams);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFFF1F1F1);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF222222);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, dp(240));
        logParams.topMargin = dp(8);
        root.addView(logScroll, logParams);

        return scroll;
    }

    /** Publishes environment state, install progress, paths and warnings to the widgets. */
    private void render() {
        if (isFinishing()) {
            return;
        }
        boolean ready = environment.isReady();
        boolean installing = environment.isInstalling();
        String error = environment.lastError();
        int percent = environment.progressPercent();
        String message = environment.progressMessage();

        if (installing) {
            statusView.setText(R.string.status_state_installing);
        } else if (ready) {
            statusView.setText(R.string.status_state_ready);
        } else if (error != null && !error.isEmpty()) {
            statusView.setText(R.string.status_state_failed);
        } else {
            statusView.setText(R.string.status_state_missing);
        }

        progressBar.setVisibility(installing ? View.VISIBLE : View.GONE);
        progressBar.setProgress(percent);
        if (installing) {
            progressView.setVisibility(View.VISIBLE);
            progressView.setText(getString(R.string.install_progress, percent,
                    message == null ? "" : message));
        } else if (ready) {
            progressView.setVisibility(View.VISIBLE);
            progressView.setText(R.string.install_done);
        } else if (error != null && !error.isEmpty()) {
            progressView.setVisibility(View.VISIBLE);
            progressView.setText(getString(R.string.install_failed, error));
        } else {
            progressView.setVisibility(View.GONE);
        }

        pathView.setText(getString(R.string.status_paths,
                environment.paths().rootfsDir().getAbsolutePath()));
        prootView.setText(getString(R.string.status_proot,
                environment.paths().prootBinary().getAbsolutePath()));
        sizeView.setText(getString(R.string.status_size,
                ready ? formatSize(environment.installedSizeBytes())
                        : getString(R.string.size_unknown)));
        abiView.setText(environment.isArm64() ? "" : getString(R.string.status_abi_missing));
        installButton.setEnabled(!installing);
    }

    /** Guards installation triggers and asks for confirmation before a reinstall. */
    private void onInstallClicked() {
        if (!environment.isArm64()) {
            // Installation itself is architecture independent: extraction works on
            // any device, only PRoot execution requires arm64.
            Toast.makeText(this, R.string.status_abi_missing, Toast.LENGTH_LONG).show();
        }
        if (environment.isInstalling()) {
            Toast.makeText(this, R.string.install_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (environment.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.status_reinstall_title)
                    .setMessage(R.string.status_reinstall_message)
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.status_reinstall_ok,
                            (dialog, which) -> startInstall(true))
                    .show();
            return;
        }
        startInstall(false);
    }

    /** Runs install or reinstall on the worker pool, logging completion and failures. */
    private void startInstall(boolean reinstall) {
        appendLog(getString(R.string.install_started));
        installButton.setEnabled(false);
        WorkerPool.shared().submit(() -> {
            try {
                ProotEnvironment.Progress progress = (percent, message) ->
                        handler.post(this::render);
                if (reinstall) {
                    environment.reinstall(progress);
                } else {
                    environment.ensureInstalled(progress);
                }
                handler.post(() -> {
                    appendLog(getString(R.string.install_done));
                    render();
                });
            } catch (IOException | RuntimeException error) {
                handler.post(() -> {
                    appendLog(getString(R.string.install_failed, describe(error)));
                    render();
                });
            }
        });
    }

    /** Runs a guest shell probe and streams its output into the log view. */
    private void onTestClicked() {
        if (testRunning) {
            return;
        }
        testRunning = true;
        appendLog(getString(R.string.test_started, getString(R.string.test_command)));
        WorkerPool.shared().submit(() -> {
            if (!environment.awaitReady(180_000L)) {
                testRunning = false;
                String reason = environment.lastError();
                handler.post(() -> {
                    if (reason == null || reason.isEmpty()) {
                        appendLog(getString(R.string.test_needs_install));
                    } else {
                        appendLog(getString(R.string.test_failed, reason));
                    }
                });
                return;
            }
            int exitCode = environment.shellRunner().execute(
                    getString(R.string.test_command), environment.paths().homePath(),
                    120_000L, new ShellRunner.Callback() {
                        /** Appends guest output to the log on the UI thread. */
                        @Override
                        public void onOutput(String text) {
                            handler.post(() -> appendLog(text));
                        }

                        /** Appends a failure line to the log on the UI thread. */
                        @Override
                        public void onError(String text) {
                            handler.post(() -> appendLog(getString(R.string.test_failed, text)));
                        }

                        /** Records the exit code and releases the running flag. */
                        @Override
                        public void onComplete(int code) {
                            handler.post(() -> {
                                appendLog(getString(R.string.test_finished, code));
                                testRunning = false;
                            });
                        }
                    });
            if (exitCode < 0) {
                testRunning = false;
            }
        });
    }

    /** Launches the IPC self-test and streams its report into the log view. */
    private void onIpcSelfTestClicked() {
        appendLog("---------- IPC ----------");
        IpcSelfTest.run(this, WorkerPool.shared(), text -> handler.post(() -> appendLog(text)));
    }

    /** Clears the log view. */
    private void clearLog() {
        logView.setText("");
    }

    /** Appends text to the log view and scrolls it to the bottom. */
    private void appendLog(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (logView.length() > 0) {
            logView.append("\n");
        }
        logView.append(text);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Formats a byte count in mebibytes.
     *
     * @param bytes byte count to format
     * @return formatted size, or the unknown marker when the count is not positive
     */
    private String formatSize(long bytes) {
        if (bytes <= 0L) {
            return getString(R.string.size_unknown);
        }
        double megabytes = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB", megabytes);
    }

    /**
     * Converts density-independent pixels to device pixels.
     *
     * @param value dp value to convert
     * @return rounded pixel value
     */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Returns the message of a throwable, falling back to its string form. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
