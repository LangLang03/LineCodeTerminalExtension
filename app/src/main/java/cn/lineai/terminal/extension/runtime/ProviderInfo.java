package cn.lineai.terminal.extension.runtime;

/**
 * Builds the JSON document returned by {@code getProviderInfo()} and consumed by
 * LineCode over IPC. Field names and capability identifiers mirror the terminal
 * provider contract shared with the LineCode {@code :ipc} module.
 */
public final class ProviderInfo {

    public static final String NAME = "LineCode Terminal Extension";
    public static final String VERSION = "1.2.0";
    public static final String DISTRO = "Ubuntu 22.04.5 LTS (jammy)";
    public static final String ARCH = "aarch64";
    public static final String SHELL = "/bin/bash";

    /** Suppresses instantiation; all members are static. */
    private ProviderInfo() {
    }

    /**
     * Builds the provider descriptor document.
     *
     * @param homePath guest home directory reported to LineCode
     * @return the JSON document describing this provider and its capabilities
     */
    public static String json(String homePath) {
        return "{\"name\":" + Json.quote(NAME)
                + ",\"version\":" + Json.quote(VERSION)
                + ",\"shell\":" + Json.quote(SHELL)
                + ",\"home\":" + Json.quote(homePath)
                + ",\"distro\":" + Json.quote(DISTRO)
                + ",\"arch\":" + Json.quote(ARCH)
                + ",\"externalStorage\":["
                + Json.quote(ExternalStorage.GUEST_SDCARD) + ","
                + Json.quote(ExternalStorage.GUEST_ANDROID_ROOT) + "]"
                + ",\"externalHome\":" + Json.quote(ExternalStorage.GUEST_HOME_LINK)
                + ",\"capabilities\":["
                + "\"executeShell\","
                + "\"backgroundShell\","
                + "\"readFile\","
                + "\"writeFile\","
                + "\"deleteFile\","
                + "\"listDir\","
                + "\"fileExists\","
                + "\"fileSize\","
                + "\"readFileChunk\","
                + "\"writeFileChunk\","
                + "\"getFileSize\","
                + "\"listDirDetailed\","
                + "\"externalStorage\"]}";
    }
}
