package io.github.artemdbondarenko.crawdad.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.artemdbondarenko.crawdad.service.ServiceConfig.TlsFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateWatcherTest {

    private static final String NAME = "crawdad.test";
    /** Short enough for quick tests, long enough to tell one burst of events from two. */
    private static final Duration PAUSE = Duration.ofMillis(300);
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @TempDir
    Path temp;

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
    /** Held in a field: java.util.logging keeps loggers only weakly. */
    private final Logger log = Logger.getLogger(CertificateWatcher.class.getName());

    private Server jetty;
    private SslContextFactory.Server tls;
    private int port;
    private CertificateWatcher watcher;

    @BeforeEach
    void collectLog() {
        log.addHandler(collect);
    }

    @AfterEach
    void stop() throws Exception {
        log.removeHandler(collect);
        if (watcher != null) {
            watcher.close();
        }
        if (jetty != null) {
            jetty.stop();
        }
    }

    @Test
    void reloadsARenewedPairForNewConnectionsAndKeepsOpenOnes() throws Exception {
        TlsFiles files = files(temp, temp);
        write(files, issue(NAME, NOW.plus(Duration.ofDays(30))));
        serve(files);
        watch(files);
        SSLSocket open = connect();

        Pair renewed = issue(NAME, NOW.plus(Duration.ofDays(90)));
        write(files, renewed);

        // A new connection gets the renewed certificate once the pause is over...
        awaitServed(renewed.notAfter());
        assertThat(messages(Level.INFO)).containsExactly("Certificate reloaded from " + files.certificate()
                + ", valid until " + renewed.notAfter());
        // ...while the connection opened before still works, on the certificate it was opened with.
        assertThat(((X509Certificate) open.getSession().getPeerCertificates()[0]).getNotAfter().toInstant())
                .isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(request(open)).startsWith("HTTP/1.1 ");
        open.close();
        assertThat(messages(Level.WARNING)).isEmpty();
    }

    @Test
    void keepsTheOldCertificateWhenOnlyTheCertificateIsReplaced() throws Exception {
        TlsFiles files = files(temp, temp);
        Pair old = issue(NAME, NOW.plus(Duration.ofDays(30)));
        write(files, old);
        serve(files);
        watch(files);

        // A new certificate next to the old key: the pair does not match.
        Files.writeString(files.certificate(), issue(NAME, NOW.plus(Duration.ofDays(90))).certificatePem());

        awaitWarning(message -> message.contains("does not belong to"));
        assertThat(messages(Level.WARNING)).containsExactly("Certificate not reloaded, still serving the previous one: "
                + files.privateKey() + ": does not belong to " + files.certificate());
        assertThat(servedNotAfter()).isEqualTo(old.notAfter());
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void keepsTheOldCertificateWhenAFileIsCorrupted() throws Exception {
        TlsFiles files = files(temp, temp);
        Pair old = issue(NAME, NOW.plus(Duration.ofDays(30)));
        write(files, old);
        serve(files);
        watch(files);

        Files.writeString(files.certificate(), "not a certificate\n");

        awaitWarning(message -> message.endsWith("not a PEM certificate"));
        assertThat(messages(Level.WARNING)).containsExactly("Certificate not reloaded, still serving the previous one: "
                + files.certificate() + ": not a PEM certificate");
        assertThat(servedNotAfter()).isEqualTo(old.notAfter());
    }

    @Test
    void keepsTheOldCertificateWhenTheNameChanges() throws Exception {
        TlsFiles files = files(temp, temp);
        Pair old = issue(NAME, NOW.plus(Duration.ofDays(30)));
        write(files, old);
        serve(files);
        watch(files);

        write(files, issue("other.test", NOW.plus(Duration.ofDays(90))));

        // The printed address and the Origin check are bound to the old name; only a restart can move them.
        awaitWarning(message -> message.contains("name changed"));
        assertThat(messages(Level.WARNING)).containsExactly("Certificate " + files.certificate()
                + " not reloaded: its name changed from crawdad.test to other.test;"
                + " restart the service to serve it. Still serving the previous certificate.");
        assertThat(servedNotAfter()).isEqualTo(old.notAfter());
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void reloadsOnceWhenCertificateAndKeyArriveWithinThePause() throws Exception {
        TlsFiles files = files(temp, temp);
        write(files, issue(NAME, NOW.plus(Duration.ofDays(30))));
        serve(files);
        watcher = CertificateWatcher.start(files, tls, NAME, NOW.plus(Duration.ofDays(30)));

        // As tailscale cert does: the certificate first, the key a moment later, well within the pause.
        Pair renewed = issue(NAME, NOW.plus(Duration.ofDays(90)));
        Files.writeString(files.certificate(), renewed.certificatePem());
        Thread.sleep(CertificateWatcher.PAUSE.dividedBy(5).toMillis());
        Files.writeString(files.privateKey(), renewed.keyPem());

        awaitServed(renewed.notAfter());
        // No read of the half-written pair in between, and no second reload after it.
        Thread.sleep(CertificateWatcher.PAUSE.multipliedBy(2).toMillis());
        assertThat(messages(Level.INFO)).hasSize(1);
        assertThat(messages(Level.WARNING)).isEmpty();
    }

    @Test
    void watchesBothDirectoriesWhenTheyDiffer() throws Exception {
        TlsFiles files = files(Files.createDirectory(temp.resolve("certs")), Files.createDirectory(temp.resolve("keys")));
        write(files, issue(NAME, NOW.plus(Duration.ofDays(30))));
        serve(files);
        watch(files);
        Pair renewed = issue(NAME, NOW.plus(Duration.ofDays(90)));

        // The certificate directory is watched: the new certificate with the old key is refused...
        Files.writeString(files.certificate(), renewed.certificatePem());
        awaitWarning(message -> message.contains("does not belong to"));
        // ...and the key directory too: its key alone completes the pair.
        Files.writeString(files.privateKey(), renewed.keyPem());
        awaitServed(renewed.notAfter());
    }

    @Test
    void ignoresOtherFilesInTheDirectory() throws Exception {
        TlsFiles files = files(temp, temp);
        Pair old = issue(NAME, NOW.plus(Duration.ofDays(30)));
        write(files, old);
        serve(files);
        watch(files);

        Files.writeString(temp.resolve("notes.txt"), "unrelated\n");
        Thread.sleep(PAUSE.multipliedBy(3).toMillis());

        assertThat(records).isEmpty();
        // The watcher is still alive: a real change is picked up.
        Pair renewed = issue(NAME, NOW.plus(Duration.ofDays(90)));
        write(files, renewed);
        awaitServed(renewed.notAfter());
    }

    @Test
    @Tag("LOG-2")
    @Tag("TLS-5")
    void keepsTheOldContextWhenTheTlsFactoryRefusesTheReloadAndNamesTheFile() throws Exception {
        TlsFiles files = files(temp, temp);
        write(files, issue(NAME, NOW.plus(Duration.ofDays(30))));
        SslContextFactory.Server refusing = new SslContextFactory.Server() {
            @Override
            public void reload(Consumer<org.eclipse.jetty.util.ssl.SslContextFactory> consumer) throws Exception {
                throw new IllegalStateException("refused for the test");
            }
        };
        watcher = CertificateWatcher.start(files, refusing, NAME, NOW.plus(Duration.ofDays(30)), Clock.systemUTC(),
                PAUSE, Duration.ofDays(1));

        write(files, issue(NAME, NOW.plus(Duration.ofDays(90))));

        awaitWarning(message -> message.contains("refused for the test"));
        assertThat(messages(Level.WARNING)).containsExactly("Certificate not reloaded, still serving the previous one: "
                + files.certificate() + ": java.lang.IllegalStateException: refused for the test");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void warnsAtStartAboutACertificateEndingInLessThan14Days() throws Exception {
        TlsFiles files = files(temp, temp);
        Instant end = NOW.plus(Duration.ofDays(10));

        watcher = CertificateWatcher.start(files, new SslContextFactory.Server(), NAME, end, clockAt(NOW), PAUSE,
                Duration.ofDays(1));

        assertThat(messages(Level.WARNING)).containsExactly(expiryWarning(files, end));
    }

    @Test
    void warnsOnlyWhenLessThan14DaysRemain() throws Exception {
        TlsFiles files = files(temp, temp);
        Instant end = NOW.plus(Duration.ofDays(60));

        // Exactly 14 days left: not yet.
        try (CertificateWatcher exactly = CertificateWatcher.start(files, new SslContextFactory.Server(), NAME, end,
                clockAt(end.minus(CertificateWatcher.EXPIRY_NOTICE)), PAUSE, Duration.ofDays(1))) {
            assertThat(exactly).isNotNull();
            assertThat(messages(Level.WARNING)).isEmpty();
        }
        // A second less: the warning.
        try (CertificateWatcher less = CertificateWatcher.start(files, new SslContextFactory.Server(), NAME, end,
                clockAt(end.minus(CertificateWatcher.EXPIRY_NOTICE).plusSeconds(1)), PAUSE, Duration.ofDays(1))) {
            assertThat(less).isNotNull();
            assertThat(messages(Level.WARNING)).containsExactly(expiryWarning(files, end));
        }
        assertThat(CertificateWatcher.EXPIRY_NOTICE).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void repeatsTheExpiryCheckOnTheTimerUntilTheCertificateIsRenewed() throws Exception {
        TlsFiles files = files(temp, temp);
        Pair ending = issue(NAME, NOW.plus(Duration.ofDays(10)));
        write(files, ending);
        serve(files);
        // The daily timer, shortened so that it fires several times during the test.
        Duration interval = Duration.ofMillis(100);
        watcher = CertificateWatcher.start(files, tls, NAME, ending.notAfter(), Clock.systemUTC(), PAUSE, interval);

        await(() -> count(expiryWarning(files, ending.notAfter())) >= 3);

        // After the renewal the timer checks the new end and stays quiet.
        Pair renewed = issue(NAME, NOW.plus(Duration.ofDays(90)));
        write(files, renewed);
        awaitServed(renewed.notAfter());
        await(() -> !messages(Level.INFO).isEmpty());
        long before = messages(Level.WARNING).size();
        Thread.sleep(interval.multipliedBy(5).toMillis());
        assertThat(messages(Level.WARNING)).hasSize((int) before);
    }

    @Test
    void checksExpiryDailyByDefault() {
        assertThat(CertificateWatcher.EXPIRY_CHECK_INTERVAL).isEqualTo(Duration.ofDays(1));
        assertThat(CertificateWatcher.PAUSE).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void stillChecksExpiryWhenTheFilesCannotBeWatched() throws Exception {
        TlsFiles files = new TlsFiles(temp.resolve("node.crt"), temp.resolve("missing").resolve("node.key"));
        Instant end = NOW.plus(Duration.ofDays(10));

        watcher = CertificateWatcher.start(files, new SslContextFactory.Server(), NAME, end, clockAt(NOW), PAUSE,
                Duration.ofDays(1));

        List<String> warnings = messages(Level.WARNING);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.getFirst()).startsWith("Certificate files are not watched,"
                + " a renewed certificate needs a restart of the service: java.nio.file.NoSuchFileException: ")
                .contains("missing");
        assertThat(warnings.get(1)).isEqualTo(expiryWarning(files, end));
    }

    @Test
    void stopsItsThreadsWhenClosed() throws Exception {
        TlsFiles files = files(temp, temp);
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        watch(files);
        Set<Thread> started = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.getName().startsWith("crawdad-certificate-"))
                .collect(Collectors.toSet());
        assertThat(started).extracting(Thread::getName)
                .contains("crawdad-certificate-watcher", "crawdad-certificate-reload");

        watcher.close();
        watcher.close();

        assertThat(started).noneMatch(Thread::isAlive);
    }

    private void watch(TlsFiles files) {
        watcher = CertificateWatcher.start(files, tls == null ? new SslContextFactory.Server() : tls, NAME,
                NOW.plus(Duration.ofDays(30)), Clock.systemUTC(), PAUSE, Duration.ofDays(1));
    }

    /** Serves the current files on a TLS connector of 127.0.0.1, as the network connector of the service does. */
    private void serve(TlsFiles files) throws Exception {
        String password = "test";
        tls = new SslContextFactory.Server();
        tls.setKeyStore(TlsMaterial.read(files).keyStore(password.toCharArray()));
        tls.setKeyStorePassword(password);
        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty, new SslConnectionFactory(tls, "http/1.1"),
                new HttpConnectionFactory());
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        jetty.addConnector(connector);
        jetty.start();
        port = connector.getLocalPort();
    }

    /** A new TLS connection with a fresh client context, so that no session is resumed. */
    private SSLSocket connect() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new TrustingAnything()}, null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        socket.startHandshake();
        return socket;
    }

    private Instant servedNotAfter() throws Exception {
        try (SSLSocket socket = connect()) {
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getNotAfter().toInstant();
        }
    }

    private void awaitServed(Instant notAfter) throws Exception {
        Instant end = Instant.now().plus(WAIT);
        while (!servedNotAfter().equals(notAfter)) {
            if (Instant.now().isAfter(end)) {
                fail("The certificate valid until " + notAfter + " was not served; log: " + messages(Level.ALL));
            }
            Thread.sleep(20);
        }
    }

    private void awaitWarning(Predicate<String> matches) throws Exception {
        await(() -> messages(Level.WARNING).stream().anyMatch(matches));
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        Instant end = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                fail("Condition not met; log: " + messages(Level.ALL));
            }
            Thread.sleep(20);
        }
    }

    private static String request(SSLSocket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[64];
        int read = in.read(buffer);
        return new String(buffer, 0, Math.max(read, 0), StandardCharsets.US_ASCII);
    }

    /** Formatted messages of exactly this level, or of every level for {@link Level#ALL}. */
    private List<String> messages(Level level) {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> level == Level.ALL || record.getLevel() == level)
                .map(formatter::formatMessage).toList();
    }

    private long count(String message) {
        return messages(Level.WARNING).stream().filter(message::equals).count();
    }

    private static String expiryWarning(TlsFiles files, Instant end) {
        return "Certificate " + files.certificate() + " expires on " + end
                + ", in less than 14 days; renew it, the service picks up the renewed files by itself";
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static TlsFiles files(Path certificateDirectory, Path keyDirectory) {
        return new TlsFiles(certificateDirectory.resolve("node.crt"), keyDirectory.resolve("node.key"));
    }

    private static void write(TlsFiles files, Pair pair) throws IOException {
        Files.writeString(files.certificate(), pair.certificatePem());
        Files.writeString(files.privateKey(), pair.keyPem());
    }

    /** A self-signed certificate for {@code name}, valid from a day ago, and its key. */
    private static Pair issue(String name, Instant notAfter) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=" + name);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.valueOf(System.nanoTime()), Date.from(NOW.minus(Duration.ofDays(1))), Date.from(notAfter),
                subject, keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, name)));
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
        return new Pair(keys, certificate);
    }

    private record Pair(KeyPair keys, X509Certificate certificate) {

        Instant notAfter() {
            return certificate.getNotAfter().toInstant();
        }

        String certificatePem() throws IOException {
            try {
                return pem("CERTIFICATE", certificate.getEncoded());
            } catch (java.security.cert.CertificateEncodingException e) {
                throw new IOException(e);
            }
        }

        String keyPem() {
            return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
        }

        private static String pem(String type, byte[] der) {
            return "-----BEGIN " + type + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                    + "\n-----END " + type + "-----\n";
        }
    }

    /** Accepts any server certificate: the tests look at which one is served, not whether it is trusted. */
    private static final class TrustingAnything implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
