package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OriginGuardTest {

    private final OriginGuard loopback = new OriginGuard("http", "127.0.0.1", 8460);
    private final OriginGuard network = new OriginGuard("https", "laptop.example.ts.net", 8460);

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource({
        "http://127.0.0.1:8460, 127.0.0.1:8460",
        // Scheme and host names are case-insensitive in both headers.
        "HTTP://127.0.0.1:8460, 127.0.0.1:8460"
    })
    @Tag("WS-1")
    @Tag("WS-2")
    @Tag("WS-3")
    void letsThePageServedByTheServiceConnect(String origin, String host) {
        assertThat(loopback.allows(origin, host)).isTrue();
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
    @Tag("WS-1")
    @Tag("WS-2")
    @Tag("WS-3")
    void rejectsEverythingElse(String origin, String host) {
        assertThat(loopback.allows(origin, host)).isFalse();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource({
        "https://laptop.example.ts.net:8460, laptop.example.ts.net:8460",
        "HTTPS://Laptop.Example.TS.net:8460, LAPTOP.example.ts.net:8460"
    })
    @Tag("NET-9")
    void letsThePageOfTheNetworkAddressConnectOverTls(String origin, String host) {
        assertThat(network.allows(origin, host)).isTrue();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource({
        // the network address over plain HTTP: a page that is not the one the service serves
        "http://laptop.example.ts.net:8460, laptop.example.ts.net:8460",
        // the loopback page on the network connector
        "http://127.0.0.1:8460, 127.0.0.1:8460",
        // the IP of the name: the service accepts only the name it prints
        "https://100.101.102.103:8460, 100.101.102.103:8460",
        "https://laptop.example.ts.net:8460, 100.101.102.103:8460"
    })
    @Tag("NET-9")
    void rejectsOtherPagesOnTheNetworkAddress(String origin, String host) {
        assertThat(network.allows(origin, host)).isFalse();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource(nullValues = "null", value = {
        // a browser's GET to the page's own address carries no Origin
        "null, 127.0.0.1:8460",
        "http://127.0.0.1:8460, 127.0.0.1:8460"
    })
    void withoutOriginRequiredLetsThePageInWithOrWithoutIt(String origin, String host) {
        assertThat(loopback.allowsWithoutOrigin(origin, host)).isTrue();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource(nullValues = "none", value = {
        // an Origin that is sent must still be the page's own
        "https://evil.example, 127.0.0.1:8460",
        "null, 127.0.0.1:8460",
        "'', 127.0.0.1:8460",
        // the Host is required either way
        "none, evil.example:8460",
        "none, none",
        "http://127.0.0.1:8460, evil.example:8460"
    })
    void withoutOriginRequiredStillRejectsAForeignOriginOrHost(String origin, String host) {
        assertThat(loopback.allowsWithoutOrigin(origin, host)).isFalse();
    }

    @Test
    @Tag("NET-9")
    void leavesOutTheDefaultPortOfTheScheme() {
        // A browser omits the default port in both headers, so the guard does too.
        OriginGuard tls = new OriginGuard("https", "laptop.example.ts.net", 443);
        OriginGuard plain = new OriginGuard("http", "127.0.0.1", 80);

        assertThat(tls.allows("https://laptop.example.ts.net", "laptop.example.ts.net")).isTrue();
        assertThat(tls.allows("https://laptop.example.ts.net:443", "laptop.example.ts.net:443")).isFalse();
        assertThat(tls.allows("https://laptop.example.ts.net", "laptop.example.ts.net:443")).isFalse();
        assertThat(plain.allows("http://127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(plain.allows("http://127.0.0.1:80", "127.0.0.1:80")).isFalse();
    }

    @Test
    @Tag("NET-9")
    void keepsAPortThatIsDefaultOnlyForTheOtherScheme() {
        OriginGuard tlsOn80 = new OriginGuard("https", "laptop.example.ts.net", 80);
        OriginGuard plainOn443 = new OriginGuard("http", "127.0.0.1", 443);

        assertThat(tlsOn80.allows("https://laptop.example.ts.net:80", "laptop.example.ts.net:80")).isTrue();
        assertThat(tlsOn80.allows("https://laptop.example.ts.net", "laptop.example.ts.net")).isFalse();
        assertThat(plainOn443.allows("http://127.0.0.1:443", "127.0.0.1:443")).isTrue();
        assertThat(plainOn443.allows("http://127.0.0.1", "127.0.0.1")).isFalse();
    }

    @Test
    @Tag("NET-9")
    void namesTheAddressItGuards() {
        assertThat(loopback.address()).isEqualTo(URI.create("http://127.0.0.1:8460/"));
        assertThat(network.address()).isEqualTo(URI.create("https://laptop.example.ts.net:8460/"));
        assertThat(new OriginGuard("https", "laptop.example.ts.net", 443).address())
                .isEqualTo(URI.create("https://laptop.example.ts.net/"));
    }

    @ParameterizedTest(name = "{0}://{1}:{2}")
    @CsvSource(nullValues = "null", value = {
        "null, 127.0.0.1, 8460",
        "ftp, 127.0.0.1, 8460",
        "http, null, 8460",
        "http, '', 8460",
        "http, 127.0.0.1:8460, 8460",
        "http, 127.0.0.1, 0",
        "http, 127.0.0.1, 65536"
    })
    void needsASchemeAHostAndAPort(String scheme, String host, int port) {
        assertThatThrownBy(() -> new OriginGuard(scheme, host, port)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheWholePortRange() {
        assertThat(new OriginGuard("http", "127.0.0.1", 1).allows("http://127.0.0.1:1", "127.0.0.1:1")).isTrue();
        assertThat(new OriginGuard("http", "127.0.0.1", 65535).allows("http://127.0.0.1:65535", "127.0.0.1:65535"))
                .isTrue();
    }
}
