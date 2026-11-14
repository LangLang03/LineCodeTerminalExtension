package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps guest Linux paths (the namespace used by LineCode over IPC) onto host
 * paths inside the extracted rootfs. Symbolic links are resolved with guest
 * semantics: absolute link targets are re-rooted at the rootfs, so resolution
 * can never escape onto the host file system.
 */
public final class GuestPaths {

    private static final int MAX_SYMLINK_HOPS = 40;

    private final File rootfs;

    /**
     * Creates a mapper rooted at the given rootfs directory.
     *
     * @param rootfsDir host directory of the extracted rootfs
     */
    public GuestPaths(File rootfsDir) {
        this.rootfs = rootfsDir.getAbsoluteFile();
    }

    /**
     * Returns the rootfs base directory all resolutions are confined to.
     *
     * @return the rootfs directory
     */
    public File rootfs() {
        return rootfs;
    }

    /**
     * Normalizes an absolute guest path: {@code ..} components are clamped at
     * the root. Relative and empty paths are rejected because the IPC contract
     * only carries absolute paths.
     *
     * @param guestPath candidate absolute guest path
     * @return the normalized absolute path
     * @throws IllegalArgumentException when the path is relative or empty, or
     *                                  contains a NUL character
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

    /**
     * Resolves a guest path to a host file, resolving symbolic links virtually
     * within the rootfs according to guest semantics. The returned file may not
     * exist yet.
     *
     * @param guestPath absolute guest path
     * @return the mapped host path
     * @throws IOException when the path traverses too many symbolic links
     */
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
     * Resolves a guest path for writing and creates missing parent directories;
     * after creation the parent is re-verified to remain inside the rootfs.
     *
     * @param guestPath absolute guest path to resolve
     * @return the mapped host file target
     * @throws IOException when parent directories cannot be created or would
     *                     leave the rootfs
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

    /**
     * Maps a host file back to its guest path.
     *
     * @param hostFile host file to map
     * @return the corresponding guest path, or null when the file lies outside
     *         the rootfs
     */
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

    /** Rejects paths whose canonical form escapes the rootfs boundary. */
    private void requireInsideRoot(File file) throws IOException {
        File canonicalRoot = rootfs.getCanonicalFile();
        String rootPath = canonicalRoot.getPath() + File.separator;
        String canonicalFile = file.getCanonicalFile().getPath();
        if (!canonicalFile.startsWith(rootPath)) {
            throw new IOException("Path escapes the rootfs: " + file);
        }
    }

    /** Builds the host file for a candidate segment appended to the resolved prefix. */
    private File child(ArrayDeque<String> resolved, String part) {
        File result = rootfs;
        for (String component : resolved) {
            result = new File(result, component);
        }
        return new File(result, part);
    }

    /** Splits an absolute path into its non-empty segments. */
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
