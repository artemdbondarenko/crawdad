package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
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

    /** The arguments with {@code --agent fake} in front, which every start needs ({@code ADAPTER-1}). */
    private static String[] withAgent(String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "--agent";
        all[1] = "fake";
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
    }

    private static ServiceConfig parse(String... args) {
        return ServiceConfig.fromArgs(withAgent(args));
    }

    private static ServiceConfig parse(ServiceConfig.HostResolver resolver, String... args) {
        return ServiceConfig.fromArgs(resolver, withAgent(args));
    }

    @Test
    @Tag("ADAPTER-1")
    @Tag("START-3")
    void aStartWithoutAgentIsAWrongOptionThatNamesTheBuiltInAdapters() {
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--port", "8461"))
                .isInstanceOf(ServiceConfig.WrongOptionException.class)
                .hasMessage("--agent is missing: name the agent adapter to talk to, such as --agent fake");
        assertThatThrownBy(ServiceConfig::fromArgs).isInstanceOf(ServiceConfig.WrongOptionException.class);
    }

    @Test
    @Tag("ADAPTER-1")
    @Tag("START-3")
    void readsTheAgentAndRefusesItWithoutAValueOrTwice() {
        assertThat(ServiceConfig.fromArgs("--agent", "echo", "--port", "0").agent()).isEqualTo("echo");
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--agent"))
                .isInstanceOf(ServiceConfig.WrongOptionException.class).hasMessage("--agent needs a value");
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--agent", "--port", "0"))
                .isInstanceOf(ServiceConfig.WrongOptionException.class).hasMessage("--agent needs a value");
        assertThatThrownBy(() -> ServiceConfig.fromArgs("--agent", "fake", "--agent", "echo"))
                .isInstanceOf(ServiceConfig.WrongOptionException.class)
                .hasMessage("--agent is given more than once; give it once");
    }

    @Test
    @Tag("NET-1")
    @Tag("NET-2")
    void listensOnLocalhostOnlyByDefault() {
        ServiceConfig config = parse();

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(ServiceConfig.DEFAULT_PORT);
        assertThat(config.tls()).isEmpty();
    }

    @Test
    @Tag("NET-1")
    @Tag("NET-2")
    void readsHostAndPortFromArguments() {
        ServiceConfig config = parse(LITERALS, "--host", "192.168.1.5", "--port", "9000");

        assertThat(config).isEqualTo(new ServiceConfig("192.168.1.5", 9000));
    }

    @Test
    @Tag("NET-2")
    void portZeroMeansAnyFreePort() {
        assertThat(parse("--port", "0").port()).isZero();
    }

    @Test
    @Tag("NET-1")
    void acceptsTheHighestPort() {
        assertThat(parse("--port", "65535").port()).isEqualTo(65535);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--port", "--port abc", "--port -1", "--port 65536", "--port 1.5", "--host", "--verbose x",
            "--tls-cert", "--port 8461 --port 8462", "--host --port 8461", "8461"})
    @Tag("START-3")
    void refusesArgumentsThatAreNotItsOptions(String args) {
        String[] split = args.split(" ");

        assertThatThrownBy(() -> parse(LITERALS, split))
                .isInstanceOf(ServiceConfig.WrongOptionException.class);
    }

    @Test
    @Tag("START-3")
    void namesTheOptionThatIsWrong() {
        assertThatThrownBy(() -> parse(LITERALS, "--prot", "8461"))
                .hasMessageStartingWith("--prot is not an option of the service");
        assertThatThrownBy(() -> parse(LITERALS, "--port"))
                .hasMessage("--port needs a value");
        assertThatThrownBy(() -> parse(LITERALS, "--port", "eighty"))
                .hasMessage("--port must be a whole number from 0 to 65535, not eighty");
        assertThatThrownBy(() -> parse(LITERALS, "--host", "10.1.2.3", "--host", "10.1.2.4"))
                .hasMessage("--host is given more than once; give it once");
    }

    @Test
    @Tag("START-3")
    void aValueThatStartsWithDashesButIsNoOptionIsTaken() {
        ServiceConfig config = parse(LITERALS, "--host", "100.64.0.1", "--tls-cert", "--box.crt",
                "--tls-key", "--box.key");

        assertThat(config.tls()).contains(new ServiceConfig.TlsFiles(Path.of("--box.crt"), Path.of("--box.key")));
    }

    @Test
    @Tag("START-3")
    @Tag("LOG-3")
    void anArgumentIsShownOnOneLine() {
        assertThatThrownBy(() -> parse(LITERALS, "--x\n--y", "1"))
                .hasMessageStartingWith("--x\\x0A--y is not an option");
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "fe80::1"})
    @Tag("NET-3")
    void rejectsAnIpv6Host(String host) {
        assertThatThrownBy(() -> parse(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPv6")
                .hasMessageContaining(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.0.6", "100.64.0.1",
        "100.127.255.254", "169.254.1.1"})
    @Tag("NET-5")
    void acceptsAPrivateNetworkAddress(String host) {
        assertThat(parse(LITERALS, "--host", host).host()).isEqualTo(host);
    }

    @Test
    @Tag("NET-3")
    @Tag("NET-5")
    void acceptsAHostNameThatResolvesToAPrivateAddress() {
        ServiceConfig config = parse(names(Map.of("pc.tailnet.ts.net", "100.101.102.103")),
                "--host", "pc.tailnet.ts.net");

        assertThat(config.host()).isEqualTo("pc.tailnet.ts.net");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.5", "localhost"})
    @Tag("NET-5")
    void rejectsALoopbackHostBecauseLoopbackIsAlwaysServed(String host) {
        // The owner gets the loopback address over HTTP anyway; --host is only for a network address.
        assertThatThrownBy(() -> parse(names(Map.of("localhost", "127.0.0.1")), "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(host)
                .hasMessageContaining("loopback")
                .hasMessageContaining("always");
    }

    @Test
    @Tag("NET-5")
    void rejectsAllInterfaces() {
        assertThatThrownBy(() -> parse(LITERALS, "--host", "0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0.0.0")
                .hasMessageContaining("all interfaces");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "11.0.0.1"})
    @Tag("NET-5")
    void rejectsAPublicAddress(String host) {
        assertThatThrownBy(() -> parse(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("--host " + host + " is not a private address");
    }

    @Test
    @Tag("NET-5")
    void rejectsAHostNameThatResolvesToAPublicAddress() {
        // A name is judged by where it points, so a public machine cannot hide behind a friendly name.
        assertThatThrownBy(() -> parse(names(Map.of("myvpn.example.com", "203.0.113.7")),
                        "--host", "myvpn.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("--host myvpn.example.com (203.0.113.7) is not a private address");
    }

    @Test
    @Tag("NET-5")
    void rejectsAHostNameWhenAnyOfItsAddressesIsPublic() {
        ServiceConfig.HostResolver twoAddresses = host -> new InetAddress[] {
            InetAddress.getByName("192.168.0.6"), InetAddress.getByName("203.0.113.7")};

        assertThatThrownBy(() -> parse(twoAddresses, "--host", "box"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("203.0.113.7");
    }

    @Test
    @Tag("NET-3")
    void skipsTheIpv6AddressesOfAHostName() {
        // MagicDNS names resolve to a 100.x address and an IPv6 one; IPv6 is not supported yet.
        ServiceConfig.HostResolver dualStack = host -> new InetAddress[] {
            InetAddress.getByName("fd7a:115c:a1e0::1"), InetAddress.getByName("100.101.102.103")};

        assertThat(parse(dualStack, "--host", "pc.tailnet.ts.net").host())
                .isEqualTo("pc.tailnet.ts.net");
    }

    @Test
    @Tag("NET-3")
    void rejectsAHostNameWithOnlyIpv6Addresses() {
        ServiceConfig.HostResolver ipv6Only = host -> new InetAddress[] {InetAddress.getByName("fd00::1")};

        assertThatThrownBy(() -> parse(ipv6Only, "--host", "v6box"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v6box")
                .hasMessageContaining("IPv4");
    }

    @Test
    @Tag("NET-3")
    void rejectsAHostNameThatDoesNotResolve() {
        assertThatThrownBy(() -> parse(LITERALS, "--host", "no-such-host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-host")
                .hasMessageContaining("resolve");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsAnEmptyHost(String host) {
        assertThatThrownBy(() -> parse(LITERALS, "--host", host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The host must not be empty");
    }

    @Test
    @Tag("TLS-2")
    void readsBothCertificatePaths() {
        ServiceConfig config = parse(LITERALS, "--host", "100.64.0.1",
                "--tls-cert", "/certs/pc.crt", "--tls-key", "/certs/pc.key");

        assertThat(config.tls()).contains(new ServiceConfig.TlsFiles(Path.of("/certs/pc.crt"), Path.of("/certs/pc.key")));
    }

    @Test
    @Tag("TLS-7")
    void withoutCertificatePathsTheServiceIssuesItsOwnCertificate() {
        assertThat(parse(LITERALS, "--host", "100.64.0.1").tls()).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--tls-cert", "--tls-key"})
    @Tag("TLS-2")
    @Tag("START-3")
    void rejectsOnlyOneCertificatePath(String option) {
        // Half a configuration must not silently fall back to the service's own certificate.
        assertThatThrownBy(() -> parse(LITERALS, "--host", "100.64.0.1", option, "/certs/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ServiceConfig.WrongOptionException.class)
                .hasMessageContaining("--tls-cert")
                .hasMessageContaining("--tls-key")
                .hasMessageContaining("both or neither");
    }

    @Test
    @Tag("TLS-2")
    void rejectsCertificatePathsWithoutANetworkAddress() {
        // Loopback is plain HTTP, so certificate files without --host would be silently ignored.
        assertThatThrownBy(() -> parse(LITERALS, "--tls-cert", "/c.crt", "--tls-key", "/c.key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--host");
    }
}
