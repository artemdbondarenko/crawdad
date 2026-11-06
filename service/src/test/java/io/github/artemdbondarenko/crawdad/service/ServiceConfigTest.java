package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceConfigTest {

    /** Resolves only the names in the map and IP literals; never asks DNS. */
    private static ServiceConfig.HostResolver names(Map<String, String> names) {
        return host -> {
            String address = names.get(host);
            if (address != null) {
                return new InetAddress[] {InetAddress.getByName(address)};
            }
            if (!host.matches("[0-9.]+")) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(host)};
        };
    }

    private static final ServiceConfig.HostResolver LITERALS = names(Map.of());

    @Test
    void listensOnLocalhostOnlyByDefault() {
        ServiceConfig config = ServiceConfig.fromArgs();

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(ServiceConfig.DEFAULT_PORT);
        assertThat(config.tls()).isEmpty();
    }

    @Test
    void readsHostAndPortFromArguments() {
        ServiceConfig config = ServiceConfig.fromArgs(LITERALS, "--host", "192.168.1.5", "--port", "9000");

        assertThat(config).isEqualTo(new ServiceConfig("192.168.1.5", 9000));
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
    @ValueSource(strings = {"--port", "--port abc", "--port -1", "--port 65536", "--host", "--verbose x", "--tls-cert"})
    void rejectsInvalidArguments(String args) {
        String[] split = args.split(" ");

        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, split)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "fe80::1"})
    void rejectsAnIpv6Host(String host) {
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPv6")
                .hasMessageContaining(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.0.6", "100.64.0.1",
        "100.127.255.254", "169.254.1.1"})
    void acceptsAPrivateNetworkAddress(String host) {
        assertThat(ServiceConfig.fromArgs(LITERALS, "--host", host).host()).isEqualTo(host);
    }

    @Test
    void acceptsAHostNameThatResolvesToAPrivateAddress() {
        ServiceConfig config = ServiceConfig.fromArgs(names(Map.of("pc.tailnet.ts.net", "100.101.102.103")),
                "--host", "pc.tailnet.ts.net");

        assertThat(config.host()).isEqualTo("pc.tailnet.ts.net");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.5", "localhost"})
    void rejectsALoopbackHostBecauseLoopbackIsAlwaysServed(String host) {
        // The owner gets the loopback address over HTTP anyway; --host is only for a network address.
        assertThatThrownBy(() -> ServiceConfig.fromArgs(names(Map.of("localhost", "127.0.0.1")), "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(host)
                .hasMessageContaining("loopback")
                .hasMessageContaining("always");
    }

    @Test
    void rejectsAllInterfaces() {
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", "0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0.0.0")
                .hasMessageContaining("all interfaces");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "11.0.0.1"})
    void rejectsAPublicAddress(String host) {
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("--host " + host + " is not a private address");
    }

    @Test
    void rejectsAHostNameThatResolvesToAPublicAddress() {
        // A name is judged by where it points, so a public machine cannot hide behind a friendly name.
        assertThatThrownBy(() -> ServiceConfig.fromArgs(names(Map.of("myvpn.example.com", "203.0.113.7")),
                        "--host", "myvpn.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("--host myvpn.example.com (203.0.113.7) is not a private address");
    }

    @Test
    void rejectsAHostNameWhenAnyOfItsAddressesIsPublic() {
        ServiceConfig.HostResolver twoAddresses = host -> new InetAddress[] {
            InetAddress.getByName("192.168.0.6"), InetAddress.getByName("203.0.113.7")};

        assertThatThrownBy(() -> ServiceConfig.fromArgs(twoAddresses, "--host", "box"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("203.0.113.7");
    }

    @Test
    void skipsTheIpv6AddressesOfAHostName() {
        // MagicDNS names resolve to a 100.x address and an IPv6 one; IPv6 is not supported yet.
        ServiceConfig.HostResolver dualStack = host -> new InetAddress[] {
            InetAddress.getByName("fd7a:115c:a1e0::1"), InetAddress.getByName("100.101.102.103")};

        assertThat(ServiceConfig.fromArgs(dualStack, "--host", "pc.tailnet.ts.net").host())
                .isEqualTo("pc.tailnet.ts.net");
    }

    @Test
    void rejectsAHostNameWithOnlyIpv6Addresses() {
        ServiceConfig.HostResolver ipv6Only = host -> new InetAddress[] {InetAddress.getByName("fd00::1")};

        assertThatThrownBy(() -> ServiceConfig.fromArgs(ipv6Only, "--host", "v6box"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v6box")
                .hasMessageContaining("IPv4");
    }

    @Test
    void rejectsAHostNameThatDoesNotResolve() {
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", "no-such-host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-host")
                .hasMessageContaining("resolve");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsAnEmptyHost(String host) {
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host must not be empty");
    }

    @Test
    void readsBothCertificatePaths() {
        ServiceConfig config = ServiceConfig.fromArgs(LITERALS, "--host", "100.64.0.1",
                "--tls-cert", "/certs/pc.crt", "--tls-key", "/certs/pc.key");

        assertThat(config.tls()).contains(new ServiceConfig.TlsFiles(Path.of("/certs/pc.crt"), Path.of("/certs/pc.key")));
    }

    @Test
    void withoutCertificatePathsTheServiceIssuesItsOwnCertificate() {
        assertThat(ServiceConfig.fromArgs(LITERALS, "--host", "100.64.0.1").tls()).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--tls-cert", "--tls-key"})
    void rejectsOnlyOneCertificatePath(String option) {
        // Half a configuration must not silently fall back to the service's own certificate.
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--host", "100.64.0.1", option, "/certs/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--tls-cert")
                .hasMessageContaining("--tls-key")
                .hasMessageContaining("both or neither");
    }

    @Test
    void rejectsCertificatePathsWithoutANetworkAddress() {
        // Loopback is plain HTTP, so certificate files without --host would be silently ignored.
        assertThatThrownBy(() -> ServiceConfig.fromArgs(LITERALS, "--tls-cert", "/c.crt", "--tls-key", "/c.key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--host");
    }
}
