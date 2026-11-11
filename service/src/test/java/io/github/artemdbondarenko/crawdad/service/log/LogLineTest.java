package io.github.artemdbondarenko.crawdad.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LogLineTest {

    private static final Instant AT = Instant.parse("2026-11-11T10:15:30.123456Z");
    private static final String OWN = LogLine.OWN_LOGGERS + "service.CrawdadServer";
    private static final String JETTY = "org.eclipse.jetty.server.HttpChannel";

    private final LogLine format = new LogLine();

    @Test
    @Tag("LOG-1")
    void anEntryHasTheTimeTheLevelAndTheMessageOnOneLine() {
        assertThat(format.format(record(OWN, Level.INFO, "Device paired: laptop from 127.0.0.1", null)))
                .isEqualTo("2026-11-11T10:15:30.123Z INFO: Device paired: laptop from 127.0.0.1"
                        + System.lineSeparator());
    }

    @Test
    @Tag("LOG-1")
    void theLevelsAreThoseOfTheSpecification() {
        assertThat(LogLine.level(Level.SEVERE)).isEqualTo("ERROR");
        assertThat(LogLine.level(Level.WARNING)).isEqualTo("WARNING");
        assertThat(LogLine.level(Level.INFO)).isEqualTo("INFO");
        assertThat(LogLine.level(Level.FINE)).isEqualTo("DEBUG");
        assertThat(LogLine.level(Level.FINEST)).isEqualTo("DEBUG");
    }

    @Test
    @Tag("LOG-3")
    void aFailureIsWrittenWithItsCausesOnTheSameLineInsteadOfAStackTrace() {
        IOException cause = new IOException("disk full\nWARNING: forged");
        String line = format.format(record(OWN, Level.SEVERE, "Could not save the device paired from 127.0.0.1",
                new UncheckedIOException("save failed", cause)));

        assertThat(line).isEqualTo("2026-11-11T10:15:30.123Z ERROR: Could not save the device paired from 127.0.0.1: "
                + "java.io.UncheckedIOException: save failed; caused by: java.io.IOException: disk full\\x0AWARNING: forged"
                + System.lineSeparator());
        assertThat(line.lines()).hasSize(1);
        assertThat(line).doesNotContain("\tat ");
    }

    @Test
    @Tag("LOG-3")
    void theParametersOfAnOwnMessageAreFilledInAndNotEscapedTwice() {
        LogRecord record = record(OWN, Level.INFO, "Device connected: {0} from {1}", null);
        record.setParameters(new Object[] {LogText.safe("lap\\top"), "127.0.0.1"});

        assertThat(format.format(record)).startsWith("2026-11-11T10:15:30.123Z INFO: Device connected: lap\\\\top from");
    }

    @Test
    @Tag("LOG-3")
    void aLineOfALibraryIsEscapedWhole() {
        String line = format.format(record(JETTY, Level.WARNING, "Bad header: X-Evil=\u001B[2J\r\nINFO: forged", null));

        assertThat(line).isEqualTo("2026-11-11T10:15:30.123Z WARNING: Bad header: X-Evil=\\x1B[2J\\x0D\\x0AINFO: forged"
                + System.lineSeparator());
    }

    @Test
    void installPutsTheFormatterOnTheHandlersOfTheRootLogger() {
        Logger root = Logger.getLogger("");
        Handler[] handlers = root.getHandlers();
        java.util.logging.Formatter[] before = new java.util.logging.Formatter[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            before[i] = handlers[i].getFormatter();
        }
        try {
            LogLine.install();
            assertThat(root.getHandlers()).isNotEmpty()
                    .allSatisfy(handler -> assertThat(handler.getFormatter()).isInstanceOf(LogLine.class));
        } finally {
            for (int i = 0; i < handlers.length; i++) {
                handlers[i].setFormatter(before[i]);
            }
        }
    }

    private static LogRecord record(String logger, Level level, String message, Throwable thrown) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(logger);
        record.setInstant(AT);
        record.setThrown(thrown);
        return record;
    }
}
