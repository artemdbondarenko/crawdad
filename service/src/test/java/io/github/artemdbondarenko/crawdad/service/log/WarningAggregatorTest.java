package io.github.artemdbondarenko.crawdad.service.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarningAggregatorTest {

    private static final String LOGGER = WarningAggregatorTest.class.getName();
    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final String STRANGER = "100.64.0.9";
    private static final String OTHER = "100.64.0.10";

    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler collect = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    // Held in a field: java.util.logging keeps loggers only weakly.
    private final Logger julLogger = Logger.getLogger(LOGGER);
    private final MutableClock clock = new MutableClock(START);
    private final ManualTimer timer = new ManualTimer();
    private final WarningAggregator warnings =
            new WarningAggregator(System.getLogger(LOGGER), INTERVAL, clock, timer);

    @BeforeEach
    void collectLog() {
        julLogger.addHandler(collect);
    }

    @AfterEach
    void stopCollecting() {
        julLogger.removeHandler(collect);
    }

    @Test
    void tenWarningsFromOneSourceInAnIntervalGiveTwoLines() {
        for (int i = 1; i <= 10; i++) {
            warnings.warn(STRANGER, "Wrong pairing code, attempt " + i);
        }

        // The first warning is written at once, the repeats wait for the end of the interval.
        assertThat(messages()).containsExactly("Wrong pairing code, attempt 1 (from 100.64.0.9)");

        clock.advance(INTERVAL);
        timer.fire();

        assertThat(messages()).containsExactly(
                "Wrong pairing code, attempt 1 (from 100.64.0.9)",
                "Wrong pairing code, attempt 10 (repeated 9 more times from 100.64.0.9 in the last 60 s)");
        assertThat(logged).allSatisfy(record -> assertThat(record.getLevel()).isEqualTo(Level.WARNING));
    }

    @Test
    void twoSourcesAreCountedSeparately() {
        for (int i = 0; i < 5; i++) {
            warnings.warn(STRANGER, "WebSocket refused");
            warnings.warn(OTHER, "WebSocket refused");
        }
        assertThat(messages()).containsExactly(
                "WebSocket refused (from 100.64.0.9)", "WebSocket refused (from 100.64.0.10)");

        clock.advance(INTERVAL);
        timer.fire();

        assertThat(messages()).containsExactly(
                "WebSocket refused (from 100.64.0.9)",
                "WebSocket refused (from 100.64.0.10)",
                "WebSocket refused (repeated 4 more times from 100.64.0.9 in the last 60 s)",
                "WebSocket refused (repeated 4 more times from 100.64.0.10 in the last 60 s)");
    }

    @Test
    void aSingleWarningGetsNoSummary() {
        warnings.warn(STRANGER, "WebSocket refused");
        assertThat(timer.delays).containsExactly(INTERVAL);

        clock.advance(INTERVAL);
        timer.fire();

        assertThat(messages()).containsExactly("WebSocket refused (from 100.64.0.9)");
        assertThat(timer.delays).isEmpty();
    }

    @Test
    void afterTheSummaryTheSourceIsForgottenAndStartsAgain() {
        warnings.warn(STRANGER, "WebSocket refused");
        warnings.warn(STRANGER, "WebSocket refused");
        warnings.warn(OTHER, "WebSocket refused");
        assertThat(warnings.tracked()).isEqualTo(2);

        clock.advance(INTERVAL);
        timer.fire();
        assertThat(warnings.tracked()).isZero();

        // A new interval: the next warning is written at once again.
        warnings.warn(STRANGER, "WebSocket refused");
        assertThat(messages()).containsExactly(
                "WebSocket refused (from 100.64.0.9)",
                "WebSocket refused (from 100.64.0.10)",
                "WebSocket refused (repeated 1 more times from 100.64.0.9 in the last 60 s)",
                "WebSocket refused (from 100.64.0.9)");
        assertThat(timer.delays).containsExactly(INTERVAL);
    }

    @Test
    void aTimerThatFiresEarlyWaitsForTheRestOfTheInterval() {
        warnings.warn(STRANGER, "WebSocket refused");
        clock.advance(Duration.ofSeconds(20));
        warnings.warn(STRANGER, "WebSocket refused");

        clock.advance(Duration.ofSeconds(10));
        timer.fire();
        assertThat(messages()).hasSize(1);
        assertThat(timer.delays).containsExactly(Duration.ofSeconds(30));

        // Repeats keep counting until the interval that began with the first warning ends.
        warnings.warn(STRANGER, "WebSocket refused");
        clock.advance(Duration.ofSeconds(30));
        timer.fire();
        assertThat(messages()).containsExactly(
                "WebSocket refused (from 100.64.0.9)",
                "WebSocket refused (repeated 2 more times from 100.64.0.9 in the last 60 s)");
        assertThat(warnings.tracked()).isZero();
    }

    @Test
    void theSourceAndTheMessageAreEscaped() {
        String forged = "1.2.3.4\r\nWARNING: forged";
        warnings.warn(forged, "Origin evil\u001B[2J");
        warnings.warn(forged, "Origin evil\u001B[2J");

        clock.advance(INTERVAL);
        timer.fire();

        assertThat(messages()).containsExactly(
                "Origin evil\\x1B[2J (from 1.2.3.4\\x0D\\x0AWARNING: forged)",
                "Origin evil\\x1B[2J (repeated 1 more times from 1.2.3.4\\x0D\\x0AWARNING: forged in the last 60 s)");
    }

    @Test
    void concurrentWarningsAreAllCounted() throws Exception {
        int threads = 8;
        int each = 100;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> started = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) {
                    warnings.warn(STRANGER, "WebSocket refused");
                }
            });
            thread.start();
            started.add(thread);
        }
        go.countDown();
        for (Thread thread : started) {
            thread.join();
        }

        clock.advance(INTERVAL);
        timer.fire();

        assertThat(messages()).containsExactly(
                "WebSocket refused (from 100.64.0.9)",
                "WebSocket refused (repeated 799 more times from 100.64.0.9 in the last 60 s)");
    }

    @Test
    void theDefaultInstanceWritesTheFirstWarningAtOnce() {
        WarningAggregator system = new WarningAggregator(System.getLogger(LOGGER));

        system.warn(STRANGER, "WebSocket refused");

        assertThat(messages()).containsExactly("WebSocket refused (from 100.64.0.9)");
    }

    @Test
    void refusesNullArguments() {
        assertThatThrownBy(() -> warnings.warn(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> warnings.warn(STRANGER, null)).isInstanceOf(NullPointerException.class);
    }

    private List<String> messages() {
        return logged.stream().map(LogRecord::getMessage).toList();
    }

    /** Collects scheduled tasks; the test runs them after moving the clock. */
    private static final class ManualTimer implements WarningAggregator.Timer {

        private final List<Duration> delays = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void schedule(Runnable task, Duration delay) {
            delays.add(delay);
            tasks.add(task);
        }

        void fire() {
            List<Runnable> due;
            synchronized (this) {
                due = List.copyOf(tasks);
                tasks.clear();
                delays.clear();
            }
            due.forEach(Runnable::run);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
