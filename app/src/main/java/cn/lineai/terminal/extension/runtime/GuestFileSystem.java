package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * File operations exposed through {@code ITerminalProviderService}. Paths are
 * guest Linux paths rooted at the Ubuntu rootfs; behavior mirrors the LineCode
 * reference terminal provider: 1 MiB chunks, a 32 MiB single-read cap, negative
 * offsets meaning append, and non-recursive deletion. Operations fail closed,
 * returning empty results or {@code false} instead of throwing across IPC.
 */
public final class GuestFileSystem {

    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = DEFAULT_CHUNK_SIZE;
    private static final long MAX_READ_FILE_BYTES = 32L * 1024L * 1024L;

    private final GuestPaths paths;
    private final String home;

    /**
     * Creates the facade over the given rootfs.
     *
     * @param rootfsDir host directory of the extracted rootfs
     * @param homePath guest home directory used as the default for listing
     */
    public GuestFileSystem(File rootfsDir, String homePath) {
        this(new GuestPaths(rootfsDir), homePath);
    }

    /**
     * Creates the facade over an explicit path mapper, including optional shared-storage
     * mounts.
     *
     * @param paths guest-to-host path mapper
     * @param homePath guest home directory used as the default for listing
     */
    public GuestFileSystem(GuestPaths paths, String homePath) {
        this.paths = paths;
        this.home = homePath;
    }

    /**
     * Reads a whole guest file, up to the 32 MiB single-read cap.
     *
     * @param path absolute guest path
     * @return the file contents, or an empty array when the path is missing,
     *         oversized or unreadable
     */
    public byte[] readFile(String path) {
        if (path == null || path.isEmpty()) {
            return new byte[0];
        }
        try {
            File file = resolveReadable(path);
            if (file == null || !file.isFile()) {
                return new byte[0];
            }
            long size = file.length();
            if (size <= 0L || size > MAX_READ_FILE_BYTES || size > Integer.MAX_VALUE) {
                return new byte[0];
            }
            int total = (int) size;
            byte[] data = new byte[total];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int offset = 0;
                while (offset < total) {
                    int read = in.read(data, offset, total - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset < total) {
                    byte[] trimmed = new byte[offset];
                    System.arraycopy(data, 0, trimmed, 0, offset);
                    return trimmed;
                }
            }
            return data;
        } catch (IOException | RuntimeException error) {
            return new byte[0];
        }
    }

    /**
     * Writes or truncates a guest file at offset zero.
     *
     * @param path absolute guest path
     * @param data payload to write
     * @return true when the payload was written
     */
    public boolean writeFile(String path, byte[] data) {
        return writeFileChunk(path, 0L, data);
    }

    /**
     * Reads one chunk of a guest file starting at the given offset; a
     * non-positive size selects the default chunk size.
     *
     * @param path absolute guest path
     * @param offset byte offset to read from; negative values are clamped to zero
     * @param size maximum number of bytes to read
     * @return the requested bytes, or an empty array when unavailable
     */
    public byte[] readFileChunk(String path, long offset, int size) {
        if (path == null || path.isEmpty()) {
            return new byte[0];
        }
        if (offset < 0L) {
            offset = 0L;
        }
        int want = size <= 0 ? DEFAULT_CHUNK_SIZE : size;
        if (want > MAX_CHUNK_SIZE) {
            want = MAX_CHUNK_SIZE;
        }
        try {
            File file = resolveReadable(path);
            if (file == null || !file.isFile()) {
                return new byte[0];
            }
            long length = file.length();
            if (offset >= length) {
                return new byte[0];
            }
            int toRead = (int) Math.min((long) want, length - offset);
            byte[] out = new byte[toRead];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                int read = 0;
                while (read < toRead) {
                    int step = raf.read(out, read, toRead - read);
                    if (step <= 0) {
                        break;
                    }
                    read += step;
                }
                if (read < toRead) {
                    byte[] trimmed = new byte[read];
                    System.arraycopy(out, 0, trimmed, 0, read);
                    return trimmed;
                }
            }
            return out;
        } catch (IOException | RuntimeException error) {
            return new byte[0];
        }
    }

    /**
     * Writes one chunk at an explicit offset, or appends when the offset is
     * negative. Missing parent directories are created.
     *
     * @param path absolute guest path
     * @param offset byte offset to write at; negative values append
     * @param data payload to write
     * @return true when the payload was written
     */
    public boolean writeFileChunk(String path, long offset, byte[] data) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        byte[] payload = data == null ? new byte[0] : data;
        try {
            File file = paths.resolveForWrite(path);
            if (file.isDirectory()) {
                return false;
            }
            if (offset < 0L) {
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(payload);
                    out.flush();
                }
                return true;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(offset);
                raf.write(payload);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /**
     * Deletes a single guest file; directories are not traversed.
     *
     * @param path absolute guest path
     * @return true when the file was removed
     */
    public boolean deleteFile(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            File file = paths.resolve(path);
            if (!file.exists()) {
                return false;
            }
            return Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /**
     * Lists the entry names of a guest directory.
     *
     * @param path absolute guest path; the guest home is used when empty
     * @return entry names, or an empty array when the path is not a directory
     */
    public String[] listDir(String path) {
        File dir = resolveDir(path);
        if (dir == null) {
            return new String[0];
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return new String[0];
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names.toArray(new String[0]);
    }

    /**
     * Tests existence of a guest path.
     *
     * @param path absolute guest path
     * @return true when the mapped host file exists
     */
    public boolean fileExists(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            File file = paths.resolve(path);
            return file.exists();
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /**
     * Returns the size of a guest file.
     *
     * @param path absolute guest path
     * @return the file size in bytes, or -1 when the path is not a regular file
     */
    public long fileSize(String path) {
        if (path == null || path.isEmpty()) {
            return -1L;
        }
        try {
            File file = paths.resolve(path);
            if (!file.isFile()) {
                return -1L;
            }
            return file.length();
        } catch (IOException | RuntimeException error) {
            return -1L;
        }
    }

    /**
     * Alias of {@link #fileSize(String)} kept for symmetry with the AIDL contract.
     *
     * @param path absolute guest path
     * @return the file size in bytes, or -1 when the path is not a regular file
     */
    public long getFileSize(String path) {
        return fileSize(path);
    }

    /**
     * Lists a guest directory as a JSON array with name, type and size per entry.
     *
     * @param path absolute guest path; the guest home is used when empty
     * @return a JSON array document, possibly empty
     */
    public String listDirDetailed(String path) {
        File dir = resolveDir(path);
        StringBuilder out = new StringBuilder("[");
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (out.length() > 1) {
                        out.append(',');
                    }
                    out.append("{\"name\":").append(Json.quote(file.getName()))
                            .append(",\"dir\":").append(file.isDirectory())
                            .append(",\"size\":").append(file.length())
                            .append('}');
                }
            }
        }
        return out.append(']').toString();
    }

    /** Resolves a guest path for reading via {@link GuestPaths#resolve(String)}. */
    private File resolveReadable(String path) throws IOException {
        return paths.resolve(path);
    }

    /** Resolves a guest directory, defaulting to the guest home for empty paths. */
    private File resolveDir(String path) {
        try {
            String target = path == null || path.isEmpty() ? home : path;
            File file = paths.resolve(target);
            if (!file.isDirectory()) {
                return null;
            }
            return file;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }
}
