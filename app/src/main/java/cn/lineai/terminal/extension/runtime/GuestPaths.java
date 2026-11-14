package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Maps guest Linux paths (the namespace used by LineCode over IPC) onto host
 * paths inside the extracted rootfs. Symbolic links are resolved with guest
 * semantics: absolute link targets are re-rooted at the rootfs, so resolution
 * can never escape onto the host file system.
 */
public final class GuestPaths {

    private static final int MAX_SYMLINK_HOPS = 40;

    /** Guest prefixes that resolve to the host shared volume instead of the rootfs. */
    private static final String[] EXTERNAL_PREFIXES = {
            ExternalStorage.GUEST_SDCARD,
            ExternalStorage.GUEST_ANDROID_ROOT,
            "/storage/self/primary"
    };

    private final File rootfs;
    private final Supplier<File> externalRoot;

    /**
     * Creates a mapper confined to the rootfs; shared storage is not exposed.
     *
     * @param rootfsDir host directory of the extracted rootfs
     */
    public GuestPaths(File rootfsDir) {
        this(rootfsDir, null);
    }

    /**
     * Creates a mapper that also exposes the host shared volume under {@code /sdcard}
     * and {@code /storage/emulated/0} while the supplier returns a usable root.
     *
     * @param rootfsDir host directory of the extracted rootfs
     * @param externalRoot supplier of the host shared storage root, or {@code null}
     */
    public GuestPaths(File rootfsDir, Supplier<File> externalRoot) {
        this.rootfs = rootfsDir.getAbsoluteFile();
        this.externalRoot = externalRoot;
    }

    /**
     * Returns the host shared storage root when the guest mapping is active.
     *
     * @return the external storage root, or {@code null} when unavailable
     */
    public File externalRoot() {
        return externalRoot == null ? null : externalRoot.get();
    }

    /**
     * Reports whether a guest path addresses the shared volume rather than the rootfs.
     *
     * @param normalized absolute normalized guest path
     * @return {@code true} when the path starts with a shared-storage prefix
     */
    public static boolean isExternalPath(String normalized) {
        for (String prefix : EXTERNAL_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
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
        File external = externalRoot();
        if (external != null && isExternalPath(normalized)) {
            return resolveExternal(external, normalized);
        }
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
        String normalized = normalize(guestPath);
        File external = externalRoot();
        if (external != null && isExternalPath(normalized)) {
            requireInside(external, parent, guestPath);
        } else {
            requireInside(rootfs, parent, guestPath);
        }
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
            String externalPath = guestPathIn(externalRoot(), ExternalStorage.GUEST_SDCARD, hostFile);
            if (externalPath != null) {
                return externalPath;
            }
            return guestPathIn(rootfs, "", hostFile);
        } catch (IOException error) {
            return null;
        }
    }

    /** Maps a host file below {@code root} to a guest path, or returns null when outside. */
    private static String guestPathIn(File root, String prefix, File hostFile) throws IOException {
        if (root == null) {
            return null;
        }
        File canonicalRoot = root.getCanonicalFile();
        String rootPath = canonicalRoot.getPath() + File.separator;
        String canonicalHost = hostFile.getCanonicalFile().getPath();
        if (!canonicalHost.startsWith(rootPath)) {
            return null;
        }
        return prefix + "/" + canonicalHost.substring(rootPath.length())
                .replace(File.separatorChar, '/');
    }

    /**
     * Resolves a shared-storage guest path to the host volume and rejects escapes that
     * would land outside it (for example a symlink planted on the shared volume).
     */
    private static File resolveExternal(File root, String normalized) throws IOException {
        StringBuilder relative = new StringBuilder();
        for (String prefix : EXTERNAL_PREFIXES) {
            if (normalized.equals(prefix)) {
                return root;
            }
            if (normalized.startsWith(prefix + "/")) {
                relative.append(normalized.substring(prefix.length() + 1));
                break;
            }
        }
        File target = root;
        for (String part : segments(relative.toString())) {
            target = new File(target, part);
        }
        File probe = target;
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        if (probe != null) {
            requireInside(root, probe, normalized);
        }
        return target;
    }

    /** Rejects paths whose canonical form escapes the given containment root. */
    private static void requireInside(File root, File file, String guestPath) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        String rootPath = canonicalRoot.getPath() + File.separator;
        String canonicalFile = file.getCanonicalFile().getPath();
        if (!canonicalFile.equals(canonicalRoot.getPath()) && !canonicalFile.startsWith(rootPath)) {
            throw new IOException("Path escapes its mount: " + guestPath);
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
