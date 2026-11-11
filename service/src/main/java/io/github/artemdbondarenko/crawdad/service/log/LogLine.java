package io.github.artemdbondarenko.crawdad.service.log;

import java.time.temporal.ChronoUnit;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Writes every entry of the service log on one line ({@code LOG-3}): the time in UTC, the level and the
 * message, then the failure and its causes, if any, as one escaped value instead of a stack trace. The
 * service's own messages escape their values themselves ({@link LogText#safe}); a line of a library, such as
 * Jetty, cannot tell its values apart and is escaped whole with {@link LogText#escape}.
 */
public final class LogLine extends Formatter {

    /** The loggers whose messages escape their own values. */
    static final String OWN_LOGGERS = "io.github.artemdbondarenko.crawdad.";

    /** Puts this formatter on every handler of the root logger, as the service starts. */
    public static void install() {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            handler.setFormatter(new LogLine());
        }
    }

    @Override
    public String format(LogRecord record) {
        String message = formatMessage(record);
        String name = record.getLoggerName();
        boolean own = name != null && name.startsWith(OWN_LOGGERS);
        StringBuilder line = new StringBuilder()
                .append(record.getInstant().truncatedTo(ChronoUnit.MILLIS))
                .append(' ')
                .append(level(record.getLevel()))
                .append(": ")
                .append(own ? message : LogText.escape(message));
        if (record.getThrown() != null) {
            line.append(": ").append(LogText.cause(record.getThrown()));
        }
        return line.append(System.lineSeparator()).toString();
    }

    /** The levels of the specification ({@code LOG-1}): {@code ERROR}, {@code WARNING}, {@code INFO}, below them {@code DEBUG}. */
    static String level(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARNING";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO";
        }
        return "DEBUG";
    }
}
