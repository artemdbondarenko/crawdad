package io.github.artemdbondarenko.crawdad.service.cli;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A self-signed root certificate that expired in 2021, as PEM; for a root the service would refuse. */
final class ExpiredCertificate {

    private ExpiredCertificate() {}

    static String pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=Crawdad root on an old machine");
        byte[] der = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(Instant.parse("2020-01-01T00:00:00Z")), Date.from(Instant.parse("2021-01-01T00:00:00Z")),
                name, pair.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()))
                .getEncoded();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
