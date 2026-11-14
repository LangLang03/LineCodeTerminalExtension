package cn.lineai.terminal.extension.runtime;

/**
 * Offset-addressed output ring buffer behind the background-shell read API.
 *
 * <p>The buffer keeps a contiguous prefix of the guest output and serves polled reads by
 * byte offset: {@link #chunkFrom(long)} returns everything recorded after the requested
 * offset, so the host can consume the stream incrementally without re-reading bytes it has
 * already seen. Once the buffer reaches the cap, appending stops (the prefix is kept) so
 * offset semantics stay stable for already-published bytes; the host is expected to poll
 * faster than the cap fills for long-running tasks.
 */
final class OutputBuffer {

    /** Maximum retained characters; the prefix is kept once the cap is reached. */
    static final int MAX_CHARS = 512 * 1024;

    private final StringBuilder buffer = new StringBuilder();
    private boolean truncated;

    /** Appends a chunk; the caller synchronizes externally. */
    void append(String text) {
        if (buffer.length() >= MAX_CHARS) {
            truncated = true;
            return;
        }
        buffer.append(text);
        if (buffer.length() > MAX_CHARS) {
            buffer.setLength(MAX_CHARS);
            truncated = true;
        }
    }

    /**
     * Returns the output recorded after {@code offset}.
     *
     * @param offset consumed byte count reported by the host
     * @return the not-yet-consumed suffix; empty when offset is past the end or negative
     */
    synchronized String chunkFrom(long offset) {
        if (offset < 0 || offset >= buffer.length()) {
            return "";
        }
        return buffer.substring((int) offset);
    }

    /** Returns the total recorded character count; the host never needs it, tests do. */
    synchronized long length() {
        return buffer.length();
    }

    /** Returns true when the cap was hit and later output was dropped. */
    synchronized boolean isTruncated() {
        return truncated;
    }
}
