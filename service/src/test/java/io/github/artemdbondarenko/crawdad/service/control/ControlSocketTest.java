package io.github.artemdbondarenko.crawdad.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Attempt;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Issued;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodesFixture;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control socket over a real Unix socket: pairing with both answer lines, listing and revoking
 * devices, the limits of a request and who may connect.
 */
class ControlSocketTest {

    private static final Instant START = Instant.parse("2026-11-08T10:00:00Z");
    private static final String PHONE = "100.64.0.7";
    private static final String STRANGER = "100.64.0.9";
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8460/");
    private static final URI NETWORK = URI.create("https://100.64.0.1:8460/");
    private static final String URLS = "\"urls\":{\"loopback\":\"http://127.0.0.1:8460/\"}";
    private static final Pattern FIRST_LINE = Pattern.compile(
            "\\{\"code\":\"([0-9]{6})\",\"expires_in\":300," + Pattern.quote(URLS) + "}");

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(START);
    private final PairingCodes codes = PairingCodesFixture.onClock(clock);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final List<Client> clients = new ArrayList<>();
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
    private final Logger log = Logger.getLogger(ControlSocket.class.getName());

    private DataDirectory data;
    private DeviceRegistry devices;
    private ControlSocket socket;

    @BeforeEach
    void start() throws IOException {
        log.addHandler(collect);
        data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        devices = DeviceRegistry.open(data, clock);
        socket = ControlSocket.start(data.runtime(), codes, devices, connections, loopbackOnly());
    }

    @AfterEach
    void stop() throws IOException {
        log.removeHandler(collect);
        for (Client client : clients) {
            client.channel.close();
        }
        socket.close();
    }

