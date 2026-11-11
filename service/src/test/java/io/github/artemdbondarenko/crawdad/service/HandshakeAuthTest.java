package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.Socket;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The device key in the {@code /ws} handshake, on a real server with loopback over HTTP and the network
 * address {@code 127.0.0.2} over TLS: 403 for a foreign page, 401 without a key that works on the
 * address, 101 with one, and the last login of the device.
 */
class HandshakeAuthTest {

    private static final String NETWORK_HOST = "127.0.0.2";
    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30Z");
    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0";

    @TempDir
    Path temp;

    private final CountingAdapter adapter = new CountingAdapter();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler collect = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    // Held in a field: java.util.logging keeps loggers only weakly.
    private final Logger log = Logger.getLogger(CrawdadServer.class.getName());

    private DataDirectory data;
    private DeviceRegistry devices;
    private X509Certificate certificate;
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private CrawdadServer server;
    private String laptopKey;
    private String phoneKey;

    @BeforeEach
    void startServer() throws Exception {
        log.addHandler(collect);
        data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        devices = DeviceRegistry.open(data, Clock.fixed(NOW, ZoneOffset.UTC));
        laptopKey = devices.add("laptop", Listener.LOOPBACK).key();
        phoneKey = devices.add("phone", Listener.NETWORK).key();
        server = CrawdadServer.start(new ServiceConfig(NETWORK_HOST, 0), "static/", adapter,
                Optional.of(networkCertificate()), devices, connections, new PairingCodes());
    }

