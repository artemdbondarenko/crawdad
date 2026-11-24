package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectoryFixture;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock.AlreadyRunningException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    @Tag("PAIR-6")
    @Tag("CMD-2")
    @Tag("PAIRCMD-1")
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
    @Tag("PAIR-13")
    void aCodeIsKeptInMemoryOnlyAndARestartEndsIt() throws Exception {
        server = run("--port", "0");
        String code;
        try (SocketChannel pair = SocketChannel.open(
                UnixDomainSocketAddress.of(data().runtime().resolve(ControlSocket.FILE_NAME)))) {
            Matcher issued = CODE.matcher(line(send(pair, "{\"op\":\"pair\"}")));
            assertThat(issued.matches()).isTrue();
            code = issued.group(1);

            // No file of the service holds the code while it is valid.
            try (Stream<Path> files = Files.walk(temp)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).as(file.toString())
                            .doesNotContain(code);
                }
            }
        }

        server.stop();
        server = run("--port", "0");

        HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(server.address().resolve("/pair"))
                .header("Origin", "http://" + server.address().getAuthority())
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).isEqualTo("{\"error\":\"invalid_code\"}");
    }

    @Test
    @Tag("CMD-7")
    void aSecondServiceOfTheSameUserRefusesToStartAndLeavesTheFirstOneAlone() throws Exception {
        server = run("--port", "0");

        assertThatThrownBy(() -> run("--port", "0"))
                .isInstanceOf(AlreadyRunningException.class)
                .hasMessage("Crawdad is already running");

        assertThat(devices()).isEqualTo("{\"devices\":[]}");
    }

    @Test
    @Tag("CMD-7")
    void theLockIsTakenBeforeTheRegistryAndTheOwnRootAreOpened() throws Exception {
        try (InstanceLock other = InstanceLock.acquire(data().runtime())) {
            assertThatThrownBy(() -> run("--host", "10.1.2.3", "--port", "0"))
                    .isInstanceOf(AlreadyRunningException.class);
        }

        assertThat(data().data()).doesNotExist();
    }

    @Test
    @Tag("CMD-8")
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
    @Tag("CMD-6")
    void aControlSocketThatCannotBeOpenedFailsTheStartBeforeAnyAddressIsListenedOn() throws Exception {
        // A directory in the way of the socket cannot be replaced.
        Files.createDirectories(data().runtime().resolve(ControlSocket.FILE_NAME).resolve("blocker"));

        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // The port is taken too: had the server tried to listen first, the start would fail on the port.
            assertThatThrownBy(() -> run("--port", String.valueOf(taken.getLocalPort())))
                    .isInstanceOf(ControlSocket.CannotOpenException.class)
                    .hasCauseInstanceOf(DirectoryNotEmptyException.class);
        }

        try (InstanceLock lock = InstanceLock.acquire(data().runtime())) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    @Tag("CMD-6")
    void aServerThatCannotListenClosesTheControlSocketAndReleasesTheLock() throws Exception {
        Path socket = data().runtime().resolve(ControlSocket.FILE_NAME);
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> run("--port", String.valueOf(taken.getLocalPort())))
                    .isInstanceOf(CrawdadServer.CannotListenException.class);
        }

        assertThat(socket).doesNotExist();
        try (InstanceLock lock = InstanceLock.acquire(data().runtime())) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    @Tag("PAIRCMD-1")
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
    @Tag("START-1")
    @Tag("TLS-3")
    @Tag("CMD-7")
    void aServiceThatCannotStartSaysWhyInOneLine() throws Exception {
        // Each reason is the owner's to fix: the message is enough, a stack trace would bury it.
        Refusal files = refusal("--host", "10.1.2.3", "--port", "0", "--tls-cert",
                temp.resolve("none.crt").toString(), "--tls-key", temp.resolve("none.key").toString());
        assertThat(files.error()).startsWith(temp.resolve("none.crt") + ": ").doesNotContain("\tat ").hasLineCount(1);
        assertThat(files.exitCode()).isEqualTo(1);

        server = run("--port", "0");
        Refusal running = refusal("--port", "0");
        assertThat(running.error()).isEqualTo("Crawdad is already running" + System.lineSeparator());
        assertThat(running.exitCode()).isEqualTo(1);
        assertThat(running.output()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--prot 8461", "--port", "--port eighty", "--port 70000", "--port 8461 --port 8462",
            "--host --port 8461"})
    @Tag("START-1")
    @Tag("START-2")
    @Tag("START-3")
    void wrongOptionsExitWithCodeTwoAndCreateNothing(String args) throws Exception {
        Refusal refusal = refusal(args.split(" "));

        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.error()).startsWith("--").doesNotContain("\tat ").hasLineCount(1);
        assertThat(refusal.output()).isEmpty();
        assertThat(temp).isEmptyDirectory();
    }

    @Test
    @Tag("START-1")
    @Tag("START-5")
    void aPortInUseIsSaidInOneLineAndLeavesNothingRunning() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Refusal refusal = refusal("--port", String.valueOf(taken.getLocalPort()));

            assertThat(refusal.error()).isEqualTo("Cannot listen on 127.0.0.1:" + taken.getLocalPort()
                    + ": the port is in use; stop the program that uses it or start with --port <number>"
                    + System.lineSeparator());
            assertThat(refusal.exitCode()).isEqualTo(1);
            assertThat(refusal.output()).isEmpty();
        }
        // The lock is released, so the service starts once the port is free.
        server = run("--port", "0");
    }

    @Test
    @Tag("START-1")
    @Tag("START-4")
    void aHostThatIsNotAnAddressOfThisMachineIsSaidInOneLineAndFreesLoopback() throws Exception {
        String foreign = foreignAddress();
        int port = freePort();

        Refusal refusal = refusal("--host", foreign, "--port", String.valueOf(port));

        assertThat(refusal.error()).startsWith("Cannot listen on " + foreign + ":" + port + ": " + foreign
                + " is not an address of this machine").doesNotContain("\tat ").hasLineCount(1);
        assertThat(refusal.exitCode()).isEqualTo(1);
        // Loopback, bound before the network address failed, is free again.
        try (ServerSocket again = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertThat(again.isBound()).isTrue();
        }
    }

    @Test
    @Tag("START-2")
    @Tag("TLS-7")
    void aRefusedStartTakesAwayTheRootAndTheDirectoriesItCreated() throws Exception {
        // The own root is created before the network address turns out not to be this machine's.
        Refusal refusal = refusal("--host", foreignAddress(), "--port", String.valueOf(freePort()));

        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(temp).isEmptyDirectory();
    }

    @Test
    @Tag("START-2")
    void aRefusedStartLeavesTheFilesThatWereThereAsTheyWere() throws Exception {
        Path registry = data().data().resolve("devices.json");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, "{\"version\":1,\"devices\":[]}");
        Path ownersNote = data().data().resolve("note.txt");
        Files.writeString(ownersNote, "keep me");
        Files.createDirectories(data().runtime());
        Path lock = data().runtime().resolve(InstanceLock.FILE_NAME);
        Files.writeString(lock, "");

        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThat(refusal("--port", String.valueOf(taken.getLocalPort())).exitCode()).isEqualTo(1);
        }

        assertThat(registry).hasContent("{\"version\":1,\"devices\":[]}");
        assertThat(ownersNote).hasContent("keep me");
        assertThat(lock).exists();
        try (Stream<Path> left = Files.walk(temp)) {
            assertThat(left)
                    .containsExactlyInAnyOrder(temp, data().data(), registry, ownersNote, data().runtime(), lock);
        }
    }

    @Test
    @Tag("START-2")
    void aRefusedStartTakesAwayWhatItCreatedInADataDirectoryThatIsALink() throws Exception {
        Path real = Files.createDirectory(temp.resolve("real"));
        Path link = Files.createSymbolicLink(temp.resolve("data"), real);

        Refusal refusal = refusal(new DataDirectory(link, temp.resolve("run")), "--host", foreignAddress(), "--port",
                String.valueOf(freePort()));

        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(real).isEmptyDirectory();
        assertThat(temp.resolve("run")).doesNotExist();
    }

    @Test
    @Tag("START-1")
    @Tag("START-5")
    void aPortHeldBySocketThatDoesNotListenIsInUseToo() throws Exception {
        try (Socket held = new Socket()) {
            held.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Refusal refusal = refusal("--port", String.valueOf(held.getLocalPort()));

            assertThat(refusal.error()).contains("the port is in use").hasLineCount(1);
            assertThat(refusal.exitCode()).isEqualTo(1);
        }
    }

    @Test
    @Tag("START-1")
    @Tag("START-2")
    @Tag("START-6")
    void aControlSocketPathThatIsTooLongIsSaidWithThePath() throws Exception {
        Path runtime = temp.resolve("r".repeat(Math.max(1, 120 - temp.toString().length())));
        DataDirectory data = new DataDirectory(temp.resolve("data"), runtime);
        Path socket = runtime.resolve(ControlSocket.FILE_NAME);

        Refusal refusal = refusal(data, "--port", "0");

        assertThat(refusal.error()).contains("too long").contains(socket.toString())
                .contains("XDG_RUNTIME_DIR").hasLineCount(1);
        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(temp).isEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"version\":2,\"devices\":[]}", "not json", "{\"version\":1}", "{\"devices\":[]}"})
    @Tag("START-1")
    @Tag("START-7")
    void aDeviceRegistryThatCannotBeUsedIsLeftAsItIs(String content) throws Exception {
        Path registry = data().data().resolve("devices.json");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, content);

        Refusal refusal = refusal("--port", "0");

        assertThat(refusal.error()).startsWith("The device registry " + registry + " ").hasLineCount(1);
        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(registry).hasContent(content);
    }

    @Test
    @Tag("START-8")
    void anIllegalArgumentThatIsNotAnOptionIsAFailureForTheLog() throws Exception {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Refusal refusal = withLog(logged, () -> refusal(() -> {
            throw new IllegalArgumentException("a bug");
        }, "--port", "0"));

        assertThat(refusal.error()).startsWith("Crawdad could not start: a bug;");
        assertThat(logged).singleElement().satisfies(record ->
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.SEVERE));
    }

    @Test
    @Tag("START-8")
    void aFailureWithoutAMessageIsToldByItsKind() throws Exception {
        Refusal refusal = withLog(new CopyOnWriteArrayList<>(), () -> refusal(() -> {
            throw new NullPointerException();
        }, "--port", "0"));

        assertThat(refusal.error()).isEqualTo("Crawdad could not start: java.lang.NullPointerException; the service "
                + "log has its causes" + System.lineSeparator());
    }

    @Test
    @Tag("START-1")
    @Tag("FILE-1")
    void noPlaceForTheDataIsSaidInOneLine() throws Exception {
        Refusal refusal = refusal(DataDirectoryFixture::withoutHome, "--port", "0");

        assertThat(refusal.error()).contains("XDG_DATA_HOME").contains("HOME").hasLineCount(1);
        assertThat(refusal.exitCode()).isEqualTo(1);
    }

    @Test
    @Tag("START-1")
    @Tag("START-8")
    @Tag("LOG-3")
    void anUnexpectedFailureIsOneLineThatPointsAtTheLogWhereItsCausesAre() throws Exception {
        IllegalStateException failure = new IllegalStateException("broken", new IOException("disk"));
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler collect = collectInto(logged);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(CrawdadService.class.getName());
        log.addHandler(collect);
        Refusal refusal;
        try {
            refusal = refusal(() -> {
                throw failure;
            }, "--port", "0");
        } finally {
            log.removeHandler(collect);
        }

        assertThat(refusal.error()).isEqualTo("Crawdad could not start: broken; the service log has its causes"
                + System.lineSeparator());
        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(logged).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }

    @Test
    @Tag("CMD-3")
    void theStartHintNamesThisJarAndLeavesTheAdapterToTheOwner() {
        assertThat(CrawdadService.startCommand()).startsWith("java -jar /").endsWith(" --agent <name>")
                .doesNotContain("--agent fake");
    }

    @Test
    @Tag("ADAPTER-1")
    @Tag("START-1")
    @Tag("START-3")
    void aStartWithoutAgentExitsWithCodeTwoAndCreatesNothing() {
        Refusal refusal = refusalOf(this::data, "--port", "0");

        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.error()).isEqualTo("--agent is missing: name the agent adapter to talk to, such as "
                + "--agent fake" + System.lineSeparator());
        assertThat(refusal.output()).isEmpty();
        assertThat(temp).isEmptyDirectory();
    }

    @Test
    @Tag("ADAPTER-5")
    @Tag("START-1")
    @Tag("START-2")
    void anAdapterThatIsNotThereIsSaidInOneLineAndLeavesNothingBehind() {
        Refusal refusal = refusalOf(this::data, "--agent", "ecko", "--port", "0");

        assertThat(refusal.exitCode()).isEqualTo(1);
        assertThat(refusal.error()).isEqualTo("No agent adapter is named ecko; the adapters are: fake (built in)"
                + System.lineSeparator());
        assertThat(refusal.output()).isEmpty();
        assertThat(temp).isEmptyDirectory();
    }

    @Test
    @Tag("ADAPTER-10")
    @Tag("LOG-2")
    void theChosenAdapterIsWrittenToTheLog() throws Exception {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler collect = collectInto(logged);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(CrawdadService.class.getName());
        log.addHandler(collect);
        try {
            server = run("--port", "0");
        } finally {
            log.removeHandler(collect);
        }

        assertThat(logged).anySatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.INFO);
            assertThat(record.getMessage()).isEqualTo(
                    "Agent adapter: fake, built in, does not execute configuration found in the repository");
        });
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

    /** What a refused start printed and the code it exits with. */
    private record Refusal(String output, String error, int exitCode) {}

    private Refusal refusal(String... args) {
        return refusal(data(), args);
    }

    private Refusal refusal(DataDirectory data, String... args) {
        return refusal(() -> data, args);
    }

    /** A start with {@code --agent fake} in front of {@code args}, which every start needs ({@code ADAPTER-1}). */
    private Refusal refusal(Supplier<DataDirectory> data, String... args) {
        return refusalOf(data, withAgent(args));
    }

    /** A start with exactly {@code args}. */
    private Refusal refusalOf(Supplier<DataDirectory> data, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CrawdadService.Start start = CrawdadService.start(args, data,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(start.server()).isEmpty();
        return new Refusal(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
                start.exitCode());
    }

    /** An address of a private range that no interface of this machine has. */
    private static String foreignAddress() throws IOException {
        String foreign = "10.255.254.253";
        assumeTrue(NetworkInterface.getByInetAddress(InetAddress.getByName(foreign)) == null,
                "this machine has the address the test needs to be foreign");
        return foreign;
    }

    private static int freePort() throws IOException {
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return free.getLocalPort();
        }
    }

    /** Runs {@code start} with the entries of the service collected into {@code logged}. */
    private static Refusal withLog(List<LogRecord> logged, Supplier<Refusal> start) {
        Handler collect = collectInto(logged);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(CrawdadService.class.getName());
        log.addHandler(collect);
        try {
            return start.get();
        } finally {
            log.removeHandler(collect);
        }
    }

    private static Handler collectInto(List<LogRecord> logged) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private CrawdadServer run(String... args) throws Exception {
        return CrawdadService.run(withAgent(args), data(), new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
    }

    static String[] withAgent(String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "--agent";
        all[1] = "fake";
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
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