    @Test
    @Tag("CMD-5")
    @Tag("FILE-2")
    @Tag("FILE-4")
    void theSocketIsOpenOnlyToItsOwnerInAPrivateDirectory() throws IOException {
        assertThat(socket.path()).isEqualTo(data.runtime().resolve("control.sock"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(data.runtime())))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.path())))
                .isEqualTo("rw-------");
    }

    @Test
    @Tag("PAIRCMD-1")
    @Tag("PAIRCMD-2")
    @Tag("PAIRCMD-6")
    void pairAnswersWithTheCodeAndThenWithTheDeviceThatUsedIt() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\",\"name\":\"phone\"}");
        String code = code(client.line());

        // The page exchanges the code; the device is added under the name given to pair.
        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code, PHONE);
        assertThat(accepted.requestedName()).contains("phone");
        accepted.paired("phone", PHONE, "Mozilla/5.0 (Android)");

        assertThat(client.line())
                .isEqualTo("{\"paired\":{\"name\":\"phone\",\"address\":\"100.64.0.7\",\"user_agent\":\"Mozilla/5.0 (Android)\"}}");
        assertThat(client.line()).as("the connection closes after the outcome").isNull();
    }

    @Test
    @Tag("PAIRCMD-6")
    void pairWithoutANameLeavesTheNameToTheBrowserAndReportsAMissingUserAgentAsNull() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\",\"name\":null}");
        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code(client.line()), PHONE);

        assertThat(accepted.requestedName()).isEmpty();
        accepted.paired("Firefox on Linux", PHONE, null);
        assertThat(client.line())
                .isEqualTo("{\"paired\":{\"name\":\"Firefox on Linux\",\"address\":\"100.64.0.7\",\"user_agent\":null}}");
    }

    @Test
    @Tag("PAIRCMD-3")
    void aNewerPairTellsTheWaitingOneItsCodeWasReplaced() throws Exception {
        Client first = connect();
        first.send("{\"op\":\"pair\"}");
        String replacedCode = code(first.line());

        Client second = connect();
        second.send("{\"op\":\"pair\"}");
        String code = code(second.line());

        assertThat(first.line()).isEqualTo("{\"replaced\":true}");
        assertThat(first.line()).isNull();
        // The newer code is the one that works.
        assertThat(codes.attempt(replacedCode, PHONE)).isInstanceOf(Attempt.Rejected.class);
        assertThat(codes.attempt(code, PHONE)).isInstanceOf(Attempt.Accepted.class);
    }

    @Test
    @Tag("PAIRCMD-3")
    void aDeviceThatCouldNotBeSavedEndsThePairingAsFailed() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\"}");
        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code(client.line()), PHONE);

        accepted.failed();

        assertThat(client.line()).isEqualTo("{\"failed\":true}");
    }

    @Test
    @Tag("PAIRCMD-3")
    void aCodeNobodyEntersInTimeEndsAsExpired() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\"}");
        String code = code(client.line());

        // Entering the code after its five minutes ends it.
        clock.advance(PairingCodes.TERM);
        assertThat(codes.attempt(code, PHONE)).isInstanceOf(Attempt.Rejected.class);

        assertThat(client.line()).isEqualTo("{\"expired\":true}");
    }

    @Test
    @Tag("PAIRCMD-3")
    void wrongAttemptsBurnTheCodeAndTheWaiterLearnsTheirSources() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\"}");
        String wrong = wrong(code(client.line()));

        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            codes.attempt(wrong, i < 3 ? STRANGER : PHONE);
        }

        assertThat(client.line()).isEqualTo("{\"burned\":{\"addresses\":[\"100.64.0.9\",\"100.64.0.7\"]}}");
    }

    @Test
    @Tag("PAIRCMD-4")
    void aClientThatLeavesStopsWaitingButTheCodeStaysValid() throws Exception {
        Client leaving = connect();
        leaving.send("{\"op\":\"pair\"}");
        String code = code(leaving.line());

        // Ctrl+C in crawdad pair closes its end of the socket.
        leaving.channel.close();
        awaitTrue(() -> socket.openConnections() == 0);

        Attempt attempt = codes.attempt(code, PHONE);
        assertThat(attempt).isInstanceOf(Attempt.Accepted.class);
        ((Attempt.Accepted) attempt).paired("phone", PHONE, "Mozilla/5.0");
    }

    @Test
    @Tag("PAIRCMD-5")
    void aCodeBurnedWhileNobodyWaitedIsReportedByTheNextPair() throws Exception {
        Client leaving = connect();
        leaving.send("{\"op\":\"pair\"}");
        String wrong = wrong(code(leaving.line()));
        leaving.channel.close();
        awaitTrue(() -> socket.openConnections() == 0);
        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            codes.attempt(wrong, STRANGER);
        }

        Client next = connect();
        next.send("{\"op\":\"pair\"}");

        assertThat(next.line()).matches(
                "\\{\"code\":\"[0-9]{6}\",\"expires_in\":300,\"burned\":\\{\"addresses\":\\[\"100.64.0.9\"]},"
                        + Pattern.quote(URLS) + "}");
    }

    @Test
    @Tag("PAIRCMD-5")
    void aBurnTheWaiterWasToldIsNotRepeatedByTheNextPair() throws Exception {
        Client waiting = connect();
        waiting.send("{\"op\":\"pair\"}");
        String wrong = wrong(code(waiting.line()));
        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            codes.attempt(wrong, STRANGER);
        }
        assertThat(waiting.line()).isEqualTo("{\"burned\":{\"addresses\":[\"100.64.0.9\"]}}");
        // The socket reports the delivery once the line is written, which ends the connection.
        assertThat(waiting.line()).isNull();
        awaitTrue(() -> socket.openConnections() == 0);

        Client next = connect();
        next.send("{\"op\":\"pair\"}");

        code(next.line());
    }

    @Test
    @Tag("PAIRCMD-1")
    void withANetworkAddressTheFirstLineCarriesBothUrls() throws Exception {
        socket.close();
        socket = ControlSocket.start(data.runtime(), codes, devices, connections,
                new ControlSocket.ServiceUrls(LOOPBACK, Optional.of(NETWORK)));
        Client client = connect();
        client.send("{\"op\":\"pair\"}");

        String urls = "{\"loopback\":\"http://127.0.0.1:8460/\",\"network\":\"https://100.64.0.1:8460/\"}";
        assertThat(client.line())
                .matches("\\{\"code\":\"[0-9]{6}\",\"expires_in\":300,\"urls\":" + Pattern.quote(urls) + "}");
    }

    @Test
    @Tag("PAIRCMD-7")
    void aNameMayHaveOneCharacter() throws Exception {
        Client client = connect();
        client.send("{\"op\":\"pair\",\"name\":\"p\"}");

        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code(client.line()), PHONE);

        assertThat(accepted.requestedName()).contains("p");
    }

    @Test
    @Tag("PAIRCMD-6")
    @Tag("PAIRCMD-7")
    void theNameIsTrimmedAndMayHaveSixtyFourCharacters() throws Exception {
        String longest = "p".repeat(ControlSocket.MAX_NAME_LENGTH);
        Client client = connect();
        client.send("{\"op\":\"pair\",\"name\":\"  " + longest + "\\t\"}");

        Attempt.Accepted accepted = (Attempt.Accepted) codes.attempt(code(client.line()), PHONE);

        assertThat(accepted.requestedName()).contains(longest);
    }

    @Test
    @Tag("PAIRCMD-7")
    void anInvalidNameIssuesNoCode() throws Exception {
        Issued waiting = codes.issue("laptop");
        List<String> invalid = List.of(
                "\"\"",
                "\"   \"",
                "\"" + "p".repeat(ControlSocket.MAX_NAME_LENGTH + 1) + "\"",
                "\"pho\\nne\"",
                "\"pho\\u0007ne\"",
                "\"pho\\u0085ne\"",
                // A direction override and a zero-width joiner are format characters.
                "\"\\u202Eenohp\"",
                "\"pho\\u200Dne\"",
                "\"pho\\u2028ne\"",
                "\"pho\\uD800ne\"");

        for (String name : invalid) {
            Client client = connect();
            client.send("{\"op\":\"pair\",\"name\":" + name + "}");
            assertThat(client.line()).as(name).isEqualTo("{\"error\":\"invalid_name\"}");
            assertThat(client.line()).as(name).isNull();
        }

        // No new code replaced the one already waiting.
        assertThat(waiting.outcome()).isNotDone();
    }

    @Test
    void aNameThatIsNotAStringIsABadRequest() throws Exception {
        assertThat(ask("{\"op\":\"pair\",\"name\":7}")).isEqualTo("{\"error\":\"bad_request\"}");
    }

    @Test
    void aRequestThatIsNotOneKnownJsonObjectIsABadRequest() throws Exception {
        List<String> bad = List.of(
                "{\"op\":",
                "",
                "[\"devices\"]",
                "\"devices\"",
                "{\"all\":true}",
                "{\"op\":7}",
                "{\"op\":\"shutdown\"}",
                "{\"op\":\"devices\"} {\"op\":\"devices\"}",
                "{\"op\":\"devices\",\"all\":\"yes\"}",
                "{\"op\":\"revoke\"}",
                "{\"op\":\"revoke\",\"name\":[\"phone\"]}");

        for (String request : bad) {
            assertThat(ask(request)).as(request).isEqualTo("{\"error\":\"bad_request\"}");
        }
    }

    @Test
    void aLineThatIsNotUtf8IsABadRequest() throws Exception {
        Client client = connect();
        client.channel.write(ByteBuffer.wrap(new byte[] {'{', '"', 'o', 'p', '"', ':', '"', (byte) 0xC3, '"', '}', '\n'}));

        assertThat(client.line()).isEqualTo("{\"error\":\"bad_request\"}");
    }

    @Test
    void aRequestLineIsLimitedToFourKibibytes() throws Exception {
        String prefix = "{\"op\":\"devices\",\"pad\":\"";
        String suffix = "\"}";
        String longest = prefix + "x".repeat(ControlSocket.MAX_REQUEST_BYTES - prefix.length() - suffix.length())
                + suffix;
        assertThat(longest).hasSize(ControlSocket.MAX_REQUEST_BYTES);

        assertThat(ask(longest)).isEqualTo("{\"devices\":[]}");
        Client client = connect();
        client.send(longest.replace("\"pad\":\"", "\"pad\":\"x"));
        // Only the answer is read: the service hangs up with the rest of the line unread, which resets the socket.
        assertThat(client.line()).isEqualTo("{\"error\":\"bad_request\"}");
    }

    @Test
    void aClientThatSendsNoCompleteLineInTimeIsDisconnected() throws Exception {
        socket.close();
        socket = ControlSocket.start(data.runtime(), codes, devices, connections, loopbackOnly(),
                Duration.ofMillis(200), ControlSocket.KERNEL);
        Client silent = connect();
        Client unfinished = connect();
        unfinished.channel.write(ByteBuffer.wrap("{\"op\":\"devices\"".getBytes(StandardCharsets.UTF_8)));

        assertThat(silent.line()).isNull();
        assertThat(unfinished.line()).isNull();
    }

    @Test
    void aClientThatClosesBeforeEndingTheLineGetsNoAnswer() throws Exception {
        Client client = connect();
        client.channel.write(ByteBuffer.wrap("{\"op\":\"devices\"}".getBytes(StandardCharsets.UTF_8)));
        client.channel.shutdownOutput();

        assertThat(client.line()).isNull();
    }

    @Test
    @Tag("CMD-5")
    void anotherUserIsDisconnectedWithoutAnAnswerAndAWarning() throws Exception {
        socket.close();
        UserPrincipal mallory = () -> "mallory";
        socket = ControlSocket.start(data.runtime(), codes, devices, connections, loopbackOnly(),
                ControlSocket.REQUEST_TIMEOUT,
                channel -> mallory);

        // The service hangs up before reading anything, so the test sends nothing that could hit a closed socket.
        Client client = connect();

        assertThat(client.line()).isNull();
        assertThat(warnings()).containsExactly(
                "Control socket connection refused: not the owner of the service (from user mallory)");
    }

    @Test
    @Tag("CMD-5")
    void aPeerWhoseUserCannotBeReadIsDisconnectedToo() throws Exception {
        socket.close();
        socket = ControlSocket.start(data.runtime(), codes, devices, connections, loopbackOnly(),
                ControlSocket.REQUEST_TIMEOUT,
                channel -> {
                    throw new IOException("no credentials");
                });

        Client client = connect();

        assertThat(client.line()).isNull();
        assertThat(warnings()).containsExactly(
                "Control socket connection refused: not the owner of the service (from an unknown user)");
    }

    @Test
    @Tag("DEV-1")
    @Tag("DEV-3")
    void devicesListsTheActiveOnesAndWithAllTheRevokedToo() throws Exception {
        devices.add("phone", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(1));
        String laptop = devices.add("laptop", Listener.LOOPBACK).device().id();
        clock.advance(Duration.ofMinutes(1));
        devices.recordLogin(laptop, "127.0.0.1", "Mozilla/5.0 (X11)");
        clock.advance(Duration.ofMinutes(1));
        devices.revoke("phone", connections);

        String active = "{\"name\":\"laptop\",\"paired_at\":\"2026-11-08T10:01:00Z\","
                + "\"last_seen\":\"2026-11-08T10:02:00Z\",\"last_address\":\"127.0.0.1\","
                + "\"last_user_agent\":\"Mozilla/5.0 (X11)\",\"listener\":\"loopback\"}";
        String revoked = "{\"name\":\"phone\",\"paired_at\":\"2026-11-08T10:00:00Z\",\"last_seen\":null,"
                + "\"last_address\":null,\"last_user_agent\":null,\"listener\":\"network\","
                + "\"revoked_at\":\"2026-11-08T10:03:00Z\"}";
        assertThat(ask("{\"op\":\"devices\"}")).isEqualTo("{\"devices\":[" + active + "]}");
        assertThat(ask("{\"op\":\"devices\",\"all\":false}")).isEqualTo("{\"devices\":[" + active + "]}");
        assertThat(ask("{\"op\":\"devices\",\"all\":null}")).isEqualTo("{\"devices\":[" + active + "]}");
        assertThat(ask("{\"op\":\"devices\",\"all\":true}"))
                .isEqualTo("{\"devices\":[" + revoked + "," + active + "]}");
    }

    @Test
    @Tag("DEV-6")
    void revokeClosesTheConnectionsOfTheDevice() throws Exception {
        String phone = devices.add("phone", Listener.LOOPBACK).device().id();
        List<String> closed = new CopyOnWriteArrayList<>();
        connections.register(phone, (status, reason) -> closed.add(status + " " + reason));

        assertThat(ask("{\"op\":\"revoke\",\"name\":\"phone\"}")).isEqualTo("{\"revoked\":\"phone\"}");

        assertThat(closed).containsExactly("4001 unpaired");
        assertThat(devices.devices(false)).isEmpty();
    }

    @Test
    @Tag("DEV-7")
    void revokeOfAnUnknownNameListsTheActiveDevices() throws Exception {
        devices.add("phone", Listener.LOOPBACK);
        devices.add("laptop", Listener.LOOPBACK);

        assertThat(ask("{\"op\":\"revoke\",\"name\":\"watch\"}"))
                .isEqualTo("{\"error\":\"not_found\",\"active\":[\"phone\",\"laptop\"]}");
    }

    @Test
    @Tag("DEV-7")
    void revokeOfARevokedDeviceTellsWhen() throws Exception {
        devices.add("phone", Listener.LOOPBACK);
        clock.advance(Duration.ofHours(1));
        devices.revoke("phone", connections);

        assertThat(ask("{\"op\":\"revoke\",\"name\":\"phone\"}"))
                .isEqualTo("{\"error\":\"already_revoked\",\"revoked_at\":\"2026-11-08T11:00:00Z\"}");
    }

    @Test
    @Tag("DEV-8")
    void aRevocationThatCannotBeSavedIsAServerErrorAndLoggedWithoutTheKey() throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();
        // The registry writes next to devices.json; without its directory every write fails.
        Files.move(data.data(), temp.resolve("moved"));

        assertThat(ask("{\"op\":\"revoke\",\"name\":\"phone\"}")).isEqualTo("{\"error\":\"server_error\"}");

        List<LogRecord> errors = records.stream().filter(record -> record.getLevel() == Level.SEVERE).toList();
        assertThat(errors).hasSize(1);
        assertThat(new SimpleFormatter().format(errors.getFirst()))
                .contains("Could not revoke the device phone")
                .doesNotContain(key);
        assertThat(devices.devices(false)).hasSize(1);
    }

    @Test
    @Tag("CMD-8")
    void aSocketLeftByAProcessThatIsGoneIsReplaced() throws Exception {
        socket.close();
        // A crashed service leaves its socket file; nothing listens on it any more.
        Files.writeString(data.runtime().resolve(ControlSocket.FILE_NAME), "left over");

        socket = ControlSocket.start(data.runtime(), codes, devices, connections, loopbackOnly());

        assertThat(ask("{\"op\":\"devices\"}")).isEqualTo("{\"devices\":[]}");
    }

    @Test
    @Tag("CMD-8")
    @Tag("PAIRCMD-3")
    void closingDeletesTheSocketAndEndsAWaitingPair() throws Exception {
        Client waiting = connect();
        waiting.send("{\"op\":\"pair\"}");
        code(waiting.line());

        // A second name for the socket shows whether anything still listens once the file is gone.
        Path alias = Files.createLink(temp.resolve("alias.sock"), socket.path());

        socket.close();

        assertThat(waiting.line()).isNull();
        assertThat(socket.path()).doesNotExist();
        assertThatThrownBy(() -> SocketChannel.open(UnixDomainSocketAddress.of(alias)))
                .isInstanceOf(IOException.class);
    }

    private static ControlSocket.ServiceUrls loopbackOnly() {
        return new ControlSocket.ServiceUrls(LOOPBACK, Optional.empty());
    }

    private Client connect() throws IOException {
        Client client = new Client(SocketChannel.open(UnixDomainSocketAddress.of(socket.path())));
        clients.add(client);
        return client;
    }

    /** Sends one request on a new connection and returns the one answer line. */
    private String ask(String request) throws Exception {
        Client client = connect();
        client.send(request);
        String answer = client.line();
        assertThat(client.line()).as("the connection closes after the answer").isNull();
        return answer;
    }

    private static String code(String firstLine) {
        Matcher matcher = FIRST_LINE.matcher(firstLine);
        assertThat(matcher.matches()).as(firstLine).isTrue();
        return matcher.group(1);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private List<String> warnings() {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> record.getLevel() == Level.WARNING).map(formatter::formatMessage)
                .toList();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("the condition within 5 s").isBefore(end);
            Thread.sleep(10);
        }
    }

    /** The test's end of one connection. */
    private static final class Client {

        final SocketChannel channel;
        private final BufferedReader in;

        Client(SocketChannel channel) {
            this.channel = channel;
            this.in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
        }

        void send(String line) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        /** The next line within 5 s, or {@code null} once the service closed the connection. */
        String line() throws Exception {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return in.readLine();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).get(5, TimeUnit.SECONDS);
        }
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
