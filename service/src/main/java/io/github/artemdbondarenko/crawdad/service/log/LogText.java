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
        StringBuilder out = new StringBuilder(kept + 16);
        for (int i = 0; i < kept; i++) {
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
        if (kept < value.length()) {
            out.append("…(+").append(value.length() - kept).append(')');
        }
        return out.toString();
    }

    /** Line and paragraph separators and the bidirectional controls that reorder what a terminal shows. */
    private static boolean changesLayout(char c) {
        return c == 0x2028 || c == 0x2029 || c == 0x200E || c == 0x200F
                || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
