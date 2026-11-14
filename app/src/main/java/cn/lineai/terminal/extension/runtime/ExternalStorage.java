package cn.lineai.terminal.extension.runtime;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * Host-side access to the device shared storage that LineCode uses for external
 * project directories.
 *
 * <p>The extension targets API 28 and opts into legacy external storage, so a granted
 * {@code READ_EXTERNAL_STORAGE}/{@code WRITE_EXTERNAL_STORAGE} pair exposes the whole
 * {@code /storage/emulated/0} volume instead of app-scoped directories. The guest sees the
 * same volume mounted at {@code /sdcard} and {@code /storage/emulated/0}, which keeps
 * absolute paths reported by LineCode usable without translation.</p>
 */
public final class ExternalStorage {

    /** Guest path of the primary shared volume, also the conventional Android alias. */
    public static final String GUEST_SDCARD = "/sdcard";

    /** Guest path matching the host absolute path LineCode stores for external projects. */
    public static final String GUEST_ANDROID_ROOT = "/storage/emulated/0";

    /** Convenience guest path below the provider home. */
    public static final String GUEST_HOME_LINK = "/root/sdcard";

    private final Context context;

    /**
     * Creates the accessor bound to the application context.
     *
     * @param context context resolved to its application instance
     */
    public ExternalStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns the primary shared storage root.
     *
     * @return the external storage directory; never {@code null}
     */
    public File root() {
        File directory = Environment.getExternalStorageDirectory();
        return directory == null ? new File(GUEST_ANDROID_ROOT) : directory;
    }

    /**
     * Reports whether shared storage is currently readable by this process.
     *
     * <p>Directory listing is the permission probe: on Android a denied external storage
     * permission makes {@link File#list()} return {@code null} while {@code isDirectory()}
     * may still be true.</p>
     *
     * @return {@code true} when the shared volume can be listed
     */
    public boolean available() {
        File root = root();
        if (!root.isDirectory()) {
            return false;
        }
        String[] children = root.list();
        return children != null;
    }

    /**
     * Returns the host root usable as a PRoot bind source, or {@code null} when the
     * permission is not granted yet.
     *
     * @return the shared storage root, or {@code null} when unavailable
     */
    public File mountRoot() {
        return available() ? root() : null;
    }
}
