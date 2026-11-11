package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

class SecurityHeadersTest {

    /** The headers of the contract, written out here so that a change in the code cannot change them silently. */
    private static final Map<String, String> CONTRACT = Map.of(
            "Content-Security-Policy", "default-src 'self'; script-src 'self'; connect-src 'self'; "
                    + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer",
            "Cross-Origin-Opener-Policy", "same-origin");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The network address of the tests: a second loopback address, served like a private one. */
    private static final String NETWORK_HOST = "127.0.0.2";

    private CrawdadServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @Tag("HTTP-3")
    void servesThePageWithTheHeadersOfTheContract() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");

        HttpResponse<String> response = get(HTTP, server.address());

        assertThat(response.statusCode()).isEqualTo(200);
        assertHasTheContractHeaders(response);
    }

    @Test
    @Tag("HTTP-3")
    void addsTheHeadersToANotFoundAnswer() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/");

        // An error page must not be framable or sniffed either.
        HttpResponse<String> response = get(HTTP, server.address().resolve("/assets/missing.js"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertHasTheContractHeaders(response);
    }

    @Test
    @Tag("HTTP-3")
    void servesTheSameHeadersOnTheNetworkAddress() throws Exception {
        X509Certificate certificate = startWithNetworkAddress();
        HttpClient https = HttpClient.newBuilder().sslContext(trusting(certificate)).build();

        HttpResponse<String> response = get(https, server.networkAddress().orElseThrow());

        assertThat(response.statusCode()).isEqualTo(200);
        assertHasTheContractHeaders(response);
        assertHasTheContractHeaders(get(HTTP, server.address()));
    }

    @Test
    @Tag("HTTP-3")
    @Tag("HTTP-4")
    void servesTheBuiltPageAndItsScriptWithTheHeaders() throws Exception {
        startWithTheBuiltClient();

        HttpResponse<String> page = get(HTTP, server.address());
        assertThat(page.statusCode()).isEqualTo(200);
        assertHasTheContractHeaders(page);

        Matcher script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(page.body());
        assertThat(script.find()).as("the page loads its script from /assets/").isTrue();
        HttpResponse<String> asset = get(HTTP, server.address().resolve(script.group(1)));
        assertThat(asset.statusCode()).isEqualTo(200);
        assertHasTheContractHeaders(asset);
    }

    @Test
    @Tag("HTTP-4")
    void theBuiltPageHasNothingInlineTheCspWouldBlock() throws Exception {
        startWithTheBuiltClient();

        String page = get(HTTP, server.address()).body();

        List<String> scripts = tags("script", page);
        assertThat(scripts).as("the page loads a script").isNotEmpty();
        assertThat(scripts).as("every script comes from a file: script-src 'self' blocks inline ones")
                .allMatch(tag -> Pattern.compile("\\ssrc\\s*=").matcher(tag).find());
        assertThat(tags("style", page)).as("no <style> element: default-src 'self' blocks it").isEmpty();
        assertThat(page).as("no style attribute: default-src 'self' blocks it")
                .doesNotContainPattern("(?i)<[^>]*\\sstyle\\s*=");
    }

    @Test
    @Tag("HTTP-4")
    void findsInlineScriptsAndStylesInAPage() {
        String page = "<head><script type=\"module\" src=\"/a.js\"></script><script>alert(1)</script>"
                + "<STYLE>p{}</STYLE></head>";

        // Guards the check above against a regular expression that never matches.
        assertThat(tags("script", page))
                .containsExactly("<script type=\"module\" src=\"/a.js\">", "<script>");
        assertThat(tags("style", page)).containsExactly("<STYLE>");
    }

    /** Opening tags with this name, in any case. */
    private static List<String> tags(String name, String page) {
        Matcher tag = Pattern.compile("(?i)<" + name + "\\b[^>]*>").matcher(page);
        List<String> found = new ArrayList<>();
        while (tag.find()) {
            found.add(tag.group());
        }
        return found;
    }

    /** Each header of the contract exactly once, with exactly its value. */
    private static void assertHasTheContractHeaders(HttpResponse<?> response) {
        CONTRACT.forEach((name, value) -> assertThat(response.headers().allValues(name)).as(name)
                .containsExactly(value));
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Starts the server from the main build with the client built by the clients module. */
    private void startWithTheBuiltClient() throws Exception {
        // The clients module builds the page only in its package phase; a plain `mvn test` has none.
        Path builtClient = Path.of("../clients/desktop/dist/index.html");
        assumeTrue(Files.exists(builtClient), "the client is not built: " + builtClient.toAbsolutePath());
        Path mainBuild = Path.of(CrawdadService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Only the main build on the class path: the test page under the same name would win otherwise.
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader mainOnly = new URLClassLoader(new URL[] {mainBuild.toUri().toURL()}, null)) {
            thread.setContextClassLoader(mainOnly);
            server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), CrawdadService.CLIENT_FILES);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /** Starts the server with the network address 127.0.0.2 and a self-signed certificate for it. */
    private X509Certificate startWithNetworkAddress() throws Exception {
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
        server = CrawdadServer.start(new ServiceConfig(NETWORK_HOST, 0, Optional.empty()), "static/",
                new FakeAgentAdapter(), Optional.of(new NetworkCertificate(pair.getPrivate(), List.of(certificate),
                        NETWORK_HOST, Optional.empty())));
        return certificate;
    }

    private static SSLContext trusting(X509Certificate certificate) throws Exception {
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("test", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
