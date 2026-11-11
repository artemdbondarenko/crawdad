package io.github.artemdbondarenko.crawdad.service.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.control.ControlSocket.ServiceUrls;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Attempt;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodesFixture;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code pair}, {@code devices}, {@code revoke} and {@code tls export-ca} commands against a real control socket. */
class CliTest {

    private static final Instant START = Instant.parse("2026-11-08T10:00:00Z");
    private static final String START_COMMAND = "java -jar /opt/crawdad/crawdad-service.jar";
    private static final String NOT_RUNNING = "Crawdad is not running; start it with: " + START_COMMAND;
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8460/");
    private static final URI NETWORK = URI.create("https://100.64.0.1:8460/");
    private static final String PHONE = "100.64.0.7";
    private static final String STRANGER = "100.64.0.9";
    private static final Pattern CODE = Pattern.compile("Pairing code: ([0-9]{6}) ");
    private static final Pattern QR_LINE = Pattern.compile("[ \\u2580\\u2584\\u2588]+");
    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String RIGHT_TO_LEFT_OVERRIDE = String.valueOf((char) 0x202E);

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(START);
    private final PairingCodes codes = PairingCodesFixture.onClock(clock);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private DataDirectory data;
    private DeviceRegistry devices;
    private ControlSocket socket;

    @BeforeEach
    void startSocket() throws IOException {
        data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        devices = DeviceRegistry.open(data, clock);
        socket = ControlSocket.start(data.runtime(), codes, devices, connections,
                new ServiceUrls(LOOPBACK, Optional.empty()));
    }

    @AfterEach
    void stopSocket() {
        socket.close();
    }

    @Test
    @Tag("CMD-1")
    void onlyTheFirstWordPicksACommandAndEverythingElseStartsTheService() {
        for (String command : List.of("pair", "devices", "revoke", "tls")) {
            assertThat(Cli.handles(new String[] {command})).as(command).isTrue();
        }
        assertThat(Cli.handles(new String[0])).isFalse();
        assertThat(Cli.handles(new String[] {"--host", "10.1.2.3"})).isFalse();
        assertThat(Cli.handles(new String[] {"--port", "pair"})).isFalse();
    }

