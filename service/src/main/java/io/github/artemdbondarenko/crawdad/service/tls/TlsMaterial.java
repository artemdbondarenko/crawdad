package io.github.artemdbondarenko.crawdad.service.tls;

import io.github.artemdbondarenko.crawdad.service.ServiceConfig.TlsFiles;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;

/**
 * A certificate and its private key read from PEM files, such as those written by {@code tailscale cert}.
 * The certificate file holds the chain, the server certificate first; the key may be PKCS#8, SEC1 EC or
 * PKCS#1 RSA. {@link #read} accepts the pair only when the key belongs to the server certificate and the
 * certificate is valid now; every refusal names the file and what is wrong with it.
 */
public record TlsMaterial(PrivateKey privateKey, List<X509Certificate> chain, String addressName) {

    /** The alias of the entry in {@link #keyStore}, the same as for the service's own certificate. */
    public static final String ALIAS = LocalCa.ServerCertificate.ALIAS;

    private static final int DNS_NAME = 2;
    private static final int IP_ADDRESS = 7;
    private static final Map<ASN1ObjectIdentifier, String> KEY_ALGORITHMS = Map.of(
            X9ObjectIdentifiers.id_ecPublicKey, "EC",
            PKCSObjectIdentifiers.rsaEncryption, "RSA");
    private static final Map<String, String> SIGNATURES = Map.of("EC", "SHA256withECDSA", "RSA", "SHA256withRSA");

    /** A certificate or key file that cannot serve; the message is {@code <path>: <problem>}. */
    public static final class UnusableFileException extends Exception {
        private final transient Path file;

        private UnusableFileException(Path file, String problem) {
            super(file + ": " + problem);
            this.file = file;
        }

        /** The file the problem is in. */
        public Path file() {
            return file;
        }
    }

    public TlsMaterial {
        Objects.requireNonNull(privateKey, "privateKey");
        chain = List.copyOf(chain);
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("chain is empty");
        }
        Objects.requireNonNull(addressName, "addressName");
    }

    /** Reads the files and checks them against the system clock. */
    public static TlsMaterial read(TlsFiles files) throws UnusableFileException {
        return read(files, Clock.systemUTC());
    }

    /** Reads the files and checks that the pair is valid at the instant of {@code clock}. */
    public static TlsMaterial read(TlsFiles files, Clock clock) throws UnusableFileException {
        Path certificateFile = files.certificate();
        Path keyFile = files.privateKey();
        List<X509Certificate> chain = readChain(certificateFile);
        PrivateKey key = readKey(keyFile);
        X509Certificate server = chain.getFirst();
        if (!belongTogether(key, server.getPublicKey())) {
            throw new UnusableFileException(keyFile, "does not belong to " + certificateFile);
        }
        Instant now = clock.instant();
        Instant notBefore = server.getNotBefore().toInstant();
        Instant notAfter = server.getNotAfter().toInstant();
        if (now.isBefore(notBefore)) {
            throw new UnusableFileException(certificateFile, "not valid until " + notBefore);
        }
        if (!now.isBefore(notAfter)) {
            throw new UnusableFileException(certificateFile, "expired on " + notAfter);
        }
        return new TlsMaterial(key, chain, addressName(certificateFile, server));
    }

    /** The end of the server certificate's validity. */
    public Instant notAfter() {
        return chain.getFirst().getNotAfter().toInstant();
    }

    /** An in-memory PKCS#12 key store with one entry, {@link #ALIAS}, protected by the password. */
    public KeyStore keyStore(char[] password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, privateKey, password, chain.toArray(Certificate[]::new));
        return store;
    }

    /** Keeps the private key out of logs and error messages. */
    @Override
    public String toString() {
        return "TlsMaterial[privateKey=<hidden>, subject=" + chain.getFirst().getSubjectX500Principal().getName()
                + ", addressName=" + addressName + ", notAfter=" + notAfter() + "]";
    }

    private static List<X509Certificate> readChain(Path file) throws UnusableFileException {
        List<X509Certificate> chain = new ArrayList<>();
        try (PEMParser parser = parser(file)) {
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            for (Object block = parser.readObject(); block != null; block = parser.readObject()) {
                if (block instanceof X509CertificateHolder holder) {
                    chain.add(converter.getCertificate(holder));
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new UnusableFileException(file, "not a PEM certificate");
        }
        if (chain.isEmpty()) {
            throw new UnusableFileException(file, "not a PEM certificate");
        }
        return chain;
    }

    /** Parse errors are not passed on as causes: their messages could quote bytes of the key. */
    private static PrivateKey readKey(Path file) throws UnusableFileException {
        PrivateKeyInfo info = null;
        try (PEMParser parser = parser(file)) {
            // openssl may put an EC PARAMETERS block before the key; anything but a key is skipped.
            for (Object block = parser.readObject(); block != null && info == null; block = parser.readObject()) {
                if (block instanceof PrivateKeyInfo pkcs8) {
                    info = pkcs8;
                } else if (block instanceof PEMKeyPair traditional) {
                    info = traditional.getPrivateKeyInfo();
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new UnusableFileException(file, "not a PEM private key");
        }
        if (info == null) {
            throw new UnusableFileException(file, "not a PEM private key");
        }
        String algorithm = KEY_ALGORITHMS.get(info.getPrivateKeyAlgorithm().getAlgorithm());
        if (algorithm == null) {
            throw new UnusableFileException(file, "unsupported private key algorithm");
        }
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new UnusableFileException(file, "not a PEM private key");
        }
    }

    /** Reads the whole file first, so a missing or unreadable file is told apart from bad content. */
    private static PEMParser parser(Path file) throws UnusableFileException {
        try {
            // ISO-8859-1 maps every byte, so a binary file reaches the parser and is refused as not PEM.
            return new PEMParser(new StringReader(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)));
        } catch (NoSuchFileException e) {
            throw new UnusableFileException(file, "file does not exist");
        } catch (IOException e) {
            throw new UnusableFileException(file, "cannot be read");
        }
    }

    /** Signs a probe with the key and checks it with the certificate's public key. */
    private static boolean belongTogether(PrivateKey key, PublicKey publicKey) {
        if (!key.getAlgorithm().equals(publicKey.getAlgorithm())) {
            return false;
        }
        try {
            byte[] probe = "crawdad certificate key check".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance(SIGNATURES.get(key.getAlgorithm()));
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(SIGNATURES.get(key.getAlgorithm()));
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The first DNS name of the subject alternative name, or else its first IP address. */
    private static String addressName(Path file, X509Certificate server) throws UnusableFileException {
        Collection<List<?>> names;
        try {
            names = server.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            throw new UnusableFileException(file, "cannot read the subject alternative name");
        }
        if (names == null || names.isEmpty()) {
            throw new UnusableFileException(file, "certificate has no subject alternative name");
        }
        String address = null;
        for (List<?> name : names) {
            int type = (Integer) name.get(0);
            if (type == DNS_NAME) {
                return (String) name.get(1);
            }
            if (type == IP_ADDRESS && address == null) {
                address = (String) name.get(1);
            }
        }
        if (address == null) {
            throw new UnusableFileException(file, "subject alternative name has no DNS name or IP address");
        }
        return address;
    }
}
