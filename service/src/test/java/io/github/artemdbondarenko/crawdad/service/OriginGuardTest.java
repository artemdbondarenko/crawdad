package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OriginGuardTest {

    private final OriginGuard guard = new OriginGuard("127.0.0.1:8460");

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource({
        "http://127.0.0.1:8460, 127.0.0.1:8460",
        // Scheme and host names are case-insensitive in both headers.
        "HTTP://127.0.0.1:8460, 127.0.0.1:8460"
    })
    void letsThePageServedByTheServiceConnect(String origin, String host) {
        assertThat(guard.allows(origin, host)).isTrue();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource(nullValues = "null", value = {
        // a page from another site
        "https://evil.example, 127.0.0.1:8460",
        // a page from another port of the same machine
        "http://127.0.0.1:8000, 127.0.0.1:8460",
        // the same address over another scheme
        "https://127.0.0.1:8460, 127.0.0.1:8460",
        // no Origin: not a browser page, or a request that hides it
        "null, 127.0.0.1:8460",
        // a request for another host, as in DNS rebinding
        "http://127.0.0.1:8460, evil.example:8460",
        "http://127.0.0.1:8460, 127.0.0.1:8000",
        "http://127.0.0.1:8460, null",
        // another name of the same machine: the service accepts only the address it prints
        "http://localhost:8460, localhost:8460",
        "http://localhost:8460, 127.0.0.1:8460",
        // an Origin with a path is not an origin
        "http://127.0.0.1:8460/, 127.0.0.1:8460"
    })
    void rejectsEverythingElse(String origin, String host) {
        assertThat(guard.allows(origin, host)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''", "127.0.0.1"})
    void needsTheFullAddressOfTheService(String authority) {
        assertThatThrownBy(() -> new OriginGuard(authority)).isInstanceOf(IllegalArgumentException.class);
    }
}
