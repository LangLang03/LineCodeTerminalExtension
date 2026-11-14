package cn.lineai.terminal.extension.runtime;

/**
 * Minimal JSON string escaping for the small documents this application
 * assembles itself. Covers the escape set required for well-formed JSON string
 * values; remaining control characters are emitted as {@code \\uXXXX} sequences.
 */
public final class Json {

    /** Suppresses instantiation; all members are static. */
    private Json() {
    }

    /**
     * Quotes a value as a JSON string literal, escaping quotes, backslashes and
     * control characters; a {@code null} value maps to the literal {@code null}.
     *
     * @param value value to quote
     * @return the quoted JSON representation, or {@code "null"} for a null input
     */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.append('"').toString();
    }
}
