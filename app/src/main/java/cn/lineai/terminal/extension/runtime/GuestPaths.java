package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 guest Linux 路径（LineCode 经 IPC 使用的命名空间）映射为已解压 rootfs 内的宿主路径。
 * 符号链接按 guest 语义解析：绝对链接目标以 rootfs 为根，不会落到宿主文件系统。
 */
public final class GuestPaths {

    private static final int MAX_SYMLINK_HOPS = 40;

    private final File rootfs;

    public GuestPaths(File rootfsDir) {
        this.rootfs = rootfsDir.getAbsoluteFile();
    }

    public File rootfs() {
        return rootfs;
    }

    /**
     * 规范化绝对 guest 路径：{@code ..} 在根处截断；相对路径与空路径直接拒绝，
     * 因为 IPC 契约只传绝对路径。
     */
    public static String normalize(String guestPath) {
        if (guestPath == null || guestPath.isEmpty() || guestPath.charAt(0) != '/') {
            throw new IllegalArgumentException("Guest path must be absolute: " + guestPath);
        }
        if (guestPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Guest path contains NUL");
        }
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (String part : guestPath.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.addLast(part);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder out = new StringBuilder();
        for (String part : stack) {
            out.append('/').append(part);
        }
        return out.toString();
    }

    /** 将 guest 路径解析为宿主文件，符号链接在 rootfs 内按 guest 语义虚拟解析。 */
    public File resolve(String guestPath) throws IOException {
        String normalized = normalize(guestPath);
        ArrayDeque<String> pending = segments(normalized);
        ArrayDeque<String> resolved = new ArrayDeque<>();
        int hops = 0;
        while (!pending.isEmpty()) {
            String part = pending.removeFirst();
            if (part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!resolved.isEmpty()) {
                    resolved.removeLast();
                }
                continue;
            }
            File candidate = child(resolved, part);
            if (Files.isSymbolicLink(candidate.toPath())) {
                if (++hops > MAX_SYMLINK_HOPS) {
                    throw new IOException("Too many symlink levels: " + guestPath);
                }
                String target = Files.readSymbolicLink(candidate.toPath()).toString()
                        .replace('\\', '/');
                if (target.startsWith("/")) {
                    resolved.clear();
                }
                List<String> targetParts = new ArrayList<>();
                for (String targetPart : target.split("/")) {
                    if (!targetPart.isEmpty()) {
                        targetParts.add(targetPart);
                    }
                }
                for (int i = targetParts.size() - 1; i >= 0; i--) {
                    pending.addFirst(targetParts.get(i));
                }
                continue;
            }
            resolved.addLast(part);
        }
        File result = rootfs;
        for (String part : resolved) {
            result = new File(result, part);
        }
        return result;
    }

    /**
     * 解析写入路径并创建缺失的父目录；创建后校验父目录仍位于 rootfs 内。
     */
    public File resolveForWrite(String guestPath) throws IOException {
        File target = resolve(guestPath);
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IOException("Guest path has no parent: " + guestPath);
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create parent directory: " + guestPath);
        }
        requireInsideRoot(parent);
        return target;
    }

    /** 返回 rootfs 内宿主文件对应的 guest 路径；位于 rootfs 之外时返回 null。 */
    public String toGuest(File hostFile) {
        try {
            File canonicalRoot = rootfs.getCanonicalFile();
            String rootPath = canonicalRoot.getPath() + File.separator;
            String canonicalHost = hostFile.getCanonicalFile().getPath();
            if (!canonicalHost.startsWith(rootPath)) {
                return null;
            }
            return "/" + canonicalHost.substring(rootPath.length()).replace(File.separatorChar, '/');
        } catch (IOException error) {
            return null;
        }
    }

    private void requireInsideRoot(File file) throws IOException {
        File canonicalRoot = rootfs.getCanonicalFile();
        String rootPath = canonicalRoot.getPath() + File.separator;
        String canonicalFile = file.getCanonicalFile().getPath();
        if (!canonicalFile.startsWith(rootPath)) {
            throw new IOException("Path escapes the rootfs: " + file);
        }
    }

    private File child(ArrayDeque<String> resolved, String part) {
        File result = rootfs;
        for (String component : resolved) {
            result = new File(result, component);
        }
        return new File(result, part);
    }

    private static ArrayDeque<String> segments(String absolutePath) {
        ArrayDeque<String> result = new ArrayDeque<>();
        for (String part : absolutePath.split("/")) {
            if (!part.isEmpty()) {
                result.addLast(part);
            }
        }
        return result;
    }
}
