package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.CannotListenException;
import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.cli.Cli;
import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock.AlreadyRunningException;
import io.github.artemdbondarenko.crawdad.service.tls.CertificateWatcher;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa.UnusableRootException;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial.UnusableFileException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

/**
 * Entry point of the service:
 * {@code java -jar crawdad-service.jar [--host <address>] [--port <number>] [--tls-cert <file> --tls-key <file>]},
 * and of the owner's commands {@code pair}, {@code devices}, {@code revoke} and {@code tls export-ca}
 * when the first argument names one ({@link Cli}).
 */
public final class CrawdadService {

    /** Classpath directory with the files of the web client. */
    static final String CLIENT_FILES = "static/";

    private CrawdadService() {}

    public static void main(String[] args) throws Exception {
        if (Cli.handles(args)) {
            System.exit(Cli.run(args, Cli.dataDirectory(DataDirectory::fromEnvironment), startCommand(), System.out,
                    System.err));
        }
        Optional<CrawdadServer> server = start(args, DataDirectory.fromEnvironment(), System.out, System.err);
        if (server.isEmpty()) {
            System.exit(1);
        }
        server.get().join();
    }

    /**
     * Starts the service, or tells in one line on {@code err} why it cannot: it runs already, an option is
     * wrong, the certificate is unusable or an address cannot be listened on. Other failures are not the
     * owner's to fix and keep their stack trace.
     */
    static Optional<CrawdadServer> start(String[] args, DataDirectory data, PrintStream out, PrintStream err)
            throws Exception {
        try {
            return Optional.of(run(args, data, out));
        } catch (AlreadyRunningException | IllegalArgumentException | UnusableRootException
                | UnusableFileException | CannotListenException e) {
            err.println(e.getMessage());
            return Optional.empty();
        }
    }

    /** How to start the service from this jar, for the hint of a command that finds it not running. */
    static String startCommand() {
        try {
            Path jar = Path.of(CrawdadService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return "java -jar " + jar;
        } catch (URISyntaxException | RuntimeException e) {
            return "java -jar crawdad-service.jar";
        }
    }

    /** Starts the server with its files in {@code data} and tells the owner where to open it. */
    static CrawdadServer run(String[] args, DataDirectory data, PrintStream out) throws Exception {
        ServiceConfig config = ServiceConfig.fromArgs(args);
        return run(config, () -> NetworkCertificate.forConfig(config, () -> LocalCa.forThisMachine(data)), data, out);
    }

    /**
     * Starts the server with {@code certificate} for the network address. Without one, prints the loopback
     * address in one line; with one, both addresses and, for the own root, its fingerprint to compare on a device.
     * Certificate files are watched for renewal until the server stops. Devices are kept in {@code data}.
     */
    static CrawdadServer run(ServiceConfig config, Optional<NetworkCertificate> certificate, DataDirectory data,
            PrintStream out) throws Exception {
        return run(config, () -> certificate, data, out);
    }

    /** Finds the certificate of the network address once the instance lock is held. */
    @FunctionalInterface
    private interface CertificateSource {
        Optional<NetworkCertificate> get() throws Exception;
    }

    /**
     * Takes the instance lock before anything in {@code data} is opened, then starts the server and the
     * control socket on the same codes, devices and connections. The socket goes before the lock when the
     * server stops; a socket that cannot start stops the server and fails the start.
     */
    private static CrawdadServer run(ServiceConfig config, CertificateSource certificateSource, DataDirectory data,
            PrintStream out) throws Exception {
        InstanceLock lock = InstanceLock.acquire(data.runtime());
        CrawdadServer server;
        Optional<NetworkCertificate> certificate;
        try {
            certificate = certificateSource.get();
            DeviceRegistry devices = DeviceRegistry.open(data, Clock.systemUTC());
            ConnectionRegistry connections = new ConnectionRegistry();
            PairingCodes codes = new PairingCodes();
            server = CrawdadServer.start(config, CLIENT_FILES, new FakeAgentAdapter(), certificate, devices,
                    connections, codes);
            ControlSocket control;
            try {
                control = ControlSocket.start(data.runtime(), codes, devices, connections,
                        new ControlSocket.ServiceUrls(server.address(), server.networkAddress()));
            } catch (IOException | RuntimeException e) {
                server.stop();
                throw e;
            }
            server.whenStopped(control::close);
            server.whenStopped(lock::close);
        } catch (Exception e) {
            lock.close();
            throw e;
        }
        if (config.tls().isPresent()) {
            // Only files are renewed in place; the own root issues a fresh certificate at every start.
            NetworkCertificate served = certificate.orElseThrow();
            CertificateWatcher watcher = CertificateWatcher.start(config.tls().get(), server.networkTls().orElseThrow(),
                    served.addressName(), served.chain().getFirst().getNotAfter().toInstant());
            server.whenStopped(watcher::close);
        }
        String name = ServiceInfo.current().displayName();
        Optional<URI> network = server.networkAddress();
        if (network.isEmpty()) {
            out.println(name + " is running. Open " + server.address() + " in a browser.");
            return server;
        }
        out.println(name + " is running.");
        out.println("  On this machine: " + server.address());
        out.println("  On the network:  " + network.get());
        certificate.flatMap(NetworkCertificate::rootFingerprint)
                .ifPresent(fingerprint -> out.println("  Root certificate SHA-256: " + fingerprint));
        return server;
    }
}
