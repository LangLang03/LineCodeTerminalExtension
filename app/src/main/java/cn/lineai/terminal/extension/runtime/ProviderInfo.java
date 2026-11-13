package cn.lineai.terminal.extension.runtime;

/** 构建 {@code getProviderInfo()} 返回的 JSON 文档，供 LineCode 经 IPC 读取。 */
public final class ProviderInfo {

    public static final String NAME = "LineCode Terminal Extension";
    public static final String VERSION = "1.0.0";
    public static final String DISTRO = "Ubuntu 22.04.5 LTS (jammy)";
    public static final String ARCH = "aarch64";
    public static final String SHELL = "/bin/bash";

    private ProviderInfo() {
    }

    public static String json(String homePath) {
        return "{\"name\":" + Json.quote(NAME)
                + ",\"version\":" + Json.quote(VERSION)
                + ",\"shell\":" + Json.quote(SHELL)
                + ",\"home\":" + Json.quote(homePath)
                + ",\"distro\":" + Json.quote(DISTRO)
                + ",\"arch\":" + Json.quote(ARCH)
                + ",\"capabilities\":["
                + "\"executeShell\","
                + "\"readFile\","
                + "\"writeFile\","
                + "\"deleteFile\","
                + "\"listDir\","
                + "\"fileExists\","
                + "\"fileSize\","
                + "\"readFileChunk\","
                + "\"writeFileChunk\","
                + "\"getFileSize\","
                + "\"listDirDetailed\"]}";
    }
}
