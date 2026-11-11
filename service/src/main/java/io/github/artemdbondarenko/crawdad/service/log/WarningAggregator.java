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
 * Writes WARNINGs so that one noisy source cannot flood the log ({@code LOG-5}). A warning has a kind, a
 * template such as {@code "Wrong pairing code"} or {@code "WebSocket refused: Origin={0}, Host={1}"}, and a
 * source, such as a remote address. The first warning of a kind from a source is written at once; repeats of
 * that kind from that source within the interval are counted and written as one summary line when it ends,
 * after which they are forgotten. Each value and the source are escaped and cut on their own with
 * {@link LogText} ({@code LOG-3}), so a long value never cuts another one out of the entry. Thread-safe.
 */
public final class WarningAggregator {

    public static final Duration INTERVAL = Duration.ofSeconds(60);

    /** Runs a task after a delay; the production timer is {@link CompletableFuture#delayedExecutor}. */
    @FunctionalInterface
    interface Timer {
        void schedule(Runnable task, Duration delay);
    }

    /** One kind of warning from one source. */
    private record Key(String kind, String source) {}

    /** The warnings of one kind from one source in the current interval. */
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
    private final Map<Key, Window> windows = new HashMap<>();

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

    /**
     * Reports a warning of the kind {@code template} from {@code source}, such as a remote address. Each
     * {@code {n}} in the template is replaced by {@code values[n]}, escaped; the template itself is the
     * service's own text and is written as it is.
     */
    public void warn(String source, String template, String... values) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");
        Key key = new Key(template, source);
        String message = format(template, values);
        Window opened = null;
        synchronized (this) {
            Window window = windows.get(key);
            if (window == null) {
                opened = new Window(clock.instant(), message);
                windows.put(key, opened);
            } else {
                window.repeats++;
                window.lastMessage = message;
            }
        }
        if (opened != null) {
            log.log(Level.WARNING, message + " (from " + LogText.safe(source) + ")");
            schedule(key, opened, interval);
        }
    }

    /** The template with each {@code {n}} replaced by the escaped value; a value is never read as a template. */
    static String format(String template, String... values) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int close = template.indexOf('}', i);
            if (template.charAt(i) == '{' && close > i + 1 && isIndex(template, i + 1, close)) {
                int index = Integer.parseInt(template, i + 1, close, 10);
                if (index >= values.length) {
                    throw new IllegalArgumentException("No value for {" + index + "} in: " + template);
                }
                out.append(LogText.safe(values[index]));
                i = close + 1;
            } else {
                out.append(template.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isIndex(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** How many kinds and sources are remembered now; for tests. */
    synchronized int tracked() {
        return windows.size();
    }

    private void schedule(Key key, Window window, Duration delay) {
        timer.schedule(() -> close(key, window), delay);
    }

    private void close(Key key, Window window) {
        Duration left;
        String message;
        int repeats;
        synchronized (this) {
            left = Duration.between(clock.instant(), window.start.plus(interval));
            if (left.isPositive()) {
                message = null;
                repeats = 0;
            } else {
                windows.remove(key);
                message = window.lastMessage;
                repeats = window.repeats;
            }
        }
        if (left.isPositive()) {
            schedule(key, window, left);
        } else if (repeats > 0) {
            log.log(Level.WARNING, message + " (repeated " + repeats + " more times from "
                    + LogText.safe(key.source()) + " in the last " + interval.toSeconds() + " s)");
        }
    }
}
