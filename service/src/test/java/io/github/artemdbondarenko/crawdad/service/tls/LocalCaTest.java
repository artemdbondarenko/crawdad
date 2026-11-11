package io.github.artemdbondarenko.crawdad.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCaTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String MACHINE = "workstation";
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final int DNS = 2;
    private static final int IP = 7;

    @TempDir
    Path temp;

    private final Map<String, InetAddress[]> dns = new LinkedHashMap<>();

    @Test
    @Tag("TLS-7")
    @Tag("TLS-8")
    @Tag("TLS-12")
    void createsAConstrainedRootOnFirstCall() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");

        X509Certificate root = readRoot();
        assertThat(issued.chain()).hasSize(2).last().isEqualTo(root);
        assertThat(root.getBasicConstraints()).isZero();
        assertThat(root.getCriticalExtensionOIDs())
                .contains(Extension.basicConstraints.getId(), Extension.keyUsage.getId(),
                        Extension.nameConstraints.getId());
        // keyUsage bits: 5 = keyCertSign, 6 = cRLSign, and nothing else.
        assertThat(root.getKeyUsage()).containsExactly(false, false, false, false, false, true, true, false, false);
        assertThat(root.getPublicKey()).isInstanceOf(ECPublicKey.class);
        assertThat(((ECPublicKey) root.getPublicKey()).getParams().getCurve().getField().getFieldSize())
                .isEqualTo(256);
        assertThat(root.getSigAlgName()).isEqualTo("SHA256withECDSA");
        assertThat(root.getNotAfter().toInstant())
                .isEqualTo(ZonedDateTime.ofInstant(root.getNotBefore().toInstant(), ZoneOffset.UTC)
                        .plusYears(3).toInstant());
        assertThat(root.getNotBefore().toInstant()).isBetween(NOW.minus(Duration.ofDays(1)), NOW);
        assertThat(permitted(root)).containsExactlyInAnyOrder(
                "ip:10.0.0.0/255.0.0.0", "ip:172.16.0.0/255.240.0.0", "ip:192.168.0.0/255.255.0.0",
                "ip:100.64.0.0/255.192.0.0", "ip:169.254.0.0/255.255.0.0", "dns:workstation");
    }

    @Test
    @Tag("TLS-8")
    void permitsTheHostNameInTheRootWhenHostIsGivenByName() throws Exception {
        dns.put("laptop.example.ts.net", new InetAddress[] {InetAddress.getByName("100.101.102.103")});

        ca(MACHINE, NOW).issue("laptop.example.ts.net");

        assertThat(permitted(readRoot())).contains("dns:workstation", "dns:laptop.example.ts.net").hasSize(7);
    }

    @Test
    @Tag("TLS-9")
    @Tag("TLS-13")
    void issuesAServerCertificateForTheHostAddressAndTheMachineName() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("192.168.0.6");

        X509Certificate server = issued.chain().getFirst();
        assertThat(server.getBasicConstraints()).isEqualTo(-1);
        assertThat(server.getCriticalExtensionOIDs())
                .contains(Extension.basicConstraints.getId(), Extension.keyUsage.getId());
        // keyUsage bit 0 = digitalSignature, and nothing else.
        assertThat(server.getKeyUsage()).containsExactly(true, false, false, false, false, false, false, false, false);
        assertThat(server.getExtendedKeyUsage()).containsExactly(SERVER_AUTH);
        assertThat(server.getSigAlgName()).isEqualTo("SHA256withECDSA");
        assertThat(((ECPublicKey) server.getPublicKey()).getParams().getCurve().getField().getFieldSize())
                .isEqualTo(256);
        assertThat(subjectNames(server)).containsExactlyInAnyOrder("ip:192.168.0.6", "dns:workstation");
        assertThat(server.getIssuerX500Principal()).isEqualTo(readRoot().getSubjectX500Principal());
        assertThat(Duration.between(server.getNotBefore().toInstant(), server.getNotAfter().toInstant()))
                .isEqualTo(Duration.ofDays(397));
        assertThat(server.getPublicKey()).isInstanceOf(ECPublicKey.class);
        assertThat(issued.privateKey().getAlgorithm()).isEqualTo("EC");
        assertThat(server.getPublicKey()).isNotEqualTo(readRoot().getPublicKey());
    }

    @Test
    @Tag("TLS-9")
    void namesTheHostAndItsAddressWhenHostIsGivenByName() throws Exception {
        dns.put("laptop.example.ts.net", new InetAddress[] {
                InetAddress.getByName("100.101.102.103"), InetAddress.getByName("fd7a:115c:a1e0::1")});

        X509Certificate server = ca(MACHINE, NOW).issue("laptop.example.ts.net").chain().getFirst();

        assertThat(subjectNames(server))
                .containsExactlyInAnyOrder("dns:laptop.example.ts.net", "ip:100.101.102.103", "dns:workstation");
    }

    @Test
    @Tag("TLS-9")
    void doesNotOutliveTheRoot() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Instant late = NOW.plus(Duration.ofDays(3 * 365 - 30));

        X509Certificate server = ca(MACHINE, late).issue("10.1.2.3").chain().getFirst();

        assertThat(server.getNotAfter()).isEqualTo(readRoot().getNotAfter());
    }

    @Test
    @Tag("TLS-7")
    void reusesTheSameRootOnTheNextCall() throws Exception {
        LocalCa.ServerCertificate first = ca(MACHINE, NOW).issue("10.1.2.3");
        Snapshot before = snapshot();

        LocalCa.ServerCertificate second = ca(MACHINE, NOW.plusSeconds(60)).issue("10.1.2.3");

        assertThat(snapshot()).isEqualTo(before);
        assertThat(second.rootFingerprint()).isEqualTo(first.rootFingerprint());
        assertThat(second.chain().getLast()).isEqualTo(first.chain().getLast());
        assertThat(second.chain().getFirst().getSerialNumber())
                .isNotEqualTo(first.chain().getFirst().getSerialNumber());
        assertThat(second.chain().getFirst().getPublicKey()).isNotEqualTo(first.chain().getFirst().getPublicKey());
    }

    @Test
    @Tag("TLS-7")
    @Tag("TLS-9")
    void issuesANewServerCertificateOfTheSameRootForANewHost() throws Exception {
        LocalCa.ServerCertificate first = ca(MACHINE, NOW).issue("10.1.2.3");

        LocalCa.ServerCertificate second = ca(MACHINE, NOW).issue("172.20.0.1");

        assertThat(subjectNames(second.chain().getFirst())).contains("ip:172.20.0.1").doesNotContain("ip:10.1.2.3");
        assertThat(second.chain().getLast()).isEqualTo(first.chain().getLast());
        validate(second.chain(), readRoot());
    }

    @Test
    @Tag("FILE-3")
    @Tag("FILE-4")
    void keepsTheRootFilesPrivate() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls()))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls().resolve("ca.key"))))
                .isEqualTo("rw-------");
        assertThat(Files.readString(tls().resolve("ca.crt"))).startsWith("-----BEGIN CERTIFICATE-----\n");
        assertThat(Files.readString(tls().resolve("ca.key"))).startsWith("-----BEGIN PRIVATE KEY-----\n");
        try (var files = Files.list(tls())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("ca.key", "ca.crt");
        }
    }

    @Test
    @Tag("NET-4")
    void reportsTheSha256FingerprintOfTheRoot() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(readRoot().getEncoded());
        assertThat(issued.rootFingerprint()).isEqualTo(HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest));
    }

    @Test
    @Tag("TLS-9")
    void buildsAChainThatTheJdkValidatorAccepts() throws Exception {
        dns.put("laptop.example.ts.net", new InetAddress[] {InetAddress.getByName("100.101.102.103")});

        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("laptop.example.ts.net");

        validate(issued.chain(), readRoot());
    }

    @Test
    @Tag("TLS-8")
    void theJdkRejectsACertificateOutsideTheNameConstraints() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        X509Certificate root = readRoot();
        PrivateKey rootKey = readRootKey();

        // Whoever holds ca.key can sign anything; the constraints in the root are what stop it.
        X509Certificate publicIp = LocalCa.signServerCertificate(root, rootKey, newKeyPair().getPublic(),
                "8.8.8.8", List.of(), List.of(InetAddress.getByName("8.8.8.8")), NOW, NOW.plusSeconds(3600));
        X509Certificate foreignName = LocalCa.signServerCertificate(root, rootKey, newKeyPair().getPublic(),
                "bank.example.com", List.of("bank.example.com"), List.of(), NOW, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> validate(List.of(publicIp, root), root))
                .isInstanceOf(CertPathValidatorException.class).hasMessageContaining("name constraints");
        assertThatThrownBy(() -> validate(List.of(foreignName, root), root))
                .isInstanceOf(CertPathValidatorException.class).hasMessageContaining("name constraints");
    }

    @Test
    @Tag("TLS-10")
    void refusesAHostNameTheRootDoesNotCover() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        dns.put("other.example.ts.net", new InetAddress[] {InetAddress.getByName("100.101.102.103")});
        Snapshot before = snapshot();

        assertThatThrownBy(() -> ca(MACHINE, NOW).issue("other.example.ts.net"))
                .isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessage("root certificate does not cover other.example.ts.net; delete " + tls()
                        + " to issue a new root and reinstall it on your devices");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Tag("TLS-10")
    void refusesAnAddressTheRootDoesNotCover() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        dns.put("workstation", new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        Snapshot before = snapshot();

        assertThatThrownBy(() -> ca(MACHINE, NOW).issue("workstation"))
                .isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessageStartingWith("root certificate does not cover 8.8.8.8; delete " + tls());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void namesTheSubjectsAfterTheMachineAndTheHost() throws Exception {
        String longName = "a".repeat(60) + ".example";
        dns.put(longName, new InetAddress[] {InetAddress.getByName("10.9.9.9")});

        X509Certificate longServer = ca(MACHINE, NOW).issue(longName).chain().getFirst();
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");

        assertThat(readRoot().getSubjectX500Principal().getName()).isEqualTo("CN=Crawdad root on workstation");
        assertThat(issued.chain().getFirst().getSubjectX500Principal().getName()).isEqualTo("CN=10.1.2.3");
        // A common name is at most 64 characters; the full name is in the SAN.
        assertThat(longServer.getSubjectX500Principal().getName()).isEqualTo("CN=" + longName.substring(0, 64));
        assertThat(subjectNames(longServer)).contains("dns:" + longName);
    }

    @Test
    @Tag("TLS-10")
    void refusesAMachineNameTheRootDoesNotCover() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Snapshot before = snapshot();

        assertThatThrownBy(() -> ca("renamed", NOW).issue("10.1.2.3"))
                .isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessageStartingWith("root certificate does not cover renamed; delete " + tls());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Tag("TLS-10")
    void refusesAnExpiredRoot() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Snapshot before = snapshot();
        Instant later = readRoot().getNotAfter().toInstant().plusSeconds(1);

        assertThatThrownBy(() -> ca(MACHINE, later).issue("10.1.2.3"))
                .isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessageStartingWith("root certificate expired on " + readRoot().getNotAfter().toInstant())
                .hasMessageEndingWith("; delete " + tls() + " to issue a new root and reinstall it on your devices");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @Tag("TLS-10")
    void refusesACorruptCertificate() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Files.writeString(tls().resolve("ca.crt"), "-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----\n");

        assertUnusable("ca.crt is not a PEM certificate");
    }

    @Test
    @Tag("TLS-10")
    void refusesACorruptKey() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        String key = Files.readString(tls().resolve("ca.key"));
        // A truncated key keeps real key bytes, which must not reach the message.
        Files.writeString(tls().resolve("ca.key"), key.substring(0, key.length() / 2) + "\n-----END PRIVATE KEY-----\n");

        String message = assertUnusable("ca.key is not a PEM private key");
        for (String line : key.lines().toList()) {
            if (!line.startsWith("-----")) {
                assertThat(message).doesNotContain(line.substring(0, 16));
            }
        }
    }

    @Test
    @Tag("TLS-10")
    void refusesAKeyThatDoesNotMatchTheCertificate() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Path otherData = temp.resolve("other");
        new LocalCa(new DataDirectory(otherData, temp.resolve("run")), () -> MACHINE, this::resolve,
                Clock.fixed(NOW, ZoneOffset.UTC)).issue("10.1.2.3");
        Files.copy(otherData.resolve("tls/ca.key"), tls().resolve("ca.key"),
                StandardCopyOption.REPLACE_EXISTING);

        assertUnusable("ca.key does not belong to ca.crt");
    }

    @Test
    @Tag("TLS-10")
    void refusesACertificateThatIsNotACertificateAuthority() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");
        Files.writeString(tls().resolve("ca.crt"), pem(issued.chain().getFirst()));

        assertUnusable("ca.crt is not a certificate authority");
    }

    @Test
    @Tag("TLS-10")
    void refusesAHalfWrittenRoot() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Files.delete(tls().resolve("ca.crt"));

        assertUnusable("ca.crt is missing");
    }

    @Test
    @Tag("TLS-10")
    void refusesAKeyWithoutItsCertificate() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Files.delete(tls().resolve("ca.key"));

        assertUnusable("ca.key is missing");
    }

    @Test
    @Tag("TLS-10")
    void refusesAnUnreadableKey() throws Exception {
        ca(MACHINE, NOW).issue("10.1.2.3");
        Files.setPosixFilePermissions(tls().resolve("ca.key"), Set.of());
        try {
            assertUnusable("ca.key cannot be read");
        } finally {
            Files.setPosixFilePermissions(tls().resolve("ca.key"), PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @Tag("TLS-7")
    void createsTheRootInAnEmptyTlsDirectory() throws Exception {
        Files.createDirectories(tls());

        ca(MACHINE, NOW).issue("10.1.2.3");

        assertThat(tls().resolve("ca.crt")).exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls()))).isEqualTo("rwx------");
    }

    @Test
    @Tag("NET-3")
    void refusesAHostThatDoesNotResolve() {
        assertThatThrownBy(() -> ca(MACHINE, NOW).issue("nowhere.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot resolve --host nowhere.example");
    }

    @Test
    @Tag("NET-3")
    void refusesAHostWithoutAnIpv4Address() throws Exception {
        dns.put("six.example", new InetAddress[] {InetAddress.getByName("fd7a:115c:a1e0::1")});

        assertThatThrownBy(() -> ca(MACHINE, NOW).issue("six.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--host six.example has no IPv4 address");
    }

    @Test
    void refusesAnEmptyMachineName() {
        assertThatThrownBy(() -> ca(" ", NOW).issue("10.1.2.3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot find the name of this machine for the root certificate");
    }

    @Test
    @Tag("LOG-4")
    void keepsPrivateKeysOutOfToString() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");
        String serverKey = Base64.getEncoder().encodeToString(issued.privateKey().getEncoded());
        String rootKey = Base64.getEncoder().encodeToString(readRootKey().getEncoded());

        String text = issued.toString();

        assertThat(text).contains("privateKey=<hidden>", issued.rootFingerprint())
                .doesNotContain(serverKey.substring(serverKey.length() - 40), rootKey.substring(rootKey.length() - 40));
    }

    @Test
    void putsTheKeyAndChainIntoAKeyStore() throws Exception {
        LocalCa.ServerCertificate issued = ca(MACHINE, NOW).issue("10.1.2.3");
        char[] password = "test".toCharArray();

        KeyStore store = issued.keyStore(password);

        assertThat(store.getKey(LocalCa.ServerCertificate.ALIAS, password)).isEqualTo(issued.privateKey());
        assertThat(store.getCertificateChain(LocalCa.ServerCertificate.ALIAS))
                .containsExactlyElementsOf(issued.chain());
    }

    @Test
    void worksWithTheRealMachineName() throws Exception {
        LocalCa ca = LocalCa.forThisMachine(new DataDirectory(temp.resolve("data"), temp.resolve("run")));

        assertThat(ca.issue("10.1.2.3").chain()).hasSize(2);
    }

    private LocalCa ca(String machine, Instant now) {
        return new LocalCa(new DataDirectory(temp.resolve("data"), temp.resolve("run")), () -> machine,
                this::resolve, Clock.fixed(now, ZoneOffset.UTC));
    }

    private InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = dns.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses;
    }

    private Path tls() {
        return temp.resolve("data/tls");
    }

    private String assertUnusable(String reason) throws IOException {
        Snapshot before = snapshot();
        Throwable thrown = catchThrowable(() -> ca(MACHINE, NOW).issue("10.1.2.3"));
        assertThat(thrown).isInstanceOf(LocalCa.UnusableRootException.class)
                .hasMessage("root certificate in " + tls() + " cannot be used: " + reason + "; delete " + tls()
                        + " to issue a new root and reinstall it on your devices")
                .hasNoCause();
        assertThat(snapshot()).isEqualTo(before);
        return thrown.getMessage();
    }

    private X509Certificate readRoot() throws Exception {
        try (var in = Files.newInputStream(tls().resolve("ca.crt"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private PrivateKey readRootKey() throws Exception {
        String pem = Files.readString(tls().resolve("ca.key"));
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Validates like a TLS client that honours the root's name constraints. The JDK applies constraints
     * only from certificates in the path, not from a trust anchor, so the self-signed root is in both.
     */
    private static void validate(List<X509Certificate> chain, X509Certificate root) throws Exception {
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
        parameters.setRevocationEnabled(false);
        parameters.setDate(Date.from(NOW.plusSeconds(60)));
        CertPathValidator.getInstance("PKIX")
                .validate(CertificateFactory.getInstance("X.509").generateCertPath(chain), parameters);
    }

    private static List<String> permitted(X509Certificate root) {
        NameConstraints constraints = NameConstraints.getInstance(
                ASN1OctetString.getInstance(root.getExtensionValue(Extension.nameConstraints.getId())).getOctets());
        assertThat(constraints.getExcludedSubtrees()).isNull();
        List<String> names = new ArrayList<>();
        for (GeneralSubtree subtree : constraints.getPermittedSubtrees()) {
            GeneralName name = subtree.getBase();
            if (name.getTagNo() == GeneralName.iPAddress) {
                byte[] bytes = ASN1OctetString.getInstance(name.getName()).getOctets();
                names.add("ip:" + dotted(bytes, 0) + "/" + dotted(bytes, 4));
            } else {
                names.add("dns:" + name.getName());
            }
        }
        return names;
    }

    private static String dotted(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) + "." + (bytes[offset + 1] & 0xff) + "." + (bytes[offset + 2] & 0xff) + "."
                + (bytes[offset + 3] & 0xff);
    }

    private static List<String> subjectNames(X509Certificate certificate) throws Exception {
        List<String> names = new ArrayList<>();
        for (List<?> entry : certificate.getSubjectAlternativeNames()) {
            int type = (Integer) entry.get(0);
            assertThat(type).isIn(DNS, IP);
            names.add((type == DNS ? "dns:" : "ip:") + entry.get(1));
        }
        return names;
    }

    /** The content and modification time of every file in tls/, to prove a refusal changed nothing. */
    private record Snapshot(Map<String, String> files) {}

    private Snapshot snapshot() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var paths = Files.list(tls()).sorted()) {
            for (Path path : paths.toList()) {
                FileTime modified = Files.getLastModifiedTime(path);
                String content = Files.isReadable(path) ? HexFormat.of().formatHex(Files.readAllBytes(path)) : "?";
                files.put(path.getFileName().toString(), modified + " " + content
                        + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
            }
        }
        return new Snapshot(files);
    }
}
