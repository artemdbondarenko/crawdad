package io.github.artemdbondarenko.crawdad.service.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.service.OriginGuard;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Issued;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The handler on a server of its own with two plain HTTP connectors: one stands for loopback, the other
 * for the network address, which pairing tells apart only by its {@link Site}. TLS itself is covered
 * in {@code CrawdadServerTest}.
 */
class PairingHandlerTest {

    private static final String CHROME_ON_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    @TempDir
    Path temp;

    private final PairingCodes codes = new PairingCodes();
    /** One permit each time the handler looks up a site, which it does once per request to /pair or /device. */
    private final Semaphore sitesLookedUp = new Semaphore(0);
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
    private final Logger log = Logger.getLogger(PairingHandler.class.getName());

    private DataDirectory data;
    private DeviceRegistry devices;
    private Server server;
    private ServerConnector loopback;
    private ServerConnector network;

    @BeforeEach
    void startServer() throws Exception {
        log.addHandler(collect);
        data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        devices = DeviceRegistry.open(data, Clock.systemUTC());
        server = new Server();
        loopback = connector(server);
        network = connector(server);
        org.eclipse.jetty.server.Handler next = new org.eclipse.jetty.server.Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                if (org.eclipse.jetty.server.Request.getPathInContext(request).startsWith("/missing")) {
                    return false;
                }
                response.setStatus(299);
                callback.succeeded();
                return true;
            }
        };
        server.setHandler(new PairingHandler(next, codes, devices, this::siteFor));
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        log.removeHandler(collect);
        server.stop();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource(nullValues = "none", value = {
        "https://evil.example, own",
        "none, own",
        "own, evil.example"
    })
    @Tag("PAIR-7")
    void aForeignPageIsRefusedBeforeTheCodeAndSpendsNoAttempts(String origin, String host) throws Exception {
        Issued issued = codes.issue(null);
        String ownAuthority = authority(loopback);
        String sentHost = host.equals("own") ? ownAuthority : host;
        String sentOrigin = "own".equals(origin) ? "http://" + ownAuthority : origin;

        // More refusals than the five wrong attempts that would burn the code.
        for (int i = 0; i < 6; i++) {
            Answer refused = send(loopback, "POST", "/pair", sentHost, sentOrigin, List.of(), body(issued.code()));
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.body()).isEqualTo("{\"error\":\"forbidden\"}");
            assertThat(refused.header("set-cookie")).isEmpty();
        }

        assertThat(issued.outcome()).isNotDone();
        assertThat(pair(loopback, issued.code()).status()).isEqualTo(204);
        // The aggregator writes the first refusal from a source and counts the rest.
        assertThat(messages(java.util.logging.Level.WARNING)).containsExactly(
                "Pairing refused: Origin=" + sentOrigin + ", Host=" + sentHost + " (from 127.0.0.1)");
    }

    @Test
    @Tag("LOG-5")
    void aRefusedOriginDoesNotHideTheWrongCodesThatFollowFromTheSameAddress() throws Exception {
        Issued issued = codes.issue(null);
        String wrong = issued.code().equals("000000") ? "111111" : "000000";
        String host = authority(loopback);

        for (int i = 0; i < 3; i++) {
            Answer refused = send(loopback, "POST", "/pair", host, "https://evil.example", List.of(), body(wrong));
            assertThat(refused.status()).isEqualTo(403);
        }
        assertThat(pair(loopback, wrong).status()).isEqualTo(401);
        assertThat(send(loopback, "GET", "/device", "evil.example", null, List.of(), null).status()).isEqualTo(403);

        // Each kind from the one address is written at once; only its own repeats are counted.
        assertThat(messages(java.util.logging.Level.WARNING)).containsExactly(
                "Pairing refused: Origin=https://evil.example, Host=" + host + " (from 127.0.0.1)",
                "Wrong pairing code (from 127.0.0.1)",
                "Device check refused: Origin=null, Host=evil.example (from 127.0.0.1)");
    }

    @Test
    @Tag("PAIR-8")
    @Tag("PAIR-10")
    void aWrongCodeIsRefusedAndWarnedAboutWithItsSourceButNotTheCode() throws Exception {
        Issued issued = codes.issue(null);
        String wrong = issued.code().equals("000000") ? "111111" : "000000";

        Answer refused = pair(loopback, wrong);

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body()).isEqualTo("{\"error\":\"invalid_code\"}");
        assertThat(refused.header("content-type")).containsExactly("application/json");
        assertThat(refused.header("set-cookie")).isEmpty();
        assertThat(messages(java.util.logging.Level.WARNING)).containsExactly("Wrong pairing code (from 127.0.0.1)");
        assertThat(allLogText()).doesNotContain(wrong).doesNotContain(issued.code());
    }

    @ParameterizedTest(name = "{0}")
    @Tag("PAIR-8")
    @ValueSource(strings = {
        "{\"code\":\"12345\"}",
        "{\"code\":\"1234567\"}",
        "{\"code\":\"12345a\"}",
        "{\"code\":\" 123456\"}",
        "{\"code\":123456}",
        "{\"code\":null}",
        "{\"pin\":\"123456\"}",
        "[\"123456\"]",
        "\"123456\"",
        "{\"code\":\"123456\"",
        "{\"code\":\"123456\"} {}",
        "code=123456",
        ""
    })
    void anythingButSixDigitsInTheBodyIsAWrongAttempt(String body) throws Exception {
        Issued issued = codes.issue(null);

        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            Answer refused = send(loopback, "POST", "/pair", body);
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.body()).isEqualTo("{\"error\":\"invalid_code\"}");
        }

        // Five of them burned the code, so each one was counted.
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Burned(List.of("127.0.0.1")));
    }

    @ParameterizedTest(name = "{0}")
    @Tag("PAIR-8")
    @ValueSource(strings = {
        "{\"code\":\"%s\",\"name\":\"phone\"}",
        "{\"name\":\"phone\",\"code\":\"%s\"}",
        "{\"code\":\"%s\",\"code\":\"%<s\"}",
        "{\"code\":\"000000\",\"code\":\"%s\"}",
        "{\"code\":\"%s\",\"extra\":null}"
    })
    void aBodyWithMoreThanTheCodeIsAWrongAttemptEvenWithTheRightCode(String template) throws Exception {
        Issued issued = codes.issue(null);
        String body = template.formatted(issued.code());

        for (int i = 0; i < PairingCodes.ATTEMPTS; i++) {
            Answer refused = send(loopback, "POST", "/pair", body);
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.body()).isEqualTo("{\"error\":\"invalid_code\"}");
            assertThat(refused.header("set-cookie")).isEmpty();
        }

        // Five of them burned the code, so each one was counted, and none paired a device.
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Burned(List.of("127.0.0.1")));
        assertThat(devices.devices(true)).isEmpty();
    }

    @Test
    @Tag("PAIR-8")
    void aBodyOverOneKibibyteIsAWrongAttemptEvenWithTheRightCode() throws Exception {
        Issued issued = codes.issue(null);
        String padded = "{\"code\":\"" + issued.code() + "\"" + " ".repeat(1024) + "}";
        String justFits = "{\"code\":\"" + issued.code() + "\"" + " ".repeat(1024 - 17) + "}";
        assertThat(justFits).hasSize(1024);

        Answer refused = send(loopback, "POST", "/pair", padded);
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body()).isEqualTo("{\"error\":\"invalid_code\"}");

        assertThat(send(loopback, "POST", "/pair", justFits).status()).isEqualTo(204);
    }

    @Test
    void slowPairingBodiesHoldNoThreadSoOtherRequestsAreStillServed() throws Exception {
        Issued issued = codes.issue(null);
        // More stalled bodies than the 200 threads of Jetty's default pool: one byte of the hundred announced.
        int stalledCount = 220;
        String stalledRequest = "POST /pair HTTP/1.1\r\nHost: " + authority(loopback) + "\r\nOrigin: "
                + origin(loopback) + "\r\nContent-Length: 100\r\n\r\n{";
        List<Socket> stalled = new ArrayList<>();
        try {
            for (int i = 0; i < stalledCount; i++) {
                Socket socket = new Socket("127.0.0.1", loopback.getLocalPort());
                stalled.add(socket);
                socket.getOutputStream().write(stalledRequest.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            }
            // Every stalled request has passed the guard and waits for its body; a blocking read would have
            // run out of threads before the last of them got here.
            assertThat(sitesLookedUp.tryAcquire(stalledCount, 3, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThat(send(loopback, "GET", "/", null).status()).isEqualTo(299);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            // An unfinished body is no attempt yet, and pairing itself still gets a thread.
            assertThat(issued.outcome()).isNotDone();
            assertThat(pair(loopback, issued.code()).status()).isEqualTo(204);
        } finally {
            for (Socket socket : stalled) {
                socket.close();
            }
        }
    }

    @Test
    @Tag("PAIR-6")
    @Tag("PAIR-12")
    @Tag("KEY-2")
    void theRightCodeSetsTheKeyCookieOnLoopback() throws Exception {
        Issued issued = codes.issue(null);

        Answer paired = send(loopback, "POST", "/pair", authority(loopback), "http://" + authority(loopback),
                List.of("User-Agent: " + CHROME_ON_ANDROID), body(issued.code()));

        assertThat(paired.status()).isEqualTo(204);
        assertThat(paired.header("cache-control")).containsExactly("no-store");
        Device device = devices.devices(false).getFirst();
        assertThat(device.name()).isEqualTo("Chrome on Android");
        assertThat(device.listener()).isEqualTo(Listener.LOOPBACK);
        String key = keyFrom(paired, "crawdad_device_" + loopback.getLocalPort());
        assertThat(paired.header("set-cookie")).containsExactly("crawdad_device_" + loopback.getLocalPort() + "="
                + key + "; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict");
        // 32 random bytes, base64url without padding.
        assertThat(key).matches("[A-Za-z0-9_-]{43}");
        assertThat(devices.authenticate(key, Listener.LOOPBACK)).contains(device);
        // The waiting crawdad pair learns the name, the address and the raw User-Agent.
        assertThat(issued.outcome()).isCompletedWithValue(
                new PairingOutcome.Paired("Chrome on Android", "127.0.0.1", CHROME_ON_ANDROID));
    }

    @Test
    @Tag("PAIR-6")
    @Tag("KEY-2")
    void theRightCodeSetsAHostPrefixedSecureCookieOnTheNetworkAddress() throws Exception {
        Issued issued = codes.issue("phone");

        Answer paired = pair(network, issued.code());

        assertThat(paired.status()).isEqualTo(204);
        String name = "__Host-crawdad_device_" + network.getLocalPort();
        assertThat(paired.header("set-cookie")).containsExactly(name + "=" + keyFrom(paired, name)
                + "; Path=/; Max-Age=34560000; Secure; HttpOnly; SameSite=Strict");
        Device device = devices.devices(false).getFirst();
        assertThat(device.name()).isEqualTo("phone");
        assertThat(device.listener()).isEqualTo(Listener.NETWORK);
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Paired("phone", "127.0.0.1", null));
    }

    @Test
    @Tag("PAIR-3")
    @Tag("PAIR-8")
    void aCodeIsExchangedOnlyOnce() throws Exception {
        Issued issued = codes.issue(null);

        assertThat(pair(loopback, issued.code()).status()).isEqualTo(204);
        assertThat(pair(loopback, issued.code()).status()).isEqualTo(401);
        assertThat(devices.devices(false)).hasSize(1);
    }

    @Test
    @Tag("PAIR-10")
    void logsThePairingWithNameAndAddressButNeitherKeyNorCode() throws Exception {
        Issued issued = codes.issue("phone");

        Answer paired = pair(loopback, issued.code());

        assertThat(messages(java.util.logging.Level.INFO)).containsExactly("Device paired: phone from 127.0.0.1");
        assertThat(allLogText()).doesNotContain(keyFrom(paired, "crawdad_device_" + loopback.getLocalPort()))
                .doesNotContain(issued.code());
    }

    @Test
    @Tag("PAIR-9")
    void aRegistryThatCannotSaveFailsThePairingAndUsesUpTheCode() throws Exception {
        Issued issued = codes.issue("phone");
        // The registry writes next to devices.json; without its directory every write fails.
        Files.delete(data.data());

        Answer failed = pair(loopback, issued.code());

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body()).isEqualTo("{\"error\":\"server_error\"}");
        assertThat(failed.header("set-cookie")).isEmpty();
        assertThat(issued.outcome()).isCompletedWithValue(new PairingOutcome.Failed());
        assertThat(pair(loopback, issued.code()).status()).isEqualTo(401);
        List<LogRecord> errors = records.stream()
                .filter(record -> record.getLevel() == java.util.logging.Level.SEVERE).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getMessage()).isEqualTo("Could not save the device paired from 127.0.0.1");
        assertThat(errors.getFirst().getThrown()).isInstanceOf(IOException.class);
        assertThat(allLogText()).doesNotContain(issued.code());
    }

    @Test
    @Tag("PAIR-12")
    void refusalsOfPairAndDeviceAreNotCachedEither() throws Exception {
        codes.issue(null);
        String foreign = "https://evil.example";

        List<Answer> refusals = List.of(
                pair(loopback, "000000"),
                send(loopback, "POST", "/pair", authority(loopback), foreign, List.of(), body("000000")),
                send(loopback, "GET", "/device", "evil.example", null, List.of(), null));

        assertThat(refusals).extracting(Answer::status).containsExactly(401, 403, 403);
        assertThat(refusals).allSatisfy(answer ->
                assertThat(answer.header("cache-control")).containsExactly("no-store"));
    }

    @ParameterizedTest(name = "{0}")
    @Tag("PAIR-15")
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x"})
    void readsTheBodyAsJsonWhateverItsContentType(String contentType) throws Exception {
        Issued issued = codes.issue(null);

        Answer paired = send(loopback, "POST", "/pair", authority(loopback), origin(loopback),
                List.of("Content-Type: " + contentType), body(issued.code()));

        assertThat(paired.status()).isEqualTo(204);
    }

    @Test
    @Tag("PAIR-11")
    @Tag("PAIR-12")
    void otherMethodsAreNotAllowed() throws Exception {
        Answer getPair = send(loopback, "GET", "/pair", null);
        assertThat(getPair.status()).isEqualTo(405);
        assertThat(getPair.header("allow")).containsExactly("POST");
        assertThat(getPair.header("cache-control")).containsExactly("no-store");
        assertThat(getPair.body()).isEqualTo("{\"error\":\"method_not_allowed\"}");

        Answer postDevice = send(loopback, "POST", "/device", "{}");
        assertThat(postDevice.status()).isEqualTo(405);
        assertThat(postDevice.header("allow")).containsExactly("GET");
        assertThat(postDevice.body()).isEqualTo("{\"error\":\"method_not_allowed\"}");
    }

    @Test
    void otherPathsGoToTheWrappedHandler() throws Exception {
        assertThat(send(loopback, "GET", "/", null).status()).isEqualTo(299);
        assertThat(send(loopback, "POST", "/pair/", body("123456")).status()).isEqualTo(299);
        assertThat(send(loopback, "GET", "/devices", null).status()).isEqualTo(299);
        // A path the wrapped handler does not take stays unhandled, so the server answers 404.
        assertThat(send(loopback, "GET", "/missing", null).status()).isEqualTo(404);
    }

    @Test
    void blocksWhateverTheWrappedHandlerDoes() {
        org.eclipse.jetty.server.Handler nonBlocking = new org.eclipse.jetty.server.Handler.Abstract.NonBlocking() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                return false;
            }
        };

        // Writing the registry blocks a thread, which Jetty must be told.
        assertThat(new PairingHandler(nonBlocking, codes, devices, this::siteFor).getInvocationType())
                .isEqualTo(org.eclipse.jetty.util.thread.Invocable.InvocationType.BLOCKING);
    }

    @Test
    @Tag("KEY-6")
    @Tag("KEY-8")
    @Tag("PAIR-12")
    @Tag("DEV-2")
    void theDeviceAnswersWithItsNameAndRenewsTheSameCookie() throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();
        String cookie = "crawdad_device_" + loopback.getLocalPort();

        // A browser sends no Origin on a GET to the page's own address.
        Answer answer = device(loopback, null, cookie + "=" + key);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("{\"name\":\"phone\"}");
        assertThat(answer.header("content-type")).containsExactly("application/json");
        assertThat(answer.header("cache-control")).containsExactly("no-store");
        assertThat(answer.header("set-cookie")).containsExactly(
                cookie + "=" + key + "; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict");
        // Only a handshake counts as a connection of the device (DEV-2).
        assertThat(devices.devices(false).getFirst().lastSeen()).isNull();
    }

    @Test
    @Tag("KEY-2")
    @Tag("KEY-6")
    void theDeviceRenewsTheSecureCookieOnTheNetworkAddress() throws Exception {
        String key = devices.add("phone", Listener.NETWORK).key();
        String cookie = "__Host-crawdad_device_" + network.getLocalPort();

        Answer answer = device(network, "https://" + authority(network), cookie + "=" + key);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("set-cookie")).containsExactly(
                cookie + "=" + key + "; Path=/; Max-Age=34560000; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    @Tag("KEY-4")
    @Tag("KEY-5")
    void aKeyWorksOnlyOnTheAddressItWasPairedOn() throws Exception {
        String loopbackKey = devices.add("laptop", Listener.LOOPBACK).key();
        String networkKey = devices.add("phone", Listener.NETWORK).key();

        // Each key under the cookie name of the other address, as a browser sharing cookies across ports would send it.
        assertThat(device(network, null, "__Host-crawdad_device_" + network.getLocalPort() + "=" + loopbackKey)
                .status()).isEqualTo(401);
        assertThat(device(loopback, null, "crawdad_device_" + loopback.getLocalPort() + "=" + networkKey)
                .status()).isEqualTo(401);
    }

    @Test
    @Tag("KEY-7")
    void findsTheWorkingKeyAmongSeveralCookiesOfTheSameName() throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();
        String cookie = "crawdad_device_" + loopback.getLocalPort();

        Answer answer = device(loopback, null, cookie + "=forged; " + cookie + "=" + key);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("set-cookie")).containsExactly(
                cookie + "=" + key + "; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict");
    }

    @Test
    @Tag("PAIR-10")
    void aKeyedDeviceKeepsItsKeyOutOfItsText() throws Exception {
        DeviceRegistry.NewDevice added = devices.add("phone", Listener.LOOPBACK);

        String text = new PairingHandler.KeyedDevice(added.device(), added.key()).toString();

        assertThat(text).doesNotContain(added.key()).contains("key=<hidden>").contains("name=phone");
    }

    @Test
    @Tag("KEY-2")
    void readsTheKeyOnlyFromTheCookieOfThisPort() throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();

        assertThat(device(loopback, null, "crawdad_device_1=" + key).status()).isEqualTo(401);
        assertThat(device(loopback, null, "__Host-crawdad_device_" + loopback.getLocalPort() + "=" + key).status())
                .isEqualTo(401);
    }

    @Test
    @Tag("KEY-6")
    @Tag("PAIR-12")
    void anUnknownMissingOrRevokedKeyIsUnauthorizedQuietlyAndKeepsTheCookie() throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();
        devices.revoke("phone", new ConnectionRegistry());
        String cookie = "crawdad_device_" + loopback.getLocalPort();

        for (String sent : new String[] {null, cookie + "=forged", cookie + "=" + key, cookie + "="}) {
            Answer answer = device(loopback, null, sent);
            assertThat(answer.status()).as(String.valueOf(sent)).isEqualTo(401);
            assertThat(answer.body()).isEqualTo("{\"error\":\"not_paired\"}");
            assertThat(answer.header("cache-control")).containsExactly("no-store");
            assertThat(answer.header("set-cookie")).isEmpty();
        }
        assertThat(messages(java.util.logging.Level.WARNING)).isEmpty();
    }

    @ParameterizedTest(name = "Origin {0}, Host {1}")
    @CsvSource(nullValues = "none", value = {
        "https://evil.example, own",
        "null, own",
        "none, evil.example",
        "none, 127.0.0.1"
    })
    @Tag("KEY-6")
    void theDeviceRefusesAForeignHostOrASentForeignOrigin(String origin, String host) throws Exception {
        String key = devices.add("phone", Listener.LOOPBACK).key();
        String sentHost = host.equals("own") ? authority(loopback) : host;

        Answer refused = send(loopback, "GET", "/device", sentHost, origin,
                List.of("Cookie: crawdad_device_" + loopback.getLocalPort() + "=" + key), null);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body()).isEqualTo("{\"error\":\"forbidden\"}");
        assertThat(refused.header("set-cookie")).isEmpty();
        assertThat(messages(java.util.logging.Level.WARNING)).containsExactly(
                "Device check refused: Origin=" + origin + ", Host=" + sentHost + " (from 127.0.0.1)");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                + "Safari/537.36 Edg/130.0.0.0 | Edge on Windows",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile "
                + "Safari/537.36 EdgA/130.0.0.0 | Edge on Android",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "EdgiOS/130.0.0.0 Mobile/15E148 Safari/605.1.15 | Edge on iOS",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Edge/18.19045 | Edge on Windows",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile "
                + "Safari/537.36 | Chrome on Android",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "CriOS/130.0.0.0 Mobile/15E148 Safari/604.1 | Chrome on iOS",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 "
                + "| Chrome on Linux",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                + "Safari/537.36 | Chrome on macOS",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0 | Firefox on Windows",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0 | Firefox on Linux",
        "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0 | Firefox on Android",
        "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/132.0 "
                + "Mobile/15E148 Safari/605.1.15 | Firefox on iOS",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 "
                + "Safari/605.1.15 | Safari on macOS",
        "Mozilla/5.0 (iPod touch; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/18.0 Mobile/15E148 Safari/604.1 | Safari on iOS",
        "Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) | Browser",
        "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                + "Safari/537.36 | Chrome",
        "curl/8.5.0 | Browser",
        "Wget/1.21 (linux-gnu) | Browser",
        "SomeTool (Windows) | Browser on Windows",
        "SomeTool (Mac OS X) | Browser on macOS",
        "SomeTool (Linux) | Browser on Linux",
        "'' | Browser"
    })
    @Tag("PAIRCMD-6")
    void namesADeviceAfterItsBrowserAndSystem(String userAgent, String name) {
        assertThat(PairingHandler.nameFor(userAgent)).isEqualTo(name);
    }

    @Test
    @Tag("PAIRCMD-6")
    void namesADeviceWithoutUserAgentBrowser() {
        assertThat(PairingHandler.nameFor(null)).isEqualTo("Browser");
    }

    /** A plain HTTP connector on loopback with a port of its own. */
    private static ServerConnector connector(Server server) {
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        return connector;
    }

    /** {@code network} stands for the TLS address: an https page, network devices, secure cookies. */
    private Site siteFor(Connector connector) {
        sitesLookedUp.release();
        int port = ((ServerConnector) connector).getLocalPort();
        if (connector == network) {
            return new Site(new OriginGuard("https", "127.0.0.1", port), Listener.NETWORK, true, port);
        }
        return new Site(new OriginGuard("http", "127.0.0.1", port), Listener.LOOPBACK, false, port);
    }

    private static String authority(ServerConnector connector) {
        return "127.0.0.1:" + connector.getLocalPort();
    }

    private String origin(ServerConnector connector) {
        return (connector == network ? "https://" : "http://") + authority(connector);
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private Answer pair(ServerConnector connector, String code) throws IOException {
        return send(connector, "POST", "/pair", body(code));
    }

    /** {@code GET /device} with the own Host, this Origin and this Cookie header, each left out when null. */
    private Answer device(ServerConnector connector, String origin, String cookie) throws IOException {
        return send(connector, "GET", "/device", authority(connector), origin,
                cookie == null ? List.of() : List.of("Cookie: " + cookie), null);
    }

    /** A request from the page of {@code connector}: its own Host and Origin. */
    private Answer send(ServerConnector connector, String method, String path, String body) throws IOException {
        return send(connector, method, path, authority(connector), origin(connector), List.of(), body);
    }

    /** Sends one raw request, so that Host and repeated headers are exactly as given; null headers are left out. */
    private static Answer send(ServerConnector connector, String method, String path, String host, String origin,
            List<String> headers, String body) throws IOException {
        StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (host != null) {
            request.append("Host: ").append(host).append("\r\n");
        }
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        headers.forEach(header -> request.append(header).append("\r\n"));
        if (body != null) {
            request.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        if (body != null) {
            request.append(body);
        }
        try (Socket socket = new Socket("127.0.0.1", connector.getLocalPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return Answer.parse(readUntilClosed(socket.getInputStream()));
        }
    }

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
        return received.toString(StandardCharsets.UTF_8);
    }

    /** The value of the cookie {@code name} in the answer's {@code Set-Cookie}. */
    private static String keyFrom(Answer answer, String name) {
        List<String> cookies = answer.header("set-cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.getFirst()).startsWith(name + "=");
        return cookies.getFirst().substring(name.length() + 1, cookies.getFirst().indexOf(';'));
    }

    private List<String> messages(java.util.logging.Level level) {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> record.getLevel() == level).map(formatter::formatMessage).toList();
    }

    /** Every log line of the handler as written, with causes. */
    private String allLogText() {
        SimpleFormatter formatter = new SimpleFormatter();
        return String.join("\n", records.stream().map(formatter::format).toList());
    }

    private record Answer(int status, List<String> headerLines, String body) {

        static Answer parse(String raw) {
            int end = raw.indexOf("\r\n\r\n");
            assertThat(end).as("a complete answer: " + raw).isPositive();
            String[] lines = raw.substring(0, end).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            List<String> headers = new ArrayList<>(List.of(lines).subList(1, lines.length));
            return new Answer(status, headers, raw.substring(end + 4));
        }

        /** The values of a header, in order; the name is case-insensitive. */
        List<String> header(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            return headerLines.stream()
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .map(line -> line.substring(prefix.length()).trim())
                    .toList();
        }
    }
}
