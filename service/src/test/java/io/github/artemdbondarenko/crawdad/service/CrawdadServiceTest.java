package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock.AlreadyRunningException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How {@link CrawdadService#run} puts the server, the control socket and the instance lock together. */
class CrawdadServiceTest {

    private static final String NETWORK_HOST = "127.0.0.2";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern CODE = Pattern.compile(
            "\\{\"code\":\"([0-9]{6})\",\"expires_in\":300,\"urls\":\\{\"loopback\":\"(http://127.0.0.1:[0-9]+/)\"}}");

    @TempDir
    Path temp;

    private CrawdadServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void theServicePairsADeviceThroughItsControlSocket() throws Exception {
        server = run("--port", "0");
        Path socket = data().runtime().resolve(ControlSocket.FILE_NAME);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket))).isEqualTo("rw-------");

        // crawdad pair asks for a code; the page on this machine exchanges it.
        try (SocketChannel pair = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            BufferedReader answers = send(pair, "{\"op\":\"pair\",\"name\":\"laptop\"}");
            Matcher code = CODE.matcher(line(answers));
            assertThat(code.matches()).isTrue();
            // The link that pair prints comes from the service, which alone knows its port.
            assertThat(code.group(2)).isEqualTo(server.address().toString());
            HttpResponse<String> paired = HTTP.send(HttpRequest.newBuilder(server.address().resolve("/pair"))
                    .header("Origin", "http://" + server.address().getAuthority())
                    .header("User-Agent", "crawdad-test")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code.group(1) + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(paired.statusCode()).isEqualTo(204);

            // The waiting pair learns which device used the code: the socket shares the server's codes.
            assertThat(line(answers))
                    .isEqualTo("{\"paired\":{\"name\":\"laptop\",\"address\":\"127.0.0.1\",\"user_agent\":\"crawdad-test\"}}");
        }

        // The socket lists the same registry the server paired into.
        try (SocketChannel devices = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            assertThat(line(send(devices, "{\"op\":\"devices\"}"))).startsWith("{\"devices\":[{\"name\":\"laptop\",");
        }
    }

    @Test
    void aSecondServiceOfTheSameUserRefusesToStartAndLeavesTheFirstOneAlone() throws Exception {
        server = run("--port", "0");

        assertThatThrownBy(() -> run("--port", "0"))
                .isInstanceOf(AlreadyRunningException.class)
                .hasMessage("Crawdad is already running");

        assertThat(devices()).isEqualTo("{\"devices\":[]}");
    }

    @Test
    void theLockIsTakenBeforeTheRegistryAndTheOwnRootAreOpened() throws Exception {
        try (InstanceLock other = InstanceLock.acquire(data().runtime())) {
            assertThatThrownBy(() -> run("--host", "10.1.2.3", "--port", "0"))
                    .isInstanceOf(AlreadyRunningException.class);
        }

        assertThat(data().data()).doesNotExist();
    }

    @Test
    void stoppingDeletesTheSocketAndReleasesTheLockSoTheServiceCanStartAgain() throws Exception {
        server = run("--port", "0");
        Path socket = data().runtime().resolve(ControlSocket.FILE_NAME);

        server.stop();
        server = null;

        assertThat(socket).doesNotExist();
        server = run("--port", "0");
        assertThat(devices()).isEqualTo("{\"devices\":[]}");
    }

    @Test
    void aControlSocketThatCannotStartStopsTheServerAndFailsTheStart() throws Exception {
        // A directory in the way of the socket cannot be replaced.
        Files.createDirectories(data().runtime().resolve(ControlSocket.FILE_NAME).resolve("blocker"));
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }

        assertThatThrownBy(() -> run("--port", String.valueOf(port))).isInstanceOf(DirectoryNotEmptyException.class);

        // The port is free again and the lock released.
        try (ServerSocket again = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertThat(again.isBound()).isTrue();
        }
        try (InstanceLock lock = InstanceLock.acquire(data().runtime())) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    void withAHostTheFirstLineOfPairCarriesTheNetworkAddressToo() throws Exception {
        // The record takes 127.0.0.2, which the options refuse, so that the test needs no private interface.
        server = CrawdadService.run(new ServiceConfig(NETWORK_HOST, 0), Optional.of(certificate()), data(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        try (SocketChannel pair = SocketChannel.open(
                UnixDomainSocketAddress.of(data().runtime().resolve(ControlSocket.FILE_NAME)))) {
            assertThat(line(send(pair, "{\"op\":\"pair\"}"))).endsWith(",\"urls\":{\"loopback\":\""
                    + server.address() + "\",\"network\":\"" + server.networkAddress().orElseThrow() + "\"}}");
        }
    }

    @Test
    void aServiceThatCannotStartSaysWhyInOneLine() throws Exception {
        // Each reason is the owner's to fix: the message is enough, a stack trace would bury it.
        assertThat(startError("--bogus", "1")).isEqualTo("Unknown argument: --bogus" + System.lineSeparator());
        assertThat(startError("--host", "10.1.2.3", "--port", "0", "--tls-cert", temp.resolve("none.crt").toString(),
                "--tls-key", temp.resolve("none.key").toString()))
                .startsWith(temp.resolve("none.crt") + ": ")
                .doesNotContain("\tat ")
                .hasLineCount(1);

        server = run("--port", "0");
        assertThat(startError("--port", "0")).isEqualTo("Crawdad is already running" + System.lineSeparator());
    }

    @Test
    void aPortInUseIsSaidInOneLineAndLeavesNothingRunning() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String error = startError("--port", String.valueOf(taken.getLocalPort()));

            assertThat(error).startsWith("Cannot listen on 127.0.0.1:" + taken.getLocalPort() + ": ")
                    .doesNotContain("\tat ")
                    .hasLineCount(1);
        }
        // The lock is released, so the service starts once the port is free.
        server = run("--port", "0");
    }

    @Test
    void aHostThatIsNotAnAddressOfThisMachineIsSaidInOneLineAndFreesLoopback() throws Exception {
        String foreign = "10.255.254.253";
        assumeTrue(NetworkInterface.getByInetAddress(InetAddress.getByName(foreign)) == null,
                "this machine has the address the test needs to be foreign");
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }

        String error = startError("--host", foreign, "--port", String.valueOf(port));

        assertThat(error).startsWith("Cannot listen on " + foreign + ":" + port + ": ")
                .doesNotContain("\tat ")
                .hasLineCount(1);
        // Loopback, bound before the network address failed, is free again.
        try (ServerSocket again = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertThat(again.isBound()).isTrue();
        }
    }

    @Test
    void theStartHintNamesThisJar() {
        assertThat(CrawdadService.startCommand()).startsWith("java -jar /");
    }

    /** A self-signed certificate for {@link #NETWORK_HOST}; nothing in the test connects over TLS. */
    private static NetworkCertificate certificate() throws Exception {
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
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        return new NetworkCertificate(pair.getPrivate(), List.of(certificate), NETWORK_HOST, Optional.empty());
    }

    private String startError(String... args) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Optional<CrawdadServer> started = CrawdadService.start(args, data(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(started).isEmpty();
        return err.toString(StandardCharsets.UTF_8);
    }

    private CrawdadServer run(String... args) throws Exception {
        return CrawdadService.run(args, data(), new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
    }

    /** A data directory of the test, so that no test touches the owner's real one. */
    private DataDirectory data() {
        return new DataDirectory(temp.resolve("data"), temp.resolve("run"));
    }

    private String devices() throws Exception {
        try (SocketChannel channel = SocketChannel.open(
                UnixDomainSocketAddress.of(data().runtime().resolve(ControlSocket.FILE_NAME)))) {
            return line(send(channel, "{\"op\":\"devices\"}"));
        }
    }

    private static BufferedReader send(SocketChannel channel, String request) throws IOException {
        channel.write(ByteBuffer.wrap((request + "\n").getBytes(StandardCharsets.UTF_8)));
        return new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    }

    /** The next answer line within 5 s. */
    private static String line(BufferedReader answers) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return answers.readLine();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).get(5, TimeUnit.SECONDS);
    }
}
