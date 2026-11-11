package io.github.artemdbondarenko.crawdad.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.artemdbondarenko.crawdad.service.ServiceConfig.TlsFiles;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsMaterialTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant NOT_BEFORE = NOW.minus(Duration.ofDays(30));
    private static final Instant NOT_AFTER = NOW.plus(Duration.ofDays(60));
    private static final String NODE = "laptop.example.ts.net";

    @TempDir
    Path temp;

    @Test
    @Tag("TLS-2")
    @Tag("TLS-4")
    void readsAMatchingPairAndNamesTheAddressAfterTheFirstDnsName() throws Exception {
        KeyPair pair = ecKeyPair();
        X509Certificate certificate = selfSigned(pair, List.of(ip("100.101.102.103"), dns(NODE), dns("other.ts.net")));
        TlsFiles files = write(pem(certificate), pkcs8(pair.getPrivate()));

        TlsMaterial material = TlsMaterial.read(files, clockAt(NOW));

        assertThat(material.addressName()).isEqualTo(NODE);
        assertThat(material.chain()).containsExactly(certificate);
        assertThat(material.privateKey()).isEqualTo(pair.getPrivate());
        assertThat(material.notAfter()).isEqualTo(NOT_AFTER);
    }

    @Test
    @Tag("TLS-2")
    void readsAnEcKeyInSec1Form() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), sec1(pair));

        TlsMaterial material = TlsMaterial.read(files, clockAt(NOW));

        assertThat(secret(material.privateKey())).isEqualTo(secret(pair.getPrivate()));
    }

    @Test
    @Tag("TLS-2")
    void skipsEcParametersThatOpensslWritesBeforeTheKey() throws Exception {
        KeyPair pair = ecKeyPair();
        // DER of OID 1.2.840.10045.3.1.7 (P-256), as in the output of "openssl ecparam -genkey".
        String parameters = block("EC PARAMETERS", new byte[] {6, 8, 42, -122, 72, -50, 61, 3, 1, 7});
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), parameters + sec1(pair));

        TlsMaterial material = TlsMaterial.read(files, clockAt(NOW));

        assertThat(secret(material.privateKey())).isEqualTo(secret(pair.getPrivate()));
    }

    @Test
    @Tag("TLS-2")
    void readsAnRsaKeyInPkcs1Form() throws Exception {
        KeyPair pair = rsaKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs1(pair.getPrivate()));

        TlsMaterial material = TlsMaterial.read(files, clockAt(NOW));

        assertThat(material.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(secret(material.privateKey())).isEqualTo(secret(pair.getPrivate()));
        assertThat(material.addressName()).isEqualTo(NODE);
    }

    @Test
    @Tag("TLS-2")
    void readsAnRsaKeyInPkcs8Form() throws Exception {
        KeyPair pair = rsaKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));

        assertThat(TlsMaterial.read(files, clockAt(NOW)).privateKey()).isEqualTo(pair.getPrivate());
    }

    @Test
    @Tag("TLS-2")
    void keepsTheWholeChainInOrderAndPutsItIntoAKeyStore() throws Exception {
        KeyPair issuerPair = ecKeyPair();
        X509Certificate issuer = certificate(issuerPair.getPublic(), "CN=Test intermediate", issuerPair.getPrivate(),
                "CN=Test intermediate", List.of(), NOT_BEFORE, NOT_AFTER, true);
        KeyPair server = ecKeyPair();
        X509Certificate leaf = certificate(server.getPublic(), "CN=" + NODE, issuerPair.getPrivate(),
                "CN=Test intermediate", List.of(dns(NODE)), NOT_BEFORE, NOT_AFTER, false);
        TlsFiles files = write(pem(leaf) + pem(issuer), pkcs8(server.getPrivate()));
        char[] password = "test".toCharArray();

        TlsMaterial material = TlsMaterial.read(files, clockAt(NOW));
        KeyStore store = material.keyStore(password);

        assertThat(material.chain()).containsExactly(leaf, issuer);
        assertThat(store.getKey(TlsMaterial.ALIAS, password)).isEqualTo(server.getPrivate());
        assertThat(store.getCertificateChain(TlsMaterial.ALIAS)).containsExactly(leaf, issuer);
    }

    @Test
    @Tag("TLS-4")
    void namesTheAddressAfterTheFirstIpAddressWhenTheSanHasNoDnsName() throws Exception {
        KeyPair pair = ecKeyPair();
        X509Certificate certificate = selfSigned(pair, List.of(ip("100.101.102.103"), ip("10.1.2.3")));

        TlsMaterial material = TlsMaterial.read(write(pem(certificate), pkcs8(pair.getPrivate())), clockAt(NOW));

        assertThat(material.addressName()).isEqualTo("100.101.102.103");
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateWithoutASubjectAlternativeName() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of())), pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(), files.certificate() + ": certificate has no subject alternative name");
    }

    @Test
    @Tag("TLS-3")
    void refusesASubjectAlternativeNameWithoutADnsNameOrAnIpAddress() throws Exception {
        KeyPair pair = ecKeyPair();
        GeneralName uri = new GeneralName(GeneralName.uniformResourceIdentifier, "https://example.com/");
        TlsFiles files = write(pem(selfSigned(pair, List.of(uri))), pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(),
                files.certificate() + ": subject alternative name has no DNS name or IP address");
    }

    @Test
    @Tag("TLS-3")
    void refusesAMissingCertificateFile() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));
        Files.delete(files.certificate());

        assertRefused(files, files.certificate(), files.certificate() + ": file does not exist");
    }

    @Test
    @Tag("TLS-3")
    void refusesAMissingKeyFile() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));
        Files.delete(files.privateKey());

        assertRefused(files, files.privateKey(), files.privateKey() + ": file does not exist");
    }

    @Test
    @Tag("TLS-3")
    void refusesAnUnreadableCertificateFile() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));
        Files.setPosixFilePermissions(files.certificate(), Set.of());
        try {
            assertRefused(files, files.certificate(), files.certificate() + ": cannot be read");
        } finally {
            Files.setPosixFilePermissions(files.certificate(), PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @Tag("TLS-3")
    void refusesAnUnreadableKeyFile() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));
        Files.setPosixFilePermissions(files.privateKey(), Set.of());
        try {
            assertRefused(files, files.privateKey(), files.privateKey() + ": cannot be read");
        } finally {
            Files.setPosixFilePermissions(files.privateKey(), PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateFileThatIsNotPem() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write("just some text\n", pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(), files.certificate() + ": not a PEM certificate");
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateInDerForm() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write("", pkcs8(pair.getPrivate()));
        Files.write(files.certificate(), selfSigned(pair, List.of(dns(NODE))).getEncoded());

        assertRefused(files, files.certificate(), files.certificate() + ": not a PEM certificate");
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateBlockThatIsNotBase64() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write("-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----\n",
                pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(), files.certificate() + ": not a PEM certificate");
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateBlockThatHoldsNoCertificate() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(block("CERTIFICATE", new byte[] {48, 3, 2, 1, 1}), pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(), files.certificate() + ": not a PEM certificate");
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateFileThatHoldsOnlyAKey() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pkcs8(pair.getPrivate()), pkcs8(pair.getPrivate()));

        assertRefused(files, files.certificate(), files.certificate() + ": not a PEM certificate");
    }

    @Test
    @Tag("TLS-3")
    void refusesAKeyFileThatIsNotPem() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), "just some text\n");

        assertRefused(files, files.privateKey(), files.privateKey() + ": not a PEM private key");
    }

    @Test
    @Tag("TLS-3")
    void refusesAKeyFileThatHoldsOnlyACertificate() throws Exception {
        KeyPair pair = ecKeyPair();
        String certificate = pem(selfSigned(pair, List.of(dns(NODE))));
        TlsFiles files = write(certificate, certificate);

        assertRefused(files, files.privateKey(), files.privateKey() + ": not a PEM private key");
    }

    @Test
    @Tag("TLS-3")
    void refusesATruncatedKeyWithoutQuotingIt() throws Exception {
        KeyPair pair = ecKeyPair();
        String key = sec1(pair);
        // A truncated key keeps real key bytes, which must not reach the message.
        String truncated = key.substring(0, key.length() / 2) + "\n-----END EC PRIVATE KEY-----\n";
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), truncated);

        String message = assertRefused(files, files.privateKey(), files.privateKey() + ": not a PEM private key");

        for (String line : key.lines().toList()) {
            if (!line.startsWith("-----")) {
                assertThat(message).doesNotContain(line.substring(0, 16));
            }
        }
    }

    @Test
    @Tag("TLS-3")
    void refusesAKeyOfAnUnsupportedAlgorithm() throws Exception {
        KeyPair pair = ecKeyPair();
        PrivateKey ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(ed25519));

        assertRefused(files, files.privateKey(), files.privateKey() + ": unsupported private key algorithm");
    }

    @Test
    @Tag("TLS-3")
    void refusesAKeyOfAnotherCertificate() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(ecKeyPair().getPrivate()));

        assertRefused(files, files.privateKey(), files.privateKey() + ": does not belong to " + files.certificate());
    }

    @Test
    @Tag("TLS-3")
    void refusesAnRsaKeyForAnEcCertificate() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs1(rsaKeyPair().getPrivate()));

        assertRefused(files, files.privateKey(), files.privateKey() + ": does not belong to " + files.certificate());
    }

    @Test
    @Tag("TLS-3")
    void refusesAnExpiredCertificate() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));

        assertThat(TlsMaterial.read(files, clockAt(NOT_AFTER.minusSeconds(1))).addressName()).isEqualTo(NODE);
        assertRefused(files, files.certificate(), clockAt(NOT_AFTER),
                files.certificate() + ": expired on " + NOT_AFTER);
    }

    @Test
    @Tag("TLS-3")
    void refusesACertificateThatIsNotValidYet() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));

        assertThat(TlsMaterial.read(files, clockAt(NOT_BEFORE)).addressName()).isEqualTo(NODE);
        assertRefused(files, files.certificate(), clockAt(NOT_BEFORE.minusSeconds(1)),
                files.certificate() + ": not valid until " + NOT_BEFORE);
    }

    @Test
    @Tag("TLS-3")
    void readsWithTheSystemClock() throws Exception {
        KeyPair pair = ecKeyPair();
        Instant now = Instant.now();
        X509Certificate certificate = certificate(pair.getPublic(), "CN=" + NODE, pair.getPrivate(), "CN=" + NODE,
                List.of(dns(NODE)), now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), false);

        assertThat(TlsMaterial.read(write(pem(certificate), pkcs8(pair.getPrivate()))).addressName()).isEqualTo(NODE);
    }

    @Test
    void keepsThePrivateKeyOutOfToString() throws Exception {
        KeyPair pair = ecKeyPair();
        TlsFiles files = write(pem(selfSigned(pair, List.of(dns(NODE)))), pkcs8(pair.getPrivate()));
        String key = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());

        String text = TlsMaterial.read(files, clockAt(NOW)).toString();

        assertThat(text).contains("privateKey=<hidden>", "addressName=" + NODE, "notAfter=" + NOT_AFTER)
                .doesNotContain(key.substring(key.length() - 40));
    }

    private String assertRefused(TlsFiles files, Path file, String message) {
        return assertRefused(files, file, clockAt(NOW), message);
    }

    private static String assertRefused(TlsFiles files, Path file, Clock clock, String message) {
        Throwable thrown = catchThrowable(() -> TlsMaterial.read(files, clock));
        assertThat(thrown).isInstanceOf(TlsMaterial.UnusableFileException.class).hasMessage(message).hasNoCause();
        assertThat(((TlsMaterial.UnusableFileException) thrown).file()).isEqualTo(file);
        return thrown.getMessage();
    }

    private TlsFiles write(String certificate, String key) throws Exception {
        Path certificateFile = temp.resolve("node.crt");
        Path keyFile = temp.resolve("node.key");
        Files.writeString(certificateFile, certificate, StandardCharsets.US_ASCII);
        Files.writeString(keyFile, key, StandardCharsets.US_ASCII);
        return new TlsFiles(certificateFile, keyFile);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static GeneralName dns(String name) {
        return new GeneralName(GeneralName.dNSName, name);
    }

    private static GeneralName ip(String address) {
        return new GeneralName(GeneralName.iPAddress, address);
    }

    private static X509Certificate selfSigned(KeyPair pair, List<GeneralName> names) throws Exception {
        return certificate(pair.getPublic(), "CN=" + NODE, pair.getPrivate(), "CN=" + NODE, names, NOT_BEFORE,
                NOT_AFTER, false);
    }

    private static X509Certificate certificate(PublicKey subjectKey, String subject, PrivateKey issuerKey,
            String issuer, List<GeneralName> names, Instant notBefore, Instant notAfter, boolean authority)
            throws Exception {
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(issuer),
                BigInteger.valueOf(System.nanoTime()), Date.from(notBefore), Date.from(notAfter),
                new X500Name(subject), subjectKey)
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
        if (!names.isEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(names.toArray(GeneralName[]::new)));
        }
        String signature = issuerKey.getAlgorithm().equals("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder(signature).build(issuerKey)));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** The secret number of a key: a re-encoded key may differ in bytes but not in this. */
    private static BigInteger secret(PrivateKey key) {
        return key instanceof java.security.interfaces.ECPrivateKey ec ? ec.getS()
                : ((RSAPrivateKey) key).getPrivateExponent();
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return block("CERTIFICATE", certificate.getEncoded());
    }

    private static String pkcs8(PrivateKey key) {
        return block("PRIVATE KEY", key.getEncoded());
    }

    /** {@code RSA PRIVATE KEY}, as {@code openssl genrsa -traditional} writes it. */
    private static String pkcs1(PrivateKey key) throws Exception {
        return block("RSA PRIVATE KEY", PrivateKeyInfo.getInstance(key.getEncoded()).parsePrivateKey()
                .toASN1Primitive().getEncoded());
    }

    /** {@code EC PRIVATE KEY} with the curve and the public key, as {@code openssl ec} writes it. */
    private static String sec1(KeyPair pair) throws Exception {
        PrivateKeyInfo info = PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded());
        BigInteger secret = ECPrivateKey.getInstance(info.parsePrivateKey()).getKey();
        ECPrivateKey key = new ECPrivateKey(256, secret,
                SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()).getPublicKeyData(),
                info.getPrivateKeyAlgorithm().getParameters());
        return block("EC PRIVATE KEY", key.getEncoded());
    }

    private static String block(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
