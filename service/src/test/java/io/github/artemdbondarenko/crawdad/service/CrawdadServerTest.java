package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.ServiceConfig.TlsFiles;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CrawdadServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The network address of the tests: a second loopback address, served like a private one. */
    private static final String NETWORK_HOST = "127.0.0.2";
    /** The name in the test certificate, as {@code tailscale cert} would write it. */
    private static final String NETWORK_NAME = "crawdad.test";
    /** A second name in the certificate, which the service does not print. */
    private static final String OTHER_NAME = "other.crawdad.test";
    private static final String NL = System.lineSeparator();

    @TempDir
    Path temp;

    private CrawdadServer server;
    private X509Certificate certificate;
    /** The registry of a server started by {@link #startWithHost}. */
    private DeviceRegistry devices;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesTheClientPageAtTheRoot() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");

        HttpResponse<String> response = get(server.address());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>Crawdad test page</title>");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
    }

    @Test
    void answersNotFoundForAMissingFile() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");

        assertThat(get(server.address().resolve("/nope")).statusCode()).isEqualTo(404);
    }

    @Test
    @Tag("NET-4")
    void reportsTheAddressItListensOn() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");

        URI address = server.address();

        assertThat(address.getScheme()).isEqualTo("http");
        assertThat(address.getHost()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isPositive();
        assertThat(address.getPath()).isEqualTo("/");
    }

    @Test
    void closesThePortWhenStopped() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        int port = server.address().getPort();

        server.stop();
        server = null;

        assertThatThrownBy(() -> new Socket("127.0.0.1", port).close()).isInstanceOf(ConnectException.class);
    }

    @Test
    void joinReturnsOnceTheServerStops() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                server.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        server.stop();
        waiting.join(Duration.ofSeconds(5));

        assertThat(waiting.isAlive()).isFalse();
    }

    @Test
    void refusesToStartWithoutTheClientFiles() {
        assertThatThrownBy(() -> CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "no-such-dir/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-dir/");
    }

    @Test
    @Tag("NET-4")
    void mainStartsTheServerAndPrintsWhereToOpenIt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        server = CrawdadService.run(new String[] {"--port", "0"}, data(),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Open " + server.address());
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("Crawdad service is running. Open " + server.address() + " in a browser." + NL);
        assertThat(get(server.address()).statusCode()).isEqualTo(200);
    }

    @Test
    @Tag("NET-4")
    void printsBothAddressesAndNoFingerprintWithCertificateFiles() throws Exception {
        ServiceConfig config = new ServiceConfig(NETWORK_HOST, 0, Optional.of(writeCertificate(
                dns(NETWORK_NAME), ip(NETWORK_HOST))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        server = CrawdadService.run(config, NetworkCertificate.forConfig(config), data(),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        // The network address carries the name from the certificate, not the address it listens on.
        URI network = server.networkAddress().orElseThrow();
        assertThat(network).isEqualTo(URI.create("https://" + NETWORK_NAME + ":" + network.getPort() + "/"));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("Crawdad service is running." + NL
                + "  On this machine: " + server.address() + NL
                + "  On the network:  " + network + NL);
    }

    @Test
    @Tag("NET-4")
    @Tag("TLS-7")
    void printsTheRootFingerprintWithTheOwnCertificate() throws Exception {
        TlsMaterial material = TlsMaterial.read(writeCertificate(ip(NETWORK_HOST)));
        NetworkCertificate own = new NetworkCertificate(material.privateKey(), material.chain(), NETWORK_HOST,
                Optional.of("AB:CD:EF"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        server = CrawdadService.run(new ServiceConfig(NETWORK_HOST, 0), Optional.of(own), data(),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        URI network = server.networkAddress().orElseThrow();
        assertThat(network).isEqualTo(URI.create("https://" + NETWORK_HOST + ":" + network.getPort() + "/"));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("Crawdad service is running." + NL
                + "  On this machine: " + server.address() + NL
                + "  On the network:  " + network + NL
                + "  Root certificate SHA-256: AB:CD:EF" + NL);
    }

    @Test
    @Tag("TLS-5")
    void reloadsRenewedCertificateFilesUntilTheServerStops() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        ServiceConfig config = new ServiceConfig(NETWORK_HOST, 0, Optional.of(writeCertificate(
                dns(NETWORK_NAME), ip(NETWORK_HOST))));
        server = CrawdadService.run(config, NetworkCertificate.forConfig(config), data(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        int port = server.networkAddress().orElseThrow().getPort();
        Set<Thread> watching = watcherThreadsSince(before);
        assertThat(watching).isNotEmpty();

        // The renewed files replace the old ones; a client that trusts only the new certificate gets in.
        writeCertificate(dns(NETWORK_NAME), ip(NETWORK_HOST));
        assertThat(connectsWithin(port, Duration.ofSeconds(10))).isTrue();

        // Stopping the server ends the watch too.
        server.stop();
        server = null;
        assertThat(watching).noneMatch(Thread::isAlive);
    }

    @Test
    void watchesNoFilesWithTheOwnCertificate() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        TlsMaterial material = TlsMaterial.read(writeCertificate(ip(NETWORK_HOST)));
        NetworkCertificate own = new NetworkCertificate(material.privateKey(), material.chain(), NETWORK_HOST,
                Optional.of("AB:CD:EF"));

        server = CrawdadService.run(new ServiceConfig(NETWORK_HOST, 0), Optional.of(own), data(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(watcherThreadsSince(before)).isEmpty();
    }

    @Test
    @Tag("TLS-7")
    @Tag("TLS-9")
    void issuesTheOwnCertificateForHostWithoutCertificateFiles() throws Exception {
        LocalCa ca = LocalCa.forThisMachine(new DataDirectory(temp.resolve("data"), temp.resolve("run")));

        NetworkCertificate own = NetworkCertificate.forConfig(new ServiceConfig("10.1.2.3", 0), () -> ca)
                .orElseThrow();

        assertThat(own.addressName()).isEqualTo("10.1.2.3");
        assertThat(own.chain().getFirst().getSubjectAlternativeNames()).contains(List.of(7, "10.1.2.3"));
        // The same root signs every start, so a second issue reports the same fingerprint.
        assertThat(own.rootFingerprint()).contains(ca.issue("10.1.2.3").rootFingerprint());
    }

    @Test
    @Tag("TLS-2")
    @Tag("TLS-4")
    @Tag("NET-9")
    void takesTheCertificateFilesAndTheirNameWhenGiven() throws Exception {
        TlsFiles files = writeCertificate(dns(NETWORK_NAME), ip(NETWORK_HOST));

        NetworkCertificate given = NetworkCertificate.forConfig(new ServiceConfig(NETWORK_HOST, 0, Optional.of(files)),
                () -> {
                    throw new AssertionError("the own root must not be touched with certificate files");
                }).orElseThrow();

        assertThat(given.addressName()).isEqualTo(NETWORK_NAME);
        assertThat(given.chain()).containsExactly(certificate);
        assertThat(given.rootFingerprint()).isEmpty();
        assertThat(given.toString()).contains("<hidden>").doesNotContain(given.privateKey().toString());
    }

    @Test
    @Tag("TLS-7")
    void needsNoCertificateWithoutHost() throws Exception {
        assertThat(NetworkCertificate.forConfig(new ServiceConfig("127.0.0.1", 0), () -> {
            throw new AssertionError("the own root must not be touched without --host");
        })).isEmpty();
    }

    @Test
    @Tag("TLS-3")
    void refusesToStartWithAnUnusableCertificateFile() {
        Path missing = temp.resolve("missing.crt");
        ServiceConfig config = new ServiceConfig(NETWORK_HOST, 0,
                Optional.of(new TlsFiles(missing, temp.resolve("missing.key"))));

        assertThatThrownBy(() -> CrawdadServer.start(config, "static/"))
                .isInstanceOf(TlsMaterial.UnusableFileException.class)
                .hasMessageStartingWith(missing + ": ");
    }

    @Test
    @Tag("TLS-10")
    void refusesToStartWithAnUnusableRoot() throws Exception {
        DataDirectory data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        LocalCa ca = LocalCa.forThisMachine(data);
        ca.issue("10.1.2.3");
        Files.delete(data.data().resolve("tls/ca.crt"));

        assertThatThrownBy(() -> NetworkCertificate.forConfig(new ServiceConfig("10.1.2.3", 0), () -> ca))
                .isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessageContaining("ca.crt is missing");
    }

    @Test
    void needsACertificateExactlyWhenThereIsANetworkAddress() throws Exception {
        TlsMaterial material = TlsMaterial.read(writeCertificate(ip(NETWORK_HOST)));
        NetworkCertificate own = new NetworkCertificate(material.privateKey(), material.chain(), NETWORK_HOST,
                Optional.empty());

        assertThatThrownBy(() -> CrawdadServer.start(new ServiceConfig(NETWORK_HOST, 0), "static/",
                new FakeAgentAdapter(), Optional.empty())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The network address 127.0.0.2 needs a certificate");
        assertThatThrownBy(() -> CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/",
                new FakeAgentAdapter(), Optional.of(own))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A certificate needs a network address");
    }

    @Test
    @Tag("NET-1")
    @Tag("NET-2")
    void listensOnlyOnLoopbackWithoutHost() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        int port = server.address().getPort();

        assertThat(server.networkAddress()).isEmpty();
        assertThat(server.networkTls()).isEmpty();
        // Another address of the machine reaches the port only if the service listened on all interfaces.
        assertThatThrownBy(() -> new Socket(NETWORK_HOST, port).close()).isInstanceOf(ConnectException.class);
    }

    @Test
    @Tag("NET-1")
    @Tag("NET-2")
    void servesLoopbackOverHttpAndTheNetworkAddressOverTls() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        URI network = server.networkAddress().orElseThrow();

        assertThat(server.address().getScheme()).isEqualTo("http");
        assertThat(get(server.address()).statusCode()).isEqualTo(200);
        try (SSLSocket socket = tls(network.getPort(), NETWORK_NAME)) {
            assertThat(send(socket, page(network.getAuthority()))).startsWith("HTTP/1.1 200");
        }
        // The TLS port is open on the --host address only, not on all interfaces.
        assertThatThrownBy(() -> new Socket("127.0.0.3", network.getPort()).close())
                .isInstanceOf(ConnectException.class);
    }

    @Test
    @Tag("NET-2")
    void servesBothAddressesOnTheConfiguredPort() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(NETWORK_HOST))) {
            port = probe.getLocalPort();
        }

        server = CrawdadServer.start(new ServiceConfig(NETWORK_HOST, port,
                Optional.of(writeCertificate(dns(NETWORK_NAME), ip(NETWORK_HOST)))), "static/");

        assertThat(server.address().getPort()).isEqualTo(port);
        assertThat(server.networkAddress().orElseThrow().getPort()).isEqualTo(port);
    }

    @Test
    @Tag("NET-6")
    void answersPlainHttpOnTheNetworkAddressWithoutARedirect() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        URI network = server.networkAddress().orElseThrow();

        try (Socket socket = new Socket(NETWORK_HOST, network.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(page(network.getAuthority()).getBytes(StandardCharsets.US_ASCII));

            // The TLS handshake fails and the connection closes: no HTTP answer, so no redirect either.
            assertThat(readUntilClosed(socket.getInputStream())).doesNotContain("HTTP/");
        }
    }

    @Test
    @Tag("NET-7")
    void doesNotSpeakTlsOnLoopback() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));

        try (SSLSocket socket = (SSLSocket) trustingTheCertificate().getSocketFactory()
                .createSocket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(5000);
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLException.class);
        }
        assertThat(get(server.address()).statusCode()).isEqualTo(200);
    }

    @Test
    @Tag("NET-9")
    void acceptsTheWebSocketOfTheNetworkPageOverTls() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        URI network = server.networkAddress().orElseThrow();
        String authority = network.getAuthority();

        try (SSLSocket socket = tls(network.getPort(), NETWORK_NAME)) {
            assertThat(send(socket, webSocket(authority, "https://" + authority, networkKeyCookie())))
                    .startsWith("HTTP/1.1 101");
        }
    }

    @Test
    @Tag("NET-9")
    void refusesAPlainHttpOriginOnTheTlsConnector() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        URI network = server.networkAddress().orElseThrow();
        String authority = network.getAuthority();

        try (SSLSocket socket = tls(network.getPort(), NETWORK_NAME)) {
            assertThat(send(socket, webSocket(authority, "http://" + authority))).startsWith("HTTP/1.1 403");
        }
    }

    @Test
    @Tag("NET-9")
    void refusesATlsOriginOnLoopback() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        String authority = server.address().getAuthority();

        String cookie = "crawdad_device_" + server.address().getPort() + "="
                + devices.add("laptop", Listener.LOOPBACK).key();

        assertThat(handshake(authority, "https://" + authority, cookie)).startsWith("HTTP/1.1 403");
        assertThat(handshake(authority, "http://" + authority, cookie)).startsWith("HTTP/1.1 101");
    }

    @Test
    @Tag("NET-10")
    void refusesAHostThatDiffersFromTheServerName() throws Exception {
        startWithHost(dns(NETWORK_NAME), dns(OTHER_NAME), ip(NETWORK_HOST));
        int port = server.networkAddress().orElseThrow().getPort();
        String foreign = "evil.example:" + port;

        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, page(foreign))).startsWith("HTTP/1.1 400");
        }
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, webSocket(foreign, "https://" + foreign))).startsWith("HTTP/1.1 400");
        }
        // The certificate covers the IP address too, but the browser asked for the name.
        String byIp = NETWORK_HOST + ":" + port;
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, page(byIp))).startsWith("HTTP/1.1 400");
        }
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, webSocket(byIp, "https://" + byIp, networkKeyCookie())))
                    .startsWith("HTTP/1.1 400");
        }
        // The other way round: the name the certificate covers, after asking for another one it covers.
        String byName = NETWORK_NAME + ":" + port;
        try (SSLSocket socket = tls(port, OTHER_NAME)) {
            assertThat(send(socket, page(byName))).startsWith("HTTP/1.1 400");
        }
        // Names are compared ignoring case, as DNS does.
        String upperCase = NETWORK_NAME.toUpperCase(Locale.ROOT) + ":" + port;
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, page(upperCase))).startsWith("HTTP/1.1 200");
        }
        // The loopback page sent to the TLS connector is judged by the TLS connector.
        String loopback = server.address().getAuthority();
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            assertThat(send(socket, webSocket(loopback, "http://" + loopback))).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @Tag("NET-10")
    void servesAnIpAddressWithoutSni() throws Exception {
        // Browsers send no SNI for an IP address; a certificate for that IP must still serve it.
        startWithHost(ip(NETWORK_HOST));
        URI network = server.networkAddress().orElseThrow();
        String authority = network.getAuthority();
        assertThat(network.getHost()).isEqualTo(NETWORK_HOST);

        try (SSLSocket socket = tls(network.getPort(), null)) {
            assertThat(send(socket, webSocket(authority, "https://" + authority, networkKeyCookie())))
                    .startsWith("HTTP/1.1 101");
        }
        try (SSLSocket socket = tls(network.getPort(), null)) {
            assertThat(send(socket, page(authority))).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    @Tag("TLS-1")
    void speaksTls12AndNewerOnly() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        SslContextFactory.Server factory = server.networkTls().orElseThrow();
        int port = server.networkAddress().orElseThrow().getPort();

        assertThat(factory.getIncludeProtocols()).containsExactlyInAnyOrder("TLSv1.3", "TLSv1.2");
        try (SSLSocket socket = (SSLSocket) trustingTheCertificate().getSocketFactory().createSocket(NETWORK_HOST,
                port)) {
            socket.setSoTimeout(5000);
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.2");
        }
    }

    @Test
    @Tag("NET-8")
    void closesASilentConnectionToTheNetworkAddressAfter30Seconds() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));

        // Read from the connector rather than waited for: the test would take half a minute.
        assertThat(server.networkIdleTimeout()).contains(Duration.ofSeconds(30));
    }

    @ParameterizedTest(name = "{0}")
    @Tag("TLS-11")
    @ValueSource(strings = {"/ca.crt", "/ca.key", "/tls/ca.crt", "/tls/ca.key", "/data/tls/ca.crt",
        "/../tls/ca.crt", "/assets/../../tls/ca.key", "/assets/..%2F..%2Ftls%2Fca.key", "/devices.json"})
    void servesNeitherTheRootCertificateNorAnyKeyOnEitherAddress(String path) throws Exception {
        // The own root is in the data directory of the service, next to the registry it serves from.
        LocalCa.forThisMachine(data()).issue("10.1.2.3");
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        URI loopback = server.address();
        int port = server.networkAddress().orElseThrow().getPort();

        String overHttp;
        try (Socket socket = new Socket(loopback.getHost(), loopback.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(get(path, loopback.getAuthority()));
            overHttp = readUntilClosed(socket.getInputStream());
        }
        String overTls;
        try (SSLSocket socket = tls(port, NETWORK_NAME)) {
            socket.getOutputStream().write(get(path, NETWORK_NAME + ":" + port));
            overTls = readUntilClosed(socket.getInputStream());
        }

        for (String answer : List.of(overHttp, overTls)) {
            assertThat(answer).doesNotStartWith("HTTP/1.1 200").doesNotContain("-----BEGIN");
        }
    }

    @Test
    @Tag("NET-8")
    void limitsSimultaneousConnectionsOnTheNetworkAddress() throws Exception {
        startWithHost(dns(NETWORK_NAME), ip(NETWORK_HOST));
        int port = server.networkAddress().orElseThrow().getPort();
        List<Socket> open = new ArrayList<>();
        try {
            // 32 connections are served...
            for (int i = 0; i < 32; i++) {
                open.add(tls(port, NETWORK_NAME));
            }
            // ...one more is not, while they stay open...
            assertThatThrownBy(() -> tls(port, NETWORK_NAME, Duration.ofSeconds(1)).close())
                    .isInstanceOf(IOException.class);
            // ...and loopback is not limited by them.
            assertThat(get(server.address()).statusCode()).isEqualTo(200);

            // Once one of them closes, a new connection is served again.
            open.removeFirst().close();
            assertThat(connectsWithin(port, Duration.ofSeconds(10))).isTrue();
        } finally {
            for (Socket socket : open) {
                socket.close();
            }
        }
    }

    @Test
    void warnsAboutARefusedHandshakeWithItsSource() throws Exception {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler collect = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // Held in a variable: java.util.logging keeps loggers only weakly.
        Logger log = Logger.getLogger(CrawdadServer.class.getName());
        log.addHandler(collect);
        try {
            server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
            String authority = server.address().getAuthority();

            assertThat(handshake(authority, "https://evil.example\\x")).startsWith("HTTP/1.1 403");

            // The source is the client's address, named once by the aggregator, which also escapes the
            // header values exactly once.
            assertThat(warnings).containsExactly(
                    "WebSocket refused: Origin=https://evil.example\\\\x, Host=" + authority + " (from 127.0.0.1)");
        } finally {
            log.removeHandler(collect);
        }
    }

    @Test
    void upgradesToWebSocketForThePageItServes() throws Exception {
        DeviceRegistry registry = DeviceRegistry.open(data(), Clock.systemUTC());
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/", new FakeAgentAdapter(),
                Optional.empty(), registry, new ConnectionRegistry(), new PairingCodes());
        String authority = server.address().getAuthority();
        String cookie = "crawdad_device_" + server.address().getPort() + "="
                + registry.add("laptop", Listener.LOOPBACK).key();

        assertThat(handshake(authority, "http://" + authority, cookie)).startsWith("HTTP/1.1 101");
    }

    @Test
    void refusesTheWebSocketToAForeignPage() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        String authority = server.address().getAuthority();

        assertThat(handshake(authority, "https://evil.example")).startsWith("HTTP/1.1 403");
    }

    @Test
    void servesTheBuiltClientFromTheMainBuild() throws Exception {
        // The clients module builds the page only in its package phase; a plain `mvn test` has none.
        Path builtClient = Path.of("../clients/desktop/dist/index.html");
        assumeTrue(Files.exists(builtClient), "the client is not built: " + builtClient.toAbsolutePath());
        // Once it is built, the service build must have copied it.
        Path mainBuild = Path.of(CrawdadService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(mainBuild.resolve(CrawdadService.CLIENT_FILES + "index.html"))
                .as("the client page copied into the service build").exists();

        // Only the main build on the class path: the test page under the same name would win otherwise.
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader mainOnly = new URLClassLoader(new URL[] {mainBuild.toUri().toURL()}, null)) {
            thread.setContextClassLoader(mainOnly);
            server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), CrawdadService.CLIENT_FILES);
        } finally {
            thread.setContextClassLoader(original);
        }

        HttpResponse<String> response = get(server.address());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Files.readString(builtClient));

        Matcher script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(response.body());
        assertThat(script.find()).as("the page loads its script from /assets/").isTrue();
        HttpResponse<String> asset = get(server.address().resolve(script.group(1)));
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).contains("javascript"));
    }

    @Test
    void jettyLogsGoToTheSameLogAsTheService() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler collect = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // Held in a variable: java.util.logging keeps loggers only weakly.
        Logger jetty = Logger.getLogger("org.eclipse.jetty");
        jetty.addHandler(collect);
        try {
            server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        } finally {
            jetty.removeHandler(collect);
        }

        assertThat(records).as("Jetty reports its start through java.util.logging").isNotEmpty();
    }

    @Test
    void theServiceKeepsItsDevicesInItsDataDirectory() throws Exception {
        String key = DeviceRegistry.open(data(), Clock.systemUTC()).add("phone", Listener.LOOPBACK).key();

        server = CrawdadService.run(new String[] {"--port", "0"}, data(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        // The device paired before the start is known: the server reads the registry of the data directory.
        HttpResponse<String> device = HTTP.send(HttpRequest.newBuilder(server.address().resolve("/device"))
                .header("Cookie", "crawdad_device_" + server.address().getPort() + "=" + key).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(device.statusCode()).isEqualTo(200);
        assertThat(device.body()).isEqualTo("{\"name\":\"phone\"}");
    }

    @Test
    void pairsAndChecksTheDeviceWithTheSecurityHeadersOfThePage() throws Exception {
        PairingCodes codes = new PairingCodes();
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/", new FakeAgentAdapter(),
                Optional.empty(), DeviceRegistry.open(data(), Clock.systemUTC()), new ConnectionRegistry(),
                codes);
        URI address = server.address();
        int port = address.getPort();

        HttpResponse<String> paired = HTTP.send(HttpRequest.newBuilder(address.resolve("/pair"))
                .header("Origin", "http://127.0.0.1:" + port)
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + codes.issue("laptop").code() + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(paired.statusCode()).isEqualTo(204);
        assertHasThePageHeaders(paired);
        String setCookie = paired.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).matches("crawdad_device_" + port
                + "=[A-Za-z0-9_-]{43}; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict");

        HttpResponse<String> device = HTTP.send(HttpRequest.newBuilder(address.resolve("/device"))
                .header("Cookie", setCookie.substring(0, setCookie.indexOf(';'))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(device.statusCode()).isEqualTo(200);
        assertThat(device.body()).isEqualTo("{\"name\":\"laptop\"}");
        assertHasThePageHeaders(device);

        HttpResponse<String> unpaired = get(address.resolve("/device"));
        assertThat(unpaired.statusCode()).isEqualTo(401);
        assertHasThePageHeaders(unpaired);
    }

    @Test
    void pairsOnTheNetworkAddressWithASecureCookieForThePortItListensOn() throws Exception {
        TlsMaterial material = TlsMaterial.read(writeCertificate(ip(NETWORK_HOST)));
        NetworkCertificate files = new NetworkCertificate(material.privateKey(), material.chain(), NETWORK_HOST,
                Optional.empty());
        PairingCodes codes = new PairingCodes();
        DeviceRegistry devices = DeviceRegistry.open(data(), Clock.systemUTC());
        server = CrawdadServer.start(new ServiceConfig(NETWORK_HOST, 0), "static/", new FakeAgentAdapter(),
                Optional.of(files), devices, new ConnectionRegistry(), codes);
        URI network = server.networkAddress().orElseThrow();
        HttpClient https = HttpClient.newBuilder().sslContext(trustingTheCertificate()).build();

        HttpResponse<String> paired = https.send(HttpRequest.newBuilder(network.resolve("/pair"))
                .header("Origin", "https://" + network.getAuthority())
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + codes.issue("phone").code() + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(paired.statusCode()).isEqualTo(204);
        String setCookie = paired.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).matches("__Host-crawdad_device_" + network.getPort()
                + "=[A-Za-z0-9_-]{43}; Path=/; Max-Age=34560000; Secure; HttpOnly; SameSite=Strict");
        String key = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(devices.devices(false).getFirst().listener()).isEqualTo(Listener.NETWORK);

        // The key works on the network address only, not on loopback of the same service.
        assertThat(https.send(HttpRequest.newBuilder(network.resolve("/device"))
                .header("Cookie", "__Host-crawdad_device_" + network.getPort() + "=" + key).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        String loopbackKey = devices.add("laptop", Listener.LOOPBACK).key();
        assertThat(https.send(HttpRequest.newBuilder(network.resolve("/device"))
                .header("Cookie", "__Host-crawdad_device_" + network.getPort() + "=" + loopbackKey).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(HTTP.send(HttpRequest.newBuilder(server.address().resolve("/device"))
                .header("Cookie", "crawdad_device_" + server.address().getPort() + "=" + key).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void tellsADisconnectedClientFromOtherSendFailures() {
        assertThat(CrawdadServer.Endpoint.isClientGone(new EofException("reset"))).isTrue();
        assertThat(CrawdadServer.Endpoint.isClientGone(new ClosedChannelException())).isTrue();
        assertThat(CrawdadServer.Endpoint.isClientGone(new IOException("disk full"))).isFalse();
        assertThat(CrawdadServer.Endpoint.isClientGone(new IllegalStateException())).isFalse();
    }

    @Test
    void escapesWhatTheClientSentBeforeLoggingTheCloseAndTheError() {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler collect = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // Held in a variable: java.util.logging keeps loggers only weakly.
        Logger log = Logger.getLogger(CrawdadServer.class.getName());
        log.addHandler(collect);
        try {
            CrawdadServer.Endpoint endpoint = new CrawdadServer.Endpoint(new FakeAgentAdapter(),
                    new ConnectionRegistry(), "device-1");

            // A paired client chooses the close reason: a forged line and a terminal colour.
            endpoint.onWebSocketClose(1000, "\nINFO: Device paired: attacker \u001b[31mFAKE",
                    org.eclipse.jetty.websocket.api.Callback.NOOP);
            endpoint.onWebSocketError(new EofException("reset\n\u001b[31m"));
        } finally {
            log.removeHandler(collect);
        }

        SimpleFormatter formatter = new SimpleFormatter();
        assertThat(records.stream().map(formatter::formatMessage).toList()).containsExactly(
                "WebSocket closed: 1000 \\x0AINFO: Device paired: attacker \\x1B[31mFAKE",
                "WebSocket client disconnected: org.eclipse.jetty.io.EofException: reset\\x0A\\x1B[31m");
    }

    /** A data directory of the test, so that no test touches the owner's real one. */
    private DataDirectory data() {
        return new DataDirectory(temp.resolve("data"), temp.resolve("run"));
    }

    /** The headers every answer of the page carries, and no caching of pairing answers. */
    private static void assertHasThePageHeaders(HttpResponse<?> response) {
        assertThat(response.headers().allValues("Content-Security-Policy")).containsExactly("default-src 'self'; "
                + "script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                + "form-action 'self'");
        assertThat(response.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.headers().allValues("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(response.headers().allValues("Cross-Origin-Opener-Policy")).containsExactly("same-origin");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
    }

    private static HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a WebSocket handshake to /ws over plain HTTP and returns the status line of the answer. */
    private static String handshake(String authority, String origin) throws IOException {
        return handshake(authority, origin, null);
    }

    /** As {@link #handshake(String, String)}, with this {@code Cookie} header unless it is null. */
    private static String handshake(String authority, String origin, String cookie) throws IOException {
        URI address = URI.create("http://" + authority);
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(5000);
            return send(socket, webSocket(authority, origin, cookie));
        }
    }

    private static String webSocket(String host, String origin) {
        return webSocket(host, origin, null);
    }

    private static String webSocket(String host, String origin, String cookie) {
        return "GET /ws HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Origin: " + origin + "\r\n"
                + (cookie == null ? "" : "Cookie: " + cookie + "\r\n")
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
    }

    /** A raw {@code GET} of the path, sent as it is, so that a dot segment or an encoded slash reaches the server. */
    private static byte[] get(String path, String host) {
        return ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String page(String host) {
        return "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
    }

    /** Writes the request and returns the status line of the answer. */
    private static String send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
    }

    /** Everything the server sends until it closes the connection; a reset ends it as well. */
    private static String readUntilClosed(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                received.write(buffer, 0, read);
            }
        } catch (SocketException reset) {
            // The peer may reset the connection instead of closing it.
        }
        return received.toString(StandardCharsets.ISO_8859_1);
    }

    /** Starts the server with {@code --host 127.0.0.2}, certificate files with these names and {@link #devices}. */
    private void startWithHost(GeneralName... names) throws Exception {
        ServiceConfig config = new ServiceConfig(NETWORK_HOST, 0, Optional.of(writeCertificate(names)));
        devices = DeviceRegistry.open(data(), Clock.systemUTC());
        server = CrawdadServer.start(config, "static/", new FakeAgentAdapter(), NetworkCertificate.forConfig(config),
                devices, new ConnectionRegistry(), new PairingCodes());
    }

    /** The key cookie of a device newly paired on the network address of a server started by {@link #startWithHost}. */
    private String networkKeyCookie() throws IOException {
        return "__Host-crawdad_device_" + server.networkAddress().orElseThrow().getPort() + "="
                + devices.add("phone", Listener.NETWORK).key();
    }

    /** A TLS connection to the network address that has finished its handshake. */
    private SSLSocket tls(int port, String sni) throws Exception {
        return tls(port, sni, Duration.ofSeconds(5));
    }

    /** {@code sni} null sends none, as a browser does for an IP address. */
    private SSLSocket tls(int port, String sni, Duration timeout) throws Exception {
        SSLSocket socket = (SSLSocket) trustingTheCertificate().getSocketFactory().createSocket(NETWORK_HOST, port);
        try {
            socket.setSoTimeout((int) timeout.toMillis());
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(sni == null ? List.of() : List.of(new SNIHostName(sni)));
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private boolean connectsWithin(int port, Duration limit) throws Exception {
        Instant end = Instant.now().plus(limit);
        while (Instant.now().isBefore(end)) {
            try (SSLSocket socket = tls(port, NETWORK_NAME, Duration.ofSeconds(1))) {
                return socket.getSession().isValid();
            } catch (IOException e) {
                // The server is still at its limit; try again.
            }
        }
        return false;
    }

    /** Live threads of a certificate watcher that were not there before. */
    private static Set<Thread> watcherThreadsSince(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.getName().startsWith("crawdad-certificate-"))
                .collect(Collectors.toSet());
    }

    private SSLContext trustingTheCertificate() throws Exception {
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("test", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    /** Writes a self-signed certificate with these alternative names and its key as PEM files. */
    private TlsFiles writeCertificate(GeneralName... names) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=Crawdad test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(30))), subject, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        Path certificateFile = temp.resolve("node.crt");
        Path keyFile = temp.resolve("node.key");
        Files.writeString(certificateFile, pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(keyFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        return new TlsFiles(certificateFile, keyFile);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static GeneralName dns(String name) {
        return new GeneralName(GeneralName.dNSName, name);
    }

    private static GeneralName ip(String address) {
        return new GeneralName(GeneralName.iPAddress, address);
    }
}
