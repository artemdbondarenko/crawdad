package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.Socket;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CrawdadServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private CrawdadServer server;

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
    void mainStartsTheServerAndPrintsWhereToOpenIt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        server = CrawdadService.run(new String[] {"--port", "0"}, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Open " + server.address());
        assertThat(get(server.address()).statusCode()).isEqualTo(200);
    }

    @Test
    void upgradesToWebSocketForThePageItServes() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");
        String authority = server.address().getAuthority();

        assertThat(handshake(authority, "http://" + authority)).startsWith("HTTP/1.1 101");
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
    void tellsADisconnectedClientFromOtherSendFailures() {
        assertThat(CrawdadServer.Endpoint.isClientGone(new EofException("reset"))).isTrue();
        assertThat(CrawdadServer.Endpoint.isClientGone(new ClosedChannelException())).isTrue();
        assertThat(CrawdadServer.Endpoint.isClientGone(new IOException("disk full"))).isFalse();
        assertThat(CrawdadServer.Endpoint.isClientGone(new IllegalStateException())).isFalse();
    }

    private static HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a WebSocket handshake to /ws with the given Origin and returns the status line of the answer. */
    private static String handshake(String authority, String origin) throws IOException {
        URI address = URI.create("http://" + authority);
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET /ws HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\n"
                    + "Origin: " + origin + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }
}
