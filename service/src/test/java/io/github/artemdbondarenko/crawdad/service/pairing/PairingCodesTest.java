package io.github.artemdbondarenko.crawdad.service.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Attempt;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Issued;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PairingCodesTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");
    private static final String PHONE = "100.64.0.7";
    private static final String STRANGER = "100.64.0.9";

    private final MutableClock clock = new MutableClock(START);
    private final ManualTimer timer = new ManualTimer();
    private final PairingCodes codes = new PairingCodes(clock, timer);

    @Test
    void aCodeIsSixDigitsAndWorksUntilFiveMinutesPass() {
        Issued issued = codes.issue(null);

        assertThat(issued.code()).matches("[0-9]{6}");
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(5));
        assertThat(issued.previousBurnedFrom()).isEmpty();

        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
    }

    @Test
    void aCodeEnteredAfterFiveMinutesIsRejectedAndTheWaiterLearnsItExpired() {
        Issued issued = codes.issue(null);

        clock.advance(Duration.ofMinutes(5));

        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Expired());
    }

    @Test
    void theTimerExpiresACodeNobodyEnters() {
        Issued issued = codes.issue(null);
        assertThat(timer.delays).containsExactly(Duration.ofMinutes(5));

        // A timer that fires early does not end the code; it waits for the rest of the term.
        clock.advance(Duration.ofMinutes(3));
        timer.fire();
        assertThat(issued.outcome()).isNotDone();
        assertThat(timer.delays).containsExactly(Duration.ofMinutes(2));

        clock.advance(Duration.ofMinutes(2));
        timer.fire();
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Expired());
        assertThat(timer.delays).isEmpty();
        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
    }

    @Test
    void aCodePairsOnceAndTheWaiterLearnsWhichDevice() {
        Issued issued = codes.issue("phone");

        Attempt attempt = codes.attempt(issued.code(), PHONE);
        assertThat(attempt).isInstanceOf(Attempt.Accepted.class);
        Attempt.Accepted accepted = (Attempt.Accepted) attempt;
        assertThat(accepted.requestedName()).contains("phone");
        assertThat(issued.outcome()).isNotDone();

        accepted.paired("phone-2", PHONE, "Mozilla/5.0 (Android)");
        assertThat(issued.outcome())
                .isCompletedWithValue(new PairingOutcome.Paired("phone-2", PHONE, "Mozilla/5.0 (Android)"));

        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
    }

    @Test
    void aDeviceThatCouldNotBeSavedFailsTheCodeForGood() {
        Issued issued = codes.issue(null);
        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(issued.code(), PHONE);

        accepted.failed();

        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Failed());
        // The code is not given back: entering it again is rejected.
        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
    }

    @Test
    void onlyTheFirstReportOfAnAcceptedAttemptCounts() {
        Issued paired = codes.issue(null);
        Attempt.Accepted first = (Attempt.Accepted) codes.attempt(paired.code(), PHONE);
        first.paired("phone", PHONE, null);
        first.failed();
        assertThat(paired.outcome()).isCompletedWithValue(new PairingOutcome.Paired("phone", PHONE, null));

        Issued failed = codes.issue(null);
        Attempt.Accepted second = (Attempt.Accepted) codes.attempt(failed.code(), PHONE);
        second.failed();
        second.paired("phone", PHONE, null);
        assertThat(failed.outcome()).isCompletedWithValue(new PairingOutcome.Failed());
    }

    @Test
    void withoutANameTheAcceptedAttemptHasNone() {
        Issued issued = codes.issue(null);

        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(issued.code(), PHONE);

        assertThat(accepted.requestedName()).isEmpty();
    }

    @Test
    void aNewCodeReplacesThePreviousOne() {
        Issued first = codes.issue(null);
        Issued second = codes.issue(null);

        assertThat(first.outcome()).isCompletedWithValue(new PairingOutcome.Replaced());
        assertThat(second.code()).isNotEqualTo(first.code());
        assertThat(codes.attempt(first.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(codes.attempt(second.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
        assertThat(second.outcome()).isNotDone();
    }

    @Test
    void fiveWrongAttemptsFromTwoAddressesBurnTheCode() {
        Issued issued = codes.issue(null);
        String wrong = wrong(issued.code());

        // Attempts count per code across all sources: three from one address, two from another.
        for (int i = 0; i < 3; i++) {
            assertThat(codes.attempt(wrong, STRANGER)).isInstanceOf(Attempt.Rejected.class);
        }
        assertThat(codes.attempt(wrong, PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(issued.outcome()).isNotDone();
        assertThat(codes.attempt(wrong, PHONE)).isInstanceOf(Attempt.Rejected.class);

        assertThat(issued.outcome())
                .isCompletedWithValue(new PairingOutcome.Burned(List.of(STRANGER, PHONE)));
        // The owner's right code is refused too: the code is gone.
        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(timer.delays).hasSize(1);
        timer.fire();
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Burned(List.of(STRANGER, PHONE)));
    }

    @Test
    void theNextCodeReportsThatThePreviousOneBurnedOnlyOnce() {
        Issued burned = codes.issue(null);
        for (int i = 0; i < 5; i++) {
            codes.attempt(wrong(burned.code()), STRANGER);
        }

        Issued next = codes.issue(null);
        Issued after = codes.issue(null);

        assertThat(next.previousBurnedFrom()).contains(List.of(STRANGER));
        assertThat(after.previousBurnedFrom()).isEmpty();
        assertThat(codes.attempt(after.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
    }

    @Test
    void aBurnReportedToItsWaiterIsNotRepeatedByTheNextCode() {
        Issued burned = codes.issue(null);
        for (int i = 0; i < 5; i++) {
            codes.attempt(wrong(burned.code()), STRANGER);
        }

        codes.burnReported((PairingOutcome.Burned) burned.outcome().join());

        assertThat(codes.issue(null).previousBurnedFrom()).isEmpty();
    }

    @Test
    void reportingAnOlderBurnLeavesTheLatestOneForTheNextCode() {
        Issued first = codes.issue(null);
        for (int i = 0; i < 5; i++) {
            codes.attempt(wrong(first.code()), STRANGER);
        }
        PairingOutcome.Burned older = (PairingOutcome.Burned) first.outcome().join();
        Issued second = codes.issue(null);
        for (int i = 0; i < 5; i++) {
            codes.attempt(wrong(second.code()), PHONE);
        }

        // The first waiter's answer arrives late; the second burn, which nobody saw, stays remembered.
        codes.burnReported(older);
        codes.burnReported(new PairingOutcome.Burned(List.of(PHONE)));

        assertThat(codes.issue(null).previousBurnedFrom()).contains(List.of(PHONE));
    }

    @Test
    void fourWrongAttemptsAndThenTheRightCodePair() {
        Issued issued = codes.issue(null);
        for (int i = 0; i < 4; i++) {
            assertThat(codes.attempt(wrong(issued.code()), i % 2 == 0 ? STRANGER : PHONE))
                    .isInstanceOf(Attempt.Rejected.class);
        }

        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
        assertThat(issued.outcome()).isNotDone();
    }

    @Test
    void withoutACodeEveryAttemptIsRejected() {
        assertThat(codes.attempt("123456", PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(codes.attempt("", PHONE)).isInstanceOf(Attempt.Rejected.class);
    }

    @Test
    void anInputOfAnotherLengthIsAWrongAttempt() {
        Issued issued = codes.issue(null);

        assertThat(codes.attempt(issued.code() + "0", PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(codes.attempt(issued.code().substring(1), PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(codes.attempt(issued.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
    }

    @Test
    void theCodeStaysOutOfTextRepresentations() {
        Issued issued = codes.issue("phone");
        Attempt accepted = codes.attempt(issued.code(), PHONE);

        assertThat(issued.toString()).doesNotContain(issued.code()).contains("<hidden>");
        assertThat(accepted.toString()).doesNotContain(issued.code()).contains("phone");
        assertThat(codes.toString()).doesNotContain(issued.code());
    }

    @Test
    void theDefaultInstanceIssuesRandomCodesOnTheSystemClock() throws Exception {
        PairingCodes system = new PairingCodes();
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            issued.add(system.issue(null).code());
        }

        assertThat(issued).allMatch(code -> code.matches("[0-9]{6}"));
        Issued last = system.issue(null);
        assertThat(system.attempt(last.code(), PHONE)).isInstanceOf(Attempt.Accepted.class);
        assertThat(last.outcome().isDone()).isFalse();
        CompletableFuture<PairingOutcome> previous = system.issue(null).outcome();
        system.issue(null);
        assertThat(previous.get(1, TimeUnit.SECONDS)).isEqualTo(new PairingOutcome.Replaced());
    }

    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    /** Collects scheduled tasks; the test runs them after moving the clock. */
    private static final class ManualTimer implements PairingCodes.Timer {

        private final List<Duration> delays = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            delays.add(delay);
            tasks.add(task);
        }

        void fire() {
            List<Runnable> due = List.copyOf(tasks);
            tasks.clear();
            delays.clear();
            due.forEach(Runnable::run);
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
