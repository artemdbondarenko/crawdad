package io.github.artemdbondarenko.crawdad.service.log;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes WARNINGs so that one noisy source cannot flood the log. The first warning from a source is
 * written at once; repeats within the interval are counted and written as one summary line when it
 * ends, after which the source is forgotten. Source and message are escaped with {@link LogText}.
 * Thread-safe.
 */
public final class WarningAggregator {

    public static final Duration INTERVAL = Duration.ofSeconds(60);

    /** Runs a task after a delay; the production timer is {@link CompletableFuture#delayedExecutor}. */
    @FunctionalInterface
    interface Timer {
        void schedule(Runnable task, Duration delay);
    }

    /** The warnings of one source in the current interval. */
    private static final class Window {

        private final Instant start;
        private String lastMessage;
        private int repeats;

        private Window(Instant start, String message) {
            this.start = start;
            this.lastMessage = message;
        }
    }

    private final Logger log;
    private final Duration interval;
    private final Clock clock;
    private final Timer timer;
    private final Map<String, Window> windows = new HashMap<>();

    /** Writes to {@code log}, aggregating over {@link #INTERVAL} on the system clock. */
    public WarningAggregator(Logger log) {
        this(log, INTERVAL, Clock.systemUTC(), (task, delay) ->
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task));
    }

    WarningAggregator(Logger log, Duration interval, Clock clock, Timer timer) {
        this.log = Objects.requireNonNull(log, "log");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** Reports a warning from {@code source}, such as a remote address. */
    public void warn(String source, String message) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        Window opened = null;
        synchronized (this) {
            Window window = windows.get(source);
            if (window == null) {
                opened = new Window(clock.instant(), message);
                windows.put(source, opened);
            } else {
                window.repeats++;
                window.lastMessage = message;
            }
        }
        if (opened != null) {
            log.log(Level.WARNING, LogText.safe(message) + " (from " + LogText.safe(source) + ")");
            schedule(source, opened, interval);
        }
    }

    /** How many sources are remembered now; for tests. */
    synchronized int tracked() {
        return windows.size();
    }

    private void schedule(String source, Window window, Duration delay) {
        timer.schedule(() -> close(source, window), delay);
    }

    private void close(String source, Window window) {
        Duration left;
        String message;
        int repeats;
        synchronized (this) {
            left = Duration.between(clock.instant(), window.start.plus(interval));
            if (left.isPositive()) {
                message = null;
                repeats = 0;
            } else {
                windows.remove(source);
                message = window.lastMessage;
                repeats = window.repeats;
            }
        }
        if (left.isPositive()) {
            schedule(source, window, left);
        } else if (repeats > 0) {
            log.log(Level.WARNING, LogText.safe(message) + " (repeated " + repeats + " more times from "
                    + LogText.safe(source) + " in the last " + interval.toSeconds() + " s)");
        }
    }
}
