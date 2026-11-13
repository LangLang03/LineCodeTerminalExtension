package cn.lineai.terminal.extension.runtime;

import java.io.File;

/** 内置 PRoot 运行时与 Ubuntu rootfs 的固定路径。 */
public final class RuntimePaths {

    private final File baseDir;

    public RuntimePaths(File filesDir) {
        this.baseDir = new File(filesDir, "runtime");
    }

    public File baseDir() {
        return baseDir;
    }

    public File prootBinary() {
        return new File(baseDir, "proot");
    }

    public File prootLoader() {
        return new File(baseDir, "proot-loader");
    }

    public File prootLibDir() {
        return new File(baseDir, "lib");
    }

    public File prootTmpDir() {
        return new File(baseDir, "tmp");
    }

    public File ubuntuDir() {
        return new File(baseDir.getParentFile(), "ubuntu");
    }

    public File rootfsDir() {
        return new File(ubuntuDir(), "rootfs");
    }

    public File rootfsTempDir() {
        return new File(ubuntuDir(), "rootfs.tmp");
    }

    public File rootfsMarker() {
        return new File(ubuntuDir(), "rootfs.marker");
    }

    public File workspaceBackupDir() {
        return new File(ubuntuDir(), "workspace.backup");
    }

    /** guest 侧主目录；LineCode 在终端 provider 模式下将其作为项目根目录。 */
    public String homePath() {
        return "/root/workspace";
    }
}
