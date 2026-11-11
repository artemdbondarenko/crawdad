package io.github.artemdbondarenko.crawdad.service.log;

/**
 * Makes an untrusted value, such as a header or a device name, safe to put into a log line or a
 * terminal: no forged lines, no terminal control, bounded length.
 */
public final class LogText {

    /** The most characters of the original value that are kept. */
    public static final int MAX_LENGTH = 200;

    private LogText() {}

    /**
     * Returns {@code value} with C0, DEL and C1 characters as {@code \xNN}, line separators and
     * direction controls as a backslash, {@code u} and four hex digits, and a backslash doubled. A value longer than
     * {@link #MAX_LENGTH} is cut first, without splitting a surrogate pair, and ends with
     * {@code …(+N)}, N being the characters dropped. {@code null} becomes {@code "null"}.
     */
    public static String safe(String value) {
        if (value == null) {
            return "null";
        }
        int kept = Math.min(value.length(), MAX_LENGTH);
        if (kept < value.length() && Character.isHighSurrogate(value.charAt(kept - 1))) {
            kept--;
        }
        String escaped = escape(value.substring(0, kept));
        return kept < value.length() ? escaped + "…(+" + (value.length() - kept) + ")" : escaped;
    }

    /** As {@link #safe}, but whole: for a line written by a library, whose values cannot be told apart. */
    public static String escape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                out.append("\\x%02X".formatted((int) c));
            } else if (changesLayout(c)) {
                out.append("\\u%04X".formatted((int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The failure and its causes on one line, such as {@code java.io.IOException: disk full; caused by:
     * java.nio.file.AccessDeniedException: /x}, made safe as one value. No stack trace: an entry is one line.
     */
    public static String cause(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) {
                chain.append("; caused by: ");
            }
            chain.append(current);
            current = current.getCause() == current ? null : current.getCause();
        }
        return safe(chain.toString());
    }

    /** Line and paragraph separators and the bidirectional controls that reorder what a terminal shows. */
    private static boolean changesLayout(char c) {
        return c == 0x2028 || c == 0x2029 || c == 0x200E || c == 0x200F
                || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
