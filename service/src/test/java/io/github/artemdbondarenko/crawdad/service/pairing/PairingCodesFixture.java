package io.github.artemdbondarenko.crawdad.service.pairing;

import java.time.Clock;

/** Pairing codes for tests outside this package that move time by hand. */
public final class PairingCodesFixture {

    private PairingCodesFixture() {}

    /** Codes on {@code clock} whose expiry timer never fires: a code ends only when entered after its term. */
    public static PairingCodes onClock(Clock clock) {
        return new PairingCodes(clock, (task, delay) -> {});
    }
}
