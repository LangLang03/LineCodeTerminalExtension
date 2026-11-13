package cn.lineai.terminal.extension.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 经 {@code ITerminalProviderService} 暴露的文件操作。路径为以 Ubuntu rootfs 为根的
 * guest Linux 路径；行为对齐 LineCode 参考终端 provider：1 MB 分块、单次读取上限 32 MB、
 * offset 为 -1 时追加、删除不递归。
 */
public final class GuestFileSystem {

    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = DEFAULT_CHUNK_SIZE;
    private static final long MAX_READ_FILE_BYTES = 32L * 1024L * 1024L;

    private final GuestPaths paths;
    private final String home;

    public GuestFileSystem(File rootfsDir, String homePath) {
        this.paths = new GuestPaths(rootfsDir);
        this.home = homePath;
    }

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

    public boolean writeFile(String path, byte[] data) {
        return writeFileChunk(path, 0L, data);
    }

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

    public long getFileSize(String path) {
        return fileSize(path);
    }

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

    private File resolveReadable(String path) throws IOException {
        return paths.resolve(path);
    }

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
