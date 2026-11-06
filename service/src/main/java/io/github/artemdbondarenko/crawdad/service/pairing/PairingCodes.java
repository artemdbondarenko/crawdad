package io.github.artemdbondarenko.crawdad.service.pairing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one pairing code the service holds, in memory only: six digits from {@link SecureRandom}, valid
 * for five minutes and once. Five wrong attempts, counted from all sources together, burn it; a new
 * code replaces the previous one. Each code's {@link PairingOutcome} completes the future handed to
 * the {@code crawdad pair} that asked for it.
 */
public final class PairingCodes {

    public static final Duration TERM = Duration.ofMinutes(5);
    public static final int ATTEMPTS = 5;

    private static final int BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Runs a task after a delay; the production timer is {@link CompletableFuture#delayedExecutor}. */
    @FunctionalInterface
    interface Timer {
        void schedule(Runnable task, Duration delay);
    }

    /**
     * A code just issued, for the {@code crawdad pair} that asked for it. {@code previousBurnedFrom}
     * holds the sources of the attempts that burned the previous code, if it burned.
     */
    public record Issued(
            String code, Duration expiresIn, Optional<List<String>> previousBurnedFrom,
            CompletableFuture<PairingOutcome> outcome) {

        /** Keeps the code out of logs and error messages. */
        @Override
        public String toString() {
            return "Issued[code=<hidden>, expiresIn=" + expiresIn + ", previousBurnedFrom=" + previousBurnedFrom + "]";
        }
    }

    /** What {@link #attempt} did with an entered code. */
    public sealed interface Attempt {

        /**
         * The code was right and is now used up. The caller adds the device and then reports it with
         * {@link #paired}, which tells the waiting {@code crawdad pair}.
         */
        final class Accepted implements Attempt {

            private final Optional<String> requestedName;
            private final CompletableFuture<PairingOutcome> outcome;

            private Accepted(Optional<String> requestedName, CompletableFuture<PairingOutcome> outcome) {
                this.requestedName = requestedName;
                this.outcome = outcome;
            }

            /** The name given to {@code crawdad pair --name}, if any. */
            public Optional<String> requestedName() {
                return requestedName;
            }

            public void paired(String name, String address, String userAgent) {
                outcome.complete(new PairingOutcome.Paired(name, address, userAgent));
            }

            @Override
            public String toString() {
                return "Accepted[requestedName=" + requestedName + "]";
            }
        }

        /** No code, or it is wrong, expired, used or burned; the caller cannot tell which. */
        record Rejected() implements Attempt {}
    }

    /** The current code. Not a record, so that no generated {@code toString} shows the code. */
    private static final class Pending {

        private final byte[] code;
        private final Instant expiresAt;
        private final Optional<String> requestedName;
        private final CompletableFuture<PairingOutcome> outcome = new CompletableFuture<>();
        private final Set<String> wrongFrom = new LinkedHashSet<>();
        private int wrongAttempts;

        private Pending(String code, Instant expiresAt, Optional<String> requestedName) {
            this.code = code.getBytes(StandardCharsets.US_ASCII);
            this.expiresAt = expiresAt;
            this.requestedName = requestedName;
        }
    }

    private final Clock clock;
    private final Timer timer;
    private Pending current;
    private List<String> lastBurnedFrom;

    /** Codes on the system clock, expired by a background timer. */
    public PairingCodes() {
        this(Clock.systemUTC(), (task, delay) ->
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task));
    }

    PairingCodes(Clock clock, Timer timer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * Issues a new code, replacing the current one, whose waiter learns {@link PairingOutcome.Replaced}.
     * {@code requestedName} is the name from {@code crawdad pair --name}, or {@code null}.
     */
    public Issued issue(String requestedName) {
        Pending replaced;
        Pending issued;
        List<String> burnedFrom;
        synchronized (this) {
            replaced = current;
            // The new code differs from the one it replaces, so the old digits stop working for sure.
            String code;
            do {
                code = "%06d".formatted(RANDOM.nextInt(BOUND));
            } while (replaced != null && code.equals(new String(replaced.code, StandardCharsets.US_ASCII)));
            issued = new Pending(code, clock.instant().plus(TERM), Optional.ofNullable(requestedName));
            current = issued;
            burnedFrom = lastBurnedFrom;
            lastBurnedFrom = null;
        }
        if (replaced != null) {
            replaced.outcome.complete(new PairingOutcome.Replaced());
        }
        scheduleExpiry(issued, TERM);
        String code = new String(issued.code, StandardCharsets.US_ASCII);
        // The waiter gets a copy, so it can wait on the outcome but cannot complete it.
        return new Issued(code, TERM, Optional.ofNullable(burnedFrom), issued.outcome.copy());
    }

    /**
     * Checks an entered code from {@code address}. A right code is used up; a wrong one counts against
     * the current code, and the fifth wrong attempt burns it.
     */
    public Attempt attempt(String entered, String address) {
        Objects.requireNonNull(entered, "entered");
        Objects.requireNonNull(address, "address");
        PairingOutcome ended = null;
        Pending pending;
        boolean right;
        synchronized (this) {
            pending = current;
            if (pending == null) {
                return new Attempt.Rejected();
            }
            if (!clock.instant().isBefore(pending.expiresAt)) {
                current = null;
                ended = new PairingOutcome.Expired();
                right = false;
            } else {
                // Constant-time comparison, so timing does not tell how many digits are right.
                right = MessageDigest.isEqual(pending.code, entered.getBytes(StandardCharsets.US_ASCII));
                if (right) {
                    current = null;
                } else {
                    pending.wrongAttempts++;
                    pending.wrongFrom.add(address);
                    if (pending.wrongAttempts >= ATTEMPTS) {
                        current = null;
                        lastBurnedFrom = List.copyOf(pending.wrongFrom);
                        ended = new PairingOutcome.Burned(lastBurnedFrom);
                    }
                }
            }
        }
        if (ended != null) {
            pending.outcome.complete(ended);
        }
        return right ? new Attempt.Accepted(pending.requestedName, pending.outcome) : new Attempt.Rejected();
    }

    private void scheduleExpiry(Pending pending, Duration delay) {
        timer.schedule(() -> expire(pending), delay);
    }

    private void expire(Pending pending) {
        Duration left;
        synchronized (this) {
            if (current != pending) {
                return;
            }
            left = Duration.between(clock.instant(), pending.expiresAt);
            if (!left.isPositive()) {
                current = null;
            }
        }
        if (left.isPositive()) {
            scheduleExpiry(pending, left);
        } else {
            pending.outcome.complete(new PairingOutcome.Expired());
        }
    }
}
