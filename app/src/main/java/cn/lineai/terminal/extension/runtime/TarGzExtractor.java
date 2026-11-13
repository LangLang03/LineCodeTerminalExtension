package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Extracts the embedded Ubuntu Base tarball with guest semantics: symbolic and
 * hard links are rebuilt, executable bits are preserved, and no entry may escape
 * the destination directory. Supports ustar and GNU tar, including GNU long
 * names and PAX path overrides, so updated images remain usable.
 */
public final class TarGzExtractor {

    /** Extraction progress callback. */
    public interface Listener {

        /**
         * Reports extraction progress.
         *
         * @param entries entries processed so far
         * @param compressedBytes compressed bytes consumed so far
         * @param currentEntry path of the entry just processed
         */
        void onProgress(long entries, long compressedBytes, String currentEntry);
    }

    private static final int BLOCK = 512;
    private static final int MAX_SYMLINK_HOPS = 40;
    private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;

    private final Listener listener;

    /**
     * Creates an extractor.
     *
     * @param listener optional progress listener
     */
    public TarGzExtractor(Listener listener) {
        this.listener = listener;
    }

    /**
     * Streams the gzip-compressed tar archive into the destination directory.
     *
     * @param tarGz gzip-compressed tar stream; not closed by this call
     * @param destination directory to populate, created when missing
     * @return the number of processed entries
     * @throws IOException on malformed archives, unsafe entry paths, symlink
     *                     escapes, or when the 512 MiB total size cap is exceeded
     */
    public int extract(InputStream tarGz, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("Cannot create extraction directory: " + destination);
        }
        File root = destination.getCanonicalFile();
        CountingInputStream counter = new CountingInputStream(tarGz);
        GZIPInputStream gzip = new GZIPInputStream(counter, 64 * 1024);
        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        int entries = 0;
        long written = 0L;
        try {
            while (true) {
                int read = readFully(gzip, header);
                if (read == 0) {
                    break;
                }
                if (read < BLOCK) {
                    throw new IOException("Truncated tar header (" + read + " bytes)");
                }
                if (isZeroBlock(header)) {
                    break;
                }
                String name = decodeName(header, 0, 100);
                String prefix = decodeName(header, 345, 155);
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                long mode = parseNumber(header, 100, 8, 0);
                long size = parseNumber(header, 124, 12, 0);
                char type = (char) (header[156] & 0xff);
                String linkName = decodeName(header, 157, 100);

                if (type == 'L') {
                    longName = readString(gzip, size);
                    continue;
                }
                if (type == 'K') {
                    longLink = readString(gzip, size);
                    continue;
                }
                if (type == 'x') {
                    String pax = readString(gzip, size);
                    paxPath = paxValue(pax, "path", paxPath);
                    paxLink = paxValue(pax, "linkpath", paxLink);
                    continue;
                }
                if (type == 'g') {
                    skipFully(gzip, size);
                    continue;
                }
                if (longName != null) {
                    path = longName;
                    longName = null;
                }
                if (longLink != null) {
                    linkName = longLink;
                    longLink = null;
                }
                if (paxPath != null) {
                    path = paxPath;
                    paxPath = null;
                }
                if (paxLink != null) {
                    linkName = paxLink;
                    paxLink = null;
                }

                String relative = safeRelativePath(path);
                if (relative == null) {
                    if (isSkippable(type)) {
                        skipFully(gzip, size);
                        continue;
                    }
                    throw new IOException("Unsafe tar entry path: " + path);
                }
                File target = resolveInsideRoot(root, relative);

                if (type == '5') {
                    ensureDirectory(target);
                    applyMode(target, mode, true);
                    skipFully(gzip, size);
                } else if (type == '2') {
                    createSymlink(target, linkName);
                    skipFully(gzip, size);
                } else if (type == '1') {
                    createHardLink(root, target, linkName, mode);
                    skipFully(gzip, size);
                } else if (type == '0' || type == '\0' || type == '7') {
                    written += writeFile(gzip, target, size, mode);
                    if (written > MAX_TOTAL_BYTES) {
                        throw new IOException("Refusing to extract more than " + MAX_TOTAL_BYTES
                                + " bytes");
                    }
                } else {
                    // Device, FIFO and similar entries must not appear in the app sandbox.
                    skipFully(gzip, size);
                }

                entries++;
                if (listener != null) {
                    listener.onProgress(entries, counter.count(), relative);
                }
            }
        } finally {
            try {
                gzip.close();
            } catch (IOException ignored) {
                // The real failure is reported by the caller.
            }
        }
        return entries;
    }

    /** Reports whether a non-regular entry type is silently skipped. */
    private static boolean isSkippable(char type) {
        return type == '3' || type == '4' || type == '6' || type == 'D';
    }

    /** Reports whether a header block is the all-zero end-of-archive marker. */
    private static boolean isZeroBlock(byte[] header) {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /** Decodes a NUL-terminated header field as UTF-8. */
    private static String decodeName(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    /** Parses an octal header field, supporting the GNU base-256 extension. */
    private static long parseNumber(byte[] header, int offset, int length, long fallback) {
        if ((header[offset] & 0x80) != 0) {
            long value = 0L;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xffL);
            }
            return value;
        }
        long value = 0L;
        boolean seen = false;
        for (int i = offset; i < offset + length; i++) {
            int b = header[i] & 0xff;
            if (b == 0 || b == ' ') {
                if (seen) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                break;
            }
            seen = true;
            value = (value << 3) + (b - '0');
        }
        return seen ? value : fallback;
    }

    /** Reads and UTF-8 decodes an inline payload such as a long name or PAX block. */
    private static String readString(InputStream in, long size) throws IOException {
        if (size <= 0 || size > 1024L * 1024L) {
            skipFully(in, size);
            return "";
        }
        byte[] data = new byte[(int) size];
        readFully(in, data);
        skipPadding(in, size);
        int end = data.length;
        while (end > 0 && (data[end - 1] == 0 || data[end - 1] == '\n')) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /** Extracts one key from a PAX extended header block. */
    private static String paxValue(String pax, String key, String fallback) {
        String prefix = key + "=";
        for (String line : pax.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return fallback;
    }

    /**
     * Returns the entry path relative to the destination; null when the entry
     * attempts to escape the root directory.
     */
    static String safeRelativePath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || path.equals(".")) {
            return null;
        }
        if (path.startsWith("/")) {
            return null;
        }
        String[] parts = path.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        if (stack.isEmpty()) {
            return null;
        }
        return String.join("/", stack);
    }

    /**
     * Resolves an entry path inside the root, virtually following existing
     * symbolic links so tar entries cannot write through them onto the host
     * file system.
     */
    private static File resolveInsideRoot(File root, String relative) throws IOException {
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        for (String part : relative.split("/")) {
            if (!part.isEmpty()) {
                pending.addLast(part);
            }
        }
        java.util.ArrayDeque<String> resolved = new java.util.ArrayDeque<>();
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
            StringBuilder path = new StringBuilder();
            for (String component : resolved) {
                path.append('/').append(component);
            }
            path.append('/').append(part);
            File candidate = new File(root, path.substring(1));
            if (Files.isSymbolicLink(candidate.toPath())) {
                if (++hops > MAX_SYMLINK_HOPS) {
                    throw new IOException("Too many symlink levels while extracting: " + relative);
                }
                String target = Files.readSymbolicLink(candidate.toPath()).toString()
                        .replace('\\', '/');
                java.util.ArrayDeque<String> targetParts = new java.util.ArrayDeque<>();
                if (target.startsWith("/")) {
                    resolved.clear();
                }
                for (String targetPart : target.split("/")) {
                    if (!targetPart.isEmpty()) {
                        targetParts.addLast(targetPart);
                    }
                }
                while (!targetParts.isEmpty()) {
                    pending.addFirst(targetParts.removeLast());
                }
                continue;
            }
            resolved.addLast(part);
        }
        File result = root;
        for (String component : resolved) {
            result = new File(result, component);
        }
        return result;
    }

    /** Consumes the block padding following an entry payload. */
    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        long skipped = 0L;
        while (skipped < padding) {
            long step = in.skip(padding - skipped);
            if (step <= 0) {
                if (in.read() < 0) {
                    break;
                }
                skipped++;
            } else {
                skipped += step;
            }
        }
    }

    /** Creates a directory, tolerating concurrent creation. */
    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    /** Creates a symbolic link, replacing any existing entry at the target. */
    private static void createSymlink(File target, String linkName) throws IOException {
        ensureDirectory(target.getParentFile());
        Files.deleteIfExists(target.toPath());
        Path link = target.toPath();
        Path targetPath = java.nio.file.Paths.get(linkName);
        try {
            Files.createSymbolicLink(link, targetPath);
        } catch (UnsupportedOperationException | IOException error) {
            throw new IOException("Cannot create symlink " + target + " -> " + linkName, error);
        }
    }

    /**
     * Creates a hard link, degrading to a byte copy where the platform forbids it.
     */
    private static void createHardLink(File root, File target, String linkName, long mode)
            throws IOException {
        String relative = safeRelativePath(linkName);
        if (relative == null) {
            throw new IOException("Unsafe hardlink target: " + linkName);
        }
        File source = resolveInsideRoot(root, relative);
        if (!source.isFile()) {
            throw new IOException("Hardlink target missing: " + linkName);
        }
        ensureDirectory(target.getParentFile());
        Files.deleteIfExists(target.toPath());
        try {
            Files.createLink(target.toPath(), source.toPath());
            return;
        } catch (IOException | UnsupportedOperationException error) {
            // On Android 12+ SELinux forbids hard links in app data directories
            // (untrusted_app_27 logs "avc: denied { link }"), so degrade to a byte
            // copy. Ubuntu Base contains only two hard links; the cost is negligible.
        }
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        applyMode(target, mode, false);
    }

    /** Writes a regular file payload, replacing a pre-existing symlink at the target. */
    private static long writeFile(InputStream in, File target, long size, long mode)
            throws IOException {
        ensureDirectory(target.getParentFile());
        if (Files.isSymbolicLink(target.toPath())) {
            Files.delete(target.toPath());
        }
        long remaining = size;
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, chunk);
                if (read <= 0) {
                    throw new IOException("Unexpected end of tar data for " + target);
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        applyMode(target, mode, false);
        skipPadding(in, size);
        return size;
    }

    /** Applies stored permission bits with a java.io executable-bit fallback. */
    private static void applyMode(File target, long mode, boolean directory) {
        int bits = (int) (mode & 0777);
        if (directory) {
            bits |= 0700;
        }
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((bits & 0400) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((bits & 0200) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((bits & 0100) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((bits & 0040) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((bits & 0020) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((bits & 0010) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((bits & 0004) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((bits & 0002) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((bits & 0001) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        try {
            Files.setPosixFilePermissions(target.toPath(), permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Some file systems (FUSE, sdcard) reject POSIX permissions; the java.io
            // fallback only preserves the executable bit, which suffices for the
            // bundled shell tools.
            if ((bits & 0111) != 0) {
                //noinspection ResultOfMethodCallIgnored
                target.setExecutable(true, false);
            }
        }
    }

    /** Reads until the buffer is full or the stream ends. */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /** Consumes an entry payload plus its block padding from the stream. */
    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            remaining -= read;
        }
        long padding = (BLOCK - (bytes % BLOCK)) % BLOCK;
        long padded = padding;
        while (padded > 0) {
            long skipped = in.skip(padded);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                padded--;
            } else {
                padded -= skipped;
            }
        }
    }

    /** Input stream wrapper counting the compressed bytes consumed so far. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private long count;

        /** Wraps the given delegate stream. */
        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        /** Returns the number of bytes read through this stream. */
        long count() {
            return count;
        }

        /** Counts and forwards a single-byte read. */
        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        /** Counts and forwards a bulk read. */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        /** Counts and forwards a skip. */
        @Override
        public long skip(long bytes) throws IOException {
            long skipped = delegate.skip(bytes);
            if (skipped > 0) {
                count += skipped;
            }
            return skipped;
        }

        /** Closes the delegate stream. */
        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
