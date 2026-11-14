package cn.lineai.terminal.extension.runtime;

import java.io.File;

/**
 * Fixed layout of the embedded PRoot runtime and the Ubuntu rootfs below the
 * application private storage. All components derive locations from this class
 * so that installation, execution and cleanup agree on one directory scheme.
 */
public final class RuntimePaths {

    private final File baseDir;

    /**
     * Creates the path layout rooted at the given files directory.
     *
     * @param filesDir application private files directory acting as the base
     */
    public RuntimePaths(File filesDir) {
        this.baseDir = new File(filesDir, "runtime");
    }

    /**
     * Returns the base directory holding the PRoot runtime assets.
     *
     * @return the base directory
     */
    public File baseDir() {
        return baseDir;
    }

    /**
     * Returns the PRoot executable location.
     *
     * @return the PRoot binary file
     */
    public File prootBinary() {
        return new File(baseDir, "proot");
    }

    /**
     * Returns the PRoot loader executable location.
     *
     * @return the loader binary file
     */
    public File prootLoader() {
        return new File(baseDir, "proot-loader");
    }

    /**
     * Returns the directory holding PRoot native libraries.
     *
     * @return the native library directory (talloc, Android shmem)
     */
    public File prootLibDir() {
        return new File(baseDir, "lib");
    }

    /**
     * Returns the scratch directory published as {@code PROOT_TMP_DIR}.
     *
     * @return the PRoot temporary directory
     */
    public File prootTmpDir() {
        return new File(baseDir, "tmp");
    }

    /**
     * Returns the directory containing the Ubuntu rootfs and its metadata files.
     *
     * @return the Ubuntu directory
     */
    public File ubuntuDir() {
        return new File(baseDir.getParentFile(), "ubuntu");
    }

    /**
     * Returns the extracted Ubuntu rootfs directory.
     *
     * @return the rootfs directory
     */
    public File rootfsDir() {
        return new File(ubuntuDir(), "rootfs");
    }

    /**
     * Returns the staging directory used while extracting a fresh rootfs.
     *
     * @return the temporary rootfs directory
     */
    public File rootfsTempDir() {
        return new File(ubuntuDir(), "rootfs.tmp");
    }

    /**
     * Returns the marker file recording the installed rootfs version and digest.
     *
     * @return the rootfs marker file
     */
    public File rootfsMarker() {
        return new File(ubuntuDir(), "rootfs.marker");
    }

    /**
     * Returns the backup location for {@code /root/workspace} during reinstall.
     *
     * @return the workspace backup directory
     */
    public File workspaceBackupDir() {
        return new File(ubuntuDir(), "workspace.backup");
    }

    /**
     * Returns the guest home directory; LineCode uses it as the project root in
     * terminal provider mode.
     *
     * @return the guest home path, {@code /root/workspace}
     */
    public String homePath() {
        return "/root/workspace";
    }
}