    @AfterEach
    void stopServer() throws Exception {
        log.removeHandler(collect);
        server.stop();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"no key", "forged key", "revoked key", "loopback key on the network address",
        "network key on loopback", "key under the cookie name of another port"})
    @Tag("DEV-2")
    void aHandshakeWithoutAKeyThatWorksHereIsUnauthorized(String presented) throws Exception {
        String loopbackCookie = loopbackCookieName();
        String networkCookie = networkCookieName();
        String status = switch (presented) {
            case "no key" -> loopback(null);
            case "forged key" -> loopback(loopbackCookie + "=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            case "revoked key" -> {
                devices.revoke("laptop", connections);
                yield loopback(loopbackCookie + "=" + laptopKey);
            }
            case "loopback key on the network address" -> network(networkCookie + "=" + laptopKey);
            case "network key on loopback" -> loopback(loopbackCookie + "=" + phoneKey);
            case "key under the cookie name of another port" -> loopback("crawdad_device_1=" + laptopKey);
            default -> throw new AssertionError(presented);
        };

        assertThat(status).startsWith("HTTP/1.1 401");
        // No session was opened and no device counts as seen.
        assertThat(adapter.calls.get()).isZero();
        assertThat(devices.devices(true)).extracting(Device::lastSeen).containsOnlyNulls();
        // Each connection to 127.0.0.2 also comes from 127.0.0.1, the source address of the loopback interface.
        assertThat(messages(java.util.logging.Level.WARNING))
                .containsExactly("WebSocket refused: no valid device key (from 127.0.0.1)");
        assertThat(messages(java.util.logging.Level.INFO)).noneMatch(line -> line.startsWith("Device connected"));
        assertThat(allLogText()).doesNotContain(laptopKey).doesNotContain(phoneKey);
    }

    @Test
    @Tag("DEV-2")
    void aKeyPairedOnLoopbackOpensTheConversationAndRecordsTheLogin() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            String status = handshake(socket, server.address().getAuthority(), "http://",
                    List.of("Cookie: " + loopbackCookieName() + "=" + laptopKey, "User-Agent: " + FIREFOX));

            assertThat(status).startsWith("HTTP/1.1 101");
            Device laptop = device("laptop");
            assertThat(laptop.lastSeen()).isEqualTo(NOW);
            assertThat(laptop.lastAddress()).isEqualTo("127.0.0.1");
            assertThat(laptop.lastUserAgent()).isEqualTo(FIREFOX);
            assertThat(device("phone").lastSeen()).isNull();
            assertThat(messages(java.util.logging.Level.INFO)).contains("Device connected: laptop from 127.0.0.1");
            assertThat(messages(java.util.logging.Level.WARNING)).isEmpty();
            // The session starts once the upgrade completes, right after the answer.
            awaitTrue(() -> adapter.calls.get() == 1);
        }
        assertThat(allLogText()).doesNotContain(laptopKey);
    }

    @Test
    void aKeyPairedOnTheNetworkAddressOpensTheConversationThere() throws Exception {
        assertThat(network(networkCookieName() + "=" + phoneKey)).startsWith("HTTP/1.1 101");

        assertThat(device("phone").lastSeen()).isEqualTo(NOW);
        assertThat(device("phone").lastUserAgent()).isNull();
        assertThat(messages(java.util.logging.Level.INFO)).contains("Device connected: phone from 127.0.0.1");
    }

    @Test
    void findsTheWorkingKeyAmongSeveralCookiesOfTheSameName() throws Exception {
        String name = loopbackCookieName();

        assertThat(loopback(name + "=forged; " + name + "=" + laptopKey)).startsWith("HTTP/1.1 101");

        assertThat(device("laptop").lastSeen()).isEqualTo(NOW);
    }

    @Test
    void aForeignPageIsRefusedBeforeTheKeyIsLookedAt() throws Exception {
        String authority = server.address().getAuthority();
        String cookie = "Cookie: " + loopbackCookieName() + "=" + laptopKey;

        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            assertThat(handshake(socket, authority, "https://evil.example", List.of(cookie)))
                    .startsWith("HTTP/1.1 403");
        }
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            assertThat(handshake(socket, "evil.example", "http://" + authority, List.of(cookie)))
                    .startsWith("HTTP/1.1 403");
        }

        assertThat(adapter.calls.get()).isZero();
        assertThat(device("laptop").lastSeen()).isNull();
        // One source within the interval: the aggregator writes the first refusal and counts the second.
        assertThat(messages(java.util.logging.Level.WARNING)).containsExactly(
                "WebSocket refused: Origin=https://evil.example, Host=" + authority + " (from 127.0.0.1)");
    }

    @Test
    void aLoginThatCannotBeSavedStillOpensTheConversation() throws Exception {
        // The registry writes next to devices.json; without its directory every write fails.
        Files.move(data.data(), temp.resolve("moved"));

        assertThat(loopback(loopbackCookieName() + "=" + laptopKey)).startsWith("HTTP/1.1 101");

        List<LogRecord> warnings = records.stream()
                .filter(record -> record.getLevel() == java.util.logging.Level.WARNING).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getMessage()).isEqualTo("Could not record the login of laptop from 127.0.0.1");
        assertThat(warnings.getFirst().getThrown()).isInstanceOf(IOException.class);
        assertThat(messages(java.util.logging.Level.INFO)).contains("Device connected: laptop from 127.0.0.1");
        assertThat(device("laptop").lastSeen()).isNull();
        assertThat(allLogText()).doesNotContain(laptopKey);
    }

    @Test
    void aConnectionIsRegisteredUnderItsDeviceUntilItCloses() throws Exception {
        String laptopId = device("laptop").id();

        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            assertThat(handshake(socket, server.address().getAuthority(), "http://",
                    List.of("Cookie: " + loopbackCookieName() + "=" + laptopKey))).startsWith("HTTP/1.1 101");

            // Registered before the answer, so a revocation from now on finds it.
            assertThat(connections.connectionsOf(laptopId)).hasSize(1);
            assertThat(connections.connectionsOf(device("phone").id())).isEmpty();
        }

        // The client went away without a close frame; the connection still leaves the registry.
        awaitTrue(() -> connections.connectionsOf(laptopId).isEmpty());
    }

    @Test
    void anEndpointClosedBeforeItOpensClosesOnOpenWithoutAConversation() {
        CrawdadServer.Endpoint endpoint = new CrawdadServer.Endpoint(adapter, new ConnectionRegistry(), "id");
        List<String> closes = new ArrayList<>();

        endpoint.close(4001, "unpaired");
        endpoint.onWebSocketOpen(session(closes));

        assertThat(closes).containsExactly("4001 unpaired");
        assertThat(adapter.calls.get()).isZero();
    }

    @Test
    void anOpenEndpointClosesItsSession() {
        CrawdadServer.Endpoint endpoint = new CrawdadServer.Endpoint(adapter, new ConnectionRegistry(), "id");
        List<String> closes = new ArrayList<>();
        endpoint.onWebSocketOpen(session(closes));
        assertThat(adapter.calls.get()).isEqualTo(1);

        endpoint.close(4001, "unpaired");

        assertThat(closes).containsExactly("4001 unpaired");
    }

    @Test
    void aClosedEndpointLeavesTheRegistry() {
        ConnectionRegistry registry = new ConnectionRegistry();
        CrawdadServer.Endpoint endpoint = new CrawdadServer.Endpoint(adapter, registry, "id");
        registry.register("id", endpoint);
        AtomicBoolean completed = new AtomicBoolean();

        endpoint.onWebSocketClose(1000, "bye", org.eclipse.jetty.websocket.api.Callback.from(
                () -> completed.set(true), failure -> {}));

        assertThat(registry.connectionsOf("id")).isEmpty();
        // Jetty finishes the close only once the callback completes.
        assertThat(completed).isTrue();
    }

    private String loopbackCookieName() {
        return "crawdad_device_" + server.address().getPort();
    }

    private String networkCookieName() {
        return "__Host-crawdad_device_" + server.networkAddress().orElseThrow().getPort();
    }

    private Device device(String name) {
        return devices.devices(true).stream().filter(device -> device.name().equals(name)).findFirst().orElseThrow();
    }

    /** A handshake from the loopback page with this {@code Cookie} header, left out when null. */
    private String loopback(String cookie) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            return handshake(socket, server.address().getAuthority(), "http://", cookieHeader(cookie));
        }
    }

    /** A handshake from the network page over TLS with this {@code Cookie} header. */
    private String network(String cookie) throws Exception {
        int port = server.networkAddress().orElseThrow().getPort();
        try (SSLSocket socket = (SSLSocket) trustingTheCertificate().getSocketFactory()
                .createSocket(NETWORK_HOST, port)) {
            return handshake(socket, server.networkAddress().orElseThrow().getAuthority(), "https://",
                    cookieHeader(cookie));
        }
    }

    private static List<String> cookieHeader(String cookie) {
        return cookie == null ? List.of() : List.of("Cookie: " + cookie);
    }

    /**
     * Sends a WebSocket handshake for {@code host} and returns the status line of the answer. An
     * {@code origin} that is only a scheme becomes the page of {@code host} itself.
     */
    private static String handshake(Socket socket, String host, String origin, List<String> headers)
            throws IOException {
        socket.setSoTimeout(5000);
        StringBuilder request = new StringBuilder("GET " + CrawdadServer.WEBSOCKET_PATH + " HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Origin: ").append(origin.endsWith("://") ? origin + host : origin).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        headers.forEach(header -> request.append(header).append("\r\n"));
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
    }

    /** A session that only records the status and reason it is closed with. */
    private static Session session(List<String> closes) {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && args != null && args.length == 3) {
                        closes.add(args[0] + " " + args[1]);
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("the condition within 5 s").isBefore(end);
            Thread.sleep(20);
        }
    }

    private List<String> messages(java.util.logging.Level level) {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> record.getLevel() == level).map(formatter::formatMessage).toList();
    }

    /** Every log line of the server as written, with causes. */
    private String allLogText() {
        SimpleFormatter formatter = new SimpleFormatter();
        return String.join("\n", records.stream().map(formatter::format).toList());
    }

    /** A self-signed certificate for 127.0.0.2, kept in {@link #certificate} for the client to trust. */
    private NetworkCertificate networkCertificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=Crawdad test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(30))), subject, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, NETWORK_HOST)));
        certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        return new NetworkCertificate(pair.getPrivate(), List.of(certificate), NETWORK_HOST, Optional.empty());
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

    /** The real fake adapter, counting how often a session was started or a message sent. */
    private static final class CountingAdapter implements AgentAdapter {

        private final AgentAdapter delegate = new FakeAgentAdapter();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String startSession() {
            calls.incrementAndGet();
            return delegate.startSession();
        }

        @Override
        public void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener) {
            calls.incrementAndGet();
            delegate.sendMessage(sessionId, text, listener);
        }
    }
}
