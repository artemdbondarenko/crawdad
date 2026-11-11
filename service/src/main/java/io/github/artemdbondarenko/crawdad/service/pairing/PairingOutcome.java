package io.github.artemdbondarenko.crawdad.service.pairing;

import java.util.List;
import java.util.Objects;

/** How a pairing code ended, as the waiting {@code crawdad pair} learns it. */
public sealed interface PairingOutcome {

    /** The code was exchanged for a key: the device's name, the address it paired from and its browser. */
    record Paired(String name, String address, String userAgent) implements PairingOutcome {

        public Paired {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(address, "address");
        }
    }

    /** Nobody entered the code within its term. */
    record Expired() implements PairingOutcome {}

    /** Wrong attempts used up the code; {@code addresses} are their sources, each once, in order of first attempt. */
    record Burned(List<String> addresses) implements PairingOutcome {

        public Burned {
            addresses = List.copyOf(addresses);
        }
    }

    /** A newer {@code crawdad pair} issued another code before this one ended. */
    record Replaced() implements PairingOutcome {}

    /** The code was right, but the device could not be saved; the code stays used up. */
    record Failed() implements PairingOutcome {}
}