    @Test
    @Tag("CMD-2")
    @Tag("CMD-3")
    void withoutTheServiceACommandSaysHowToStartItAndFails() {
        DataDirectory nowhere = new DataDirectory(temp.resolve("other-data"), temp.resolve("other-run"));

        assertThat(Cli.run(new String[] {"pair"}, nowhere, START_COMMAND, out, err)).isEqualTo(1);

        assertThat(err()).isEqualTo(NOT_RUNNING + System.lineSeparator());
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("CMD-3")
    void aSocketLeftByAServiceThatIsGoneCountsAsNotRunning() throws Exception {
        socket.close();
        // A crashed service leaves its bound socket file behind; nothing accepts on it.
        Path left = data.runtime().resolve(ControlSocket.FILE_NAME);
        try (ServerSocketChannel gone = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            gone.bind(UnixDomainSocketAddress.of(left));
        }
        assertThat(left).exists();

        assertThat(run("devices")).isEqualTo(1);

        assertThat(err()).isEqualTo(NOT_RUNNING + System.lineSeparator());
    }

    @Test
    @Tag("CMD-3")
    void aSocketThisUserMayNotOpenCountsAsNotRunning() {
        try {
            setPermissions(data.runtime(), "---------");

            assertThat(run("devices")).isEqualTo(1);
        } finally {
            setPermissions(data.runtime(), "rwx------");
        }

        assertThat(err()).isEqualTo(NOT_RUNNING + System.lineSeparator());
    }

    @Test
    @Tag("PAIRCMD-1")
    @Tag("PAIRCMD-2")
    @Tag("PAIRCMD-3")
    void pairWithoutANetworkAddressPrintsTheLinkForThisMachineAndNoQrAndEndsWithTheDevice() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair", "--name", "phone");
        String code = awaitCode();

        // The page exchanges the code; the waiting command learns which device used it.
        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code, PHONE);
        assertThat(accepted.requestedName()).contains("phone");
        accepted.paired("phone", PHONE, "Mozilla/5.0 (Android)");

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(out()).isEqualTo(lines(
                "Pairing code: " + code + " (valid for 5 minutes)",
                "On this machine, open: http://127.0.0.1:8460/#pair=" + code,
                "To pair a phone, start Crawdad with --host <private address> and run pair again.",
                "Waiting for the code to be used; Ctrl+C stops waiting and the code stays valid.",
                "Paired phone from 100.64.0.7, browser: Mozilla/5.0 (Android)"));
        assertThat(err()).isEmpty();
    }

    @Test
    @Tag("PAIRCMD-1")
    @Tag("PAIRCMD-3")
    void withANetworkAddressPairPrintsAQrCodeOfTheNetworkLink() throws Exception {
        socket.close();
        socket = ControlSocket.start(data.runtime(), codes, devices, connections,
                new ServiceUrls(LOOPBACK, Optional.of(NETWORK)));

        CompletableFuture<Integer> exit = runInBackground("pair");
        String code = awaitCode();
        awaitOut("Waiting for the code");

        String qr = out().lines().filter(line -> QR_LINE.matcher(line).matches()).collect(Collectors.joining("\n"));
        assertThat(QrDecoder.decode(qr)).isEqualTo("https://100.64.0.1:8460/#pair=" + code);
        assertThat(out())
                .contains("On this machine, open: http://127.0.0.1:8460/#pair=" + code)
                .contains("On another device, scan the code or open: https://100.64.0.1:8460/#pair=" + code)
                .doesNotContain("--host");

        // A newer pair replaces the code; the waiting command fails.
        codes.issue(null);
        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("The code was replaced by a newer pair command."));
    }

    @Test
    @Tag("PAIRCMD-3")
    void aCodeNobodyEntersInTimeEndsTheCommandAsExpired() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");
        String code = awaitCode();

        clock.advance(PairingCodes.TERM);
        codes.attempt(code, PHONE);

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("The code expired; run pair again for a new one."));
    }

    @Test
    @Tag("PAIRCMD-3")
    @Tag("PAIRCMD-5")
    void aBurnedCodeEndsTheCommandWithTheSourcesAndIsNotRepeatedByTheNextPair() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");
        String wrong = awaitCode().equals("000000") ? "000001" : "000000";
        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            codes.attempt(wrong, i < 3 ? STRANGER : PHONE);
        }
        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("The code burned after wrong attempts from: 100.64.0.9, 100.64.0.7"));

        // This command already told the owner, so the next one starts without the warning.
        outBytes.reset();
        CompletableFuture<Integer> next = runInBackground("pair");
        awaitCode();
        assertThat(out()).startsWith("Pairing code: ");
        codes.issue(null);
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    @Tag("PAIRCMD-5")
    void aCodeThatBurnedWhileNobodyWaitedIsReportedByTheNextPair() throws Exception {
        String burned = codes.issue(null).code();
        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            codes.attempt(burned.equals("000000") ? "000001" : "000000", STRANGER);
        }

        CompletableFuture<Integer> exit = runInBackground("pair");
        awaitCode();

        assertThat(out()).startsWith(lines("The previous code burned after wrong attempts from: 100.64.0.9")
                + "Pairing code: ");
        codes.issue(null);
        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    @Tag("PAIRCMD-3")
    void aDeviceTheServiceCouldNotSaveEndsTheCommandAsFailed() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");

        ((Attempt.Accepted) codes.attempt(awaitCode(), PHONE)).failed();

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("Pairing failed: the service could not save the device; see the service log"));
    }

    @ParameterizedTest(name = "{0}")
    @Tag("CMD-4")
    @ValueSource(strings = {"pair", "devices", "revoke phone"})
    void aServiceThatClosesTheConnectionWithoutAnAnswerIsReportedWithThePointerToTheLog(String command)
            throws Exception {
        socket.close();
        Path path = data.runtime().resolve(ControlSocket.FILE_NAME);
        try (ServerSocketChannel hangsUp = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            hangsUp.bind(UnixDomainSocketAddress.of(path));
            // Accepts the command and closes, as the service does with a peer it refuses.
            Thread.ofVirtual().start(() -> {
                try {
                    hangsUp.accept().close();
                } catch (IOException e) {
                    // The test ends either way.
                }
            });

            assertThat(run(command.split(" "))).isEqualTo(1);
        }

        assertThat(err()).isEqualTo(lines("Crawdad closed the connection without an answer; see the service log."));
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("PAIRCMD-3")
    void aServiceThatStopsDuringTheWaitEndsTheCommand() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");
        awaitCode();

        socket.close();

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("Crawdad stopped before the code was used."));
    }

    @Test
    @Tag("PAIRCMD-8")
    void theDeviceThatPairedIsPrintedWithoutTerminalControlCharacters() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");

        ((Attempt.Accepted) codes.attempt(awaitCode(), PHONE))
                .paired("Firefox" + ESC + "[2J", PHONE, "Mozilla" + RIGHT_TO_LEFT_OVERRIDE + "/5.0");

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(out())
                .contains("Paired Firefox\\x1B[2J from 100.64.0.7, browser: Mozilla\\u202E/5.0")
                .doesNotContain(ESC)
                .doesNotContain(RIGHT_TO_LEFT_OVERRIDE);
    }

    @Test
    @Tag("PAIRCMD-3")
    void aDeviceThatPairedWithoutAUserAgentHasAnUnknownBrowser() throws Exception {
        CompletableFuture<Integer> exit = runInBackground("pair");

        ((Attempt.Accepted) codes.attempt(awaitCode(), PHONE)).paired("phone", PHONE, null);

        assertThat(exit.get(5, TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(out()).endsWith(lines("Paired phone from 100.64.0.7, browser: unknown"));
    }

    @Test
    @Tag("PAIRCMD-7")
    void anInvalidNameIsRefusedWithoutACodeSayingWhatANameMayBe() {
        assertThat(run("pair", "--name", " ")).isEqualTo(1);

        assertThat(err()).isEqualTo(lines("The device name must be 1 to 64 characters, without control characters,"
                + " format characters such as direction marks, or line and paragraph separators."));
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("DEV-1")
    @Tag("DEV-3")
    @Tag("DEV-4")
    @Tag("DEV-5")
    void devicesPrintsATableOfTheActiveOnesAndWithAllTheRevokedToo() throws Exception {
        devices.add("phone", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(1));
        String laptop = devices.add("laptop", Listener.LOOPBACK).device().id();
        clock.advance(Duration.ofMinutes(1));
        devices.recordLogin(laptop, "127.0.0.1", "Mozilla/5.0 " + ESC + "[31m");
        clock.advance(Duration.ofMinutes(1));
        devices.revoke("phone", connections);

        assertThat(run("devices")).isEqualTo(0);
        assertThat(out()).isEqualTo(lines(
                "NAME    PAIRED                LAST SEEN             ADDRESS    BROWSER               LISTENER",
                "laptop  2026-11-08T10:01:00Z  2026-11-08T10:02:00Z  127.0.0.1  Mozilla/5.0 \\x1B[31m  loopback"));

        outBytes.reset();
        assertThat(run("devices", "--all")).isEqualTo(0);
        assertThat(out()).isEqualTo(lines(
                "NAME    PAIRED                LAST SEEN             ADDRESS    BROWSER               LISTENER"
                        + "  REVOKED",
                "phone   2026-11-08T10:00:00Z  never                 -          -                     network"
                        + "   2026-11-08T10:03:00Z",
                "laptop  2026-11-08T10:01:00Z  2026-11-08T10:02:00Z  127.0.0.1  Mozilla/5.0 \\x1B[31m  loopback"
                        + "  -"));
        assertThat(err()).isEmpty();
    }

    @Test
    @Tag("DEV-1")
    void devicesWithoutAnyDeviceSaysSo() {
        assertThat(run("devices", "--all")).isEqualTo(0);

        assertThat(out()).isEqualTo(lines("No paired devices."));
    }

    @Test
    @Tag("DEV-6")
    void revokeOfAnActiveDeviceSucceeds() throws Exception {
        devices.add("phone", Listener.LOOPBACK);

        assertThat(run("revoke", "phone")).isEqualTo(0);

        assertThat(out()).isEqualTo(lines("Revoked phone; its open connections are closed."));
        assertThat(devices.devices(false)).isEmpty();
    }

    @Test
    @Tag("DEV-7")
    void revokeOfAnUnknownNameFailsWithTheActiveDevices() throws Exception {
        assertThat(run("revoke", "watch")).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("No active device named watch. There are no active devices."));

        devices.add("phone", Listener.LOOPBACK);
        devices.add("laptop", Listener.LOOPBACK);
        errBytes.reset();
        assertThat(run("revoke", "watch" + ESC)).isEqualTo(1);
        assertThat(err()).isEqualTo(lines("No active device named watch\\x1B. Active devices: phone, laptop"));
    }

    @Test
    @Tag("DEV-7")
    void revokeOfARevokedDeviceFailsWithTheDate() throws Exception {
        devices.add("phone", Listener.LOOPBACK);
        clock.advance(Duration.ofHours(1));
        devices.revoke("phone", connections);

        assertThat(run("revoke", "phone")).isEqualTo(1);

        assertThat(err()).isEqualTo(lines("phone was already revoked at 2026-11-08T11:00:00Z."));
    }

    @Test
    @Tag("DEV-8")
    void aRevocationTheServiceCouldNotSaveFails() throws Exception {
        devices.add("phone", Listener.LOOPBACK);
        // The registry writes next to devices.json; without its directory every write fails.
        Files.move(data.data(), temp.resolve("moved"));

        assertThat(run("revoke", "phone")).isEqualTo(1);

        assertThat(err()).isEqualTo(lines("Crawdad could not save the revocation; see the service log."));
    }

    @Test
    void anAnswerOutsideTheContractFails() throws Exception {
        DataDirectory fake = new DataDirectory(temp.resolve("fake-data"), temp.resolve("fake-run"));
        Files.createDirectories(fake.runtime());
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(fake.runtime().resolve(ControlSocket.FILE_NAME)));
            CompletableFuture<Void> answered = CompletableFuture.runAsync(() -> answerOnce(server,
                    "{\"error\":\"bad_request\"}\n"));

            assertThat(Cli.run(new String[] {"devices"}, fake, START_COMMAND, out, err)).isEqualTo(1);
            answered.get(5, TimeUnit.SECONDS);
        }

        assertThat(err()).isEqualTo(lines("Unexpected answer from Crawdad: {\"error\":\"bad_request\"}"));
    }

    @Test
    @Tag("CMD-1")
    void wrongArgumentsPrintTheUsageAndExitWithTwo() {
        List<List<String>> wrong = List.of(
                List.of("pair", "--bogus"),
                List.of("pair", "--name"),
                List.of("devices", "all"),
                List.of("revoke"),
                List.of("revoke", "phone", "laptop"),
                List.of("tls"),
                List.of("tls", "import-ca"));

        for (List<String> args : wrong) {
            errBytes.reset();
            assertThat(run(args.toArray(String[]::new))).as(args.toString()).isEqualTo(2);
            assertThat(err()).as(args.toString()).isEqualTo(lines(
                    "Usage: pair [--name <name>] | devices [--all] | revoke <name> | tls export-ca"));
        }
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("CA-5")
    void exportCaWithoutARootTellsToStartTheServiceWithHostAndNoCertificateFiles() {
        socket.close();

        assertThat(run("tls", "export-ca")).isEqualTo(1);

        assertThat(err()).isEqualTo(lines("No root certificate yet: start Crawdad with --host <private address>"
                + " and without --tls-cert and --tls-key to create it."));
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("CA-1")
    @Tag("CA-2")
    @Tag("CA-4")
    void exportCaPrintsTheRootAsPemAndItsFingerprintWithoutTheService() throws Exception {
        String fingerprint = LocalCa.forThisMachine(data).issue("10.1.2.3").rootFingerprint();
        socket.close();

        assertThat(run("tls", "export-ca")).isEqualTo(0);

        assertThat(out()).isEqualTo(Files.readString(
                data.data().resolve(LocalCa.DIRECTORY).resolve(LocalCa.CERTIFICATE_FILE)));
        assertThat(out()).startsWith("-----BEGIN CERTIFICATE-----\n").doesNotContain("PRIVATE KEY");
        assertThat(err()).isEqualTo(lines("SHA-256 fingerprint: " + fingerprint));
    }

    @Test
    @Tag("CA-3")
    void exportCaNeverReadsThePrivateKeyOfTheRoot() throws Exception {
        LocalCa.forThisMachine(data).issue("10.1.2.3");
        socket.close();
        Path key = data.data().resolve(LocalCa.DIRECTORY).resolve(LocalCa.KEY_FILE);
        setPermissions(key, "---------");
        try {
            assertThat(run("tls", "export-ca")).isEqualTo(0);
        } finally {
            setPermissions(key, "rw-------");
        }

        assertThat(out()).startsWith("-----BEGIN CERTIFICATE-----\n").doesNotContain("PRIVATE KEY");
    }

    @Test
    @Tag("CA-4")
    void exportCaPrintsAnExpiredRootAsItIs() throws Exception {
        Path root = Files.createDirectories(data.data().resolve(LocalCa.DIRECTORY)).resolve(LocalCa.CERTIFICATE_FILE);
        Files.writeString(root, ExpiredCertificate.pem());
        socket.close();

        assertThat(run("tls", "export-ca")).isEqualTo(0);

        assertThat(out()).isEqualTo(Files.readString(root));
        assertThat(err()).startsWith("SHA-256 fingerprint: ");
    }

    @Test
    @Tag("PAIRCMD-4")
    void ctrlCStopsTheWaitWithCode130AndTheCodeStaysValid() throws Exception {
        // A real process, so that the signal and the exit code are the JVM's own.
        Path home = temp.resolve("home");
        DataDirectory xdg = new DataDirectory(home.resolve("data/crawdad"), home.resolve("run/crawdad"));
        ControlSocket service = ControlSocket.start(xdg.runtime(), codes, devices, connections,
                new ServiceUrls(LOOPBACK, Optional.empty()));
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "io.github.artemdbondarenko.crawdad.service.CrawdadService", "pair");
        command.environment().put("HOME", home.toString());
        command.environment().put("XDG_DATA_HOME", home.resolve("data").toString());
        command.environment().put("XDG_RUNTIME_DIR", home.resolve("run").toString());
        command.redirectErrorStream(true);
        Process pair = command.start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(pair.getInputStream(),
                    StandardCharsets.UTF_8));
            String code = null;
            for (String line = output.readLine(); line != null && !line.startsWith("Waiting"); line = output.readLine()) {
                Matcher issued = CODE.matcher(line);
                if (issued.find()) {
                    code = issued.group(1);
                }
            }
            assertThat(code).isNotNull();

            assertThat(new ProcessBuilder("kill", "-INT", String.valueOf(pair.pid())).start().waitFor()).isZero();

            assertThat(pair.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.exitValue()).isEqualTo(130);
            assertThat(codes.attempt(code, PHONE)).isInstanceOf(Attempt.Accepted.class);
        } finally {
            pair.destroyForcibly();
            service.close();
        }
    }

    @Test
    @Tag("CA-5")
    void exportCaOfADamagedRootFails() throws Exception {
        Path root = Files.createDirectories(data.data().resolve(LocalCa.DIRECTORY)).resolve(LocalCa.CERTIFICATE_FILE);
        Files.writeString(root, "not a certificate");

        assertThat(run("tls", "export-ca")).isEqualTo(1);

        assertThat(err()).startsWith(root + " is not a readable certificate");
        assertThat(out()).isEmpty();
    }

    @Test
    @Tag("FILE-2")
    void theDataDirectoryIsFoundWithoutTheWarningOfTheService() {
        Logger directoryLog = Logger.getLogger(DataDirectory.class.getName());
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
        directoryLog.addHandler(collect);
        try {
            // Stands for DataDirectory.fromEnvironment without XDG_RUNTIME_DIR, which warns on this logger.
            DataDirectory found = Cli.dataDirectory(() -> {
                System.getLogger(DataDirectory.class.getName()).log(Level.WARNING, "XDG_RUNTIME_DIR is not set");
                return data;
            });

            assertThat(found).isEqualTo(data);
            assertThat(records).isEmpty();
            // The service, in another process, warns as before; here the level is back once the lookup is done.
            System.getLogger(DataDirectory.class.getName()).log(Level.WARNING, "after");
            assertThat(records).hasSize(1);
        } finally {
            directoryLog.removeHandler(collect);
        }
    }

    private int run(String... args) {
        return Cli.run(args, data, START_COMMAND, out, err);
    }

    private CompletableFuture<Integer> runInBackground(String... args) {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> exit.complete(run(args)));
        return exit;
    }

    /** The code the running {@code pair} printed, within 5 s. */
    private String awaitCode() throws InterruptedException {
        Matcher matcher = awaitOut("Pairing code: ");
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private Matcher awaitOut(String text) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(5));
        while (!out().contains(text)) {
            assertThat(Instant.now()).as("\"%s\" in the output within 5 s", text).isBefore(end);
            Thread.sleep(10);
        }
        return CODE.matcher(out());
    }

    private static void answerOnce(ServerSocketChannel server, String answer) {
        try (SocketChannel channel = server.accept()) {
            new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
                    .readLine();
            channel.write(ByteBuffer.wrap(answer.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setPermissions(Path directory, String permissions) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(permissions));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static String lines(String... lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append(System.lineSeparator());
        }
        return text.toString();
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
