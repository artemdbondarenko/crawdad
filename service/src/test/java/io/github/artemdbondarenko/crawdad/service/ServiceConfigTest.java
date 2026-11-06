package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceConfigTest {

    @Test
    void listensOnLocalhostOnlyByDefault() {
        ServiceConfig config = ServiceConfig.fromArgs();

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(ServiceConfig.DEFAULT_PORT);
    }

    @Test
    void readsHostAndPortFromArguments() {
        ServiceConfig config = ServiceConfig.fromArgs("--host", "0.0.0.0", "--port", "9000");

        assertThat(config).isEqualTo(new ServiceConfig("0.0.0.0", 9000));
    }

    @Test
    void portZeroMeansAnyFreePort() {
        assertThat(ServiceConfig.fromArgs("--port", "0").port()).isZero();
    }

    @Test
    void acceptsTheHighestPort() {
        assertThat(ServiceConfig.fromArgs("--port", "65535").port()).isEqualTo(65535);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--port", "--port abc", "--port -1", "--port 65536", "--host", "--verbose x"})
    void rejectsInvalidArguments(String args) {
        String[] split = args.split(" ");

        assertThatThrownBy(() -> ServiceConfig.fromArgs(split)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "fe80::1"})
    void rejectsAnIpv6Host(String host) {
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPv6")
                .hasMessageContaining(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "192.168.1.5"})
    void acceptsAnIpv4AddressOrAHostName(String host) {
        assertThat(ServiceConfig.fromArgs("--host", host).host()).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsAnEmptyHost(String host) {
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host must not be empty");
    }
}
