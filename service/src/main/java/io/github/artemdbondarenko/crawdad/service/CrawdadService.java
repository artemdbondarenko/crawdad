package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.service.CrawdadServer.CannotListenException;
import io.github.artemdbondarenko.crawdad.service.CrawdadServer.NetworkCertificate;
import io.github.artemdbondarenko.crawdad.service.ServiceConfig.RefusedOptionException;
import io.github.artemdbondarenko.crawdad.service.ServiceConfig.WrongOptionException;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.cli.Cli;
import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.UnusableRegistryException;
import io.github.artemdbondarenko.crawdad.service.log.LogLine;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory.NoDataHomeException;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock;
import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock.AlreadyRunningException;
import io.github.artemdbondarenko.crawdad.service.storage.StartFootprint;
import io.github.artemdbondarenko.crawdad.service.tls.CertificateWatcher;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa.UnusableRootException;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial.UnusableFileException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Entry point of the service:
 * {@code java -jar crawdad-service.jar [--host <address>] [--port <number>] [--tls-cert <file> --tls-key <file>]},
 * and of the owner's commands {@code pair}, {@code devices}, {@code revoke} and {@code tls export-ca}
 * when the first argument names one ({@link Cli}).
 */
public final class CrawdadService {

    /** Classpath directory with the files of the web client. */
    static final String CLIENT_FILES = "static/";

    private static final Logger LOG = System.getLogger(CrawdadService.class.getName());

    private CrawdadService() {}

    public static void main(String[] args) throws Exception {
        if (Cli.handles(args)) {
            System.exit(Cli.run(args, Cli.dataDirectory(DataDirectory::fromEnvironment), startCommand(), System.out,
                    System.err));
        }
        // Every entry on one line, with no stack trace, from the first one on (LOG-3).
        LogLine.install();
        Start start = start(args, DataDirectory::fromEnvironment, System.out, System.err);
        if (start.server().isEmpty()) {
            System.exit(start.exitCode());
        }
        start.server().get().join();
    }

    /** The running server, or the exit code of a start that was refused. */
    record Start(Optional<CrawdadServer> server, int exitCode) {

        static Start refused(int exitCode) {
            return new Start(Optional.empty(), exitCode);
        }
    }

    /** The exit code of wrong options ({@code START-3}). */
    static final int WRONG_OPTIONS = 2;

    /** The exit code of every other refusal. */
    static final int REFUSED = 1;

    static Start start(String[] args, DataDirectory data, PrintStream out, PrintStream err) {
        return start(args, () -> data, out, err);
    }

    /**
     * Starts the service, or tells in one line on {@code err} why it cannot, with no stack trace, and gives the
     * exit code ({@code START-1}). A reason of the table of refusals is the owner's to fix, and the line is all
     * they need; any other failure also goes to the log with its causes ({@code START-8}).
     */
    static Start start(String[] args, Supplier<DataDirectory> data, PrintStream out, PrintStream err) {
        try {
            return new Start(Optional.of(run(args, data, out)), 0);
        } catch (WrongOptionException e) {
            err.println(e.getMessage());
            return Start.refused(WRONG_OPTIONS);
        } catch (CannotListenException e) {
            if (e.reason() == CannotListenException.Reason.OTHER) {
                return failed(e, err);
            }
            err.println(e.getMessage());
            return Start.refused(REFUSED);
        } catch (AlreadyRunningException | RefusedOptionException | NoDataHomeException | UnusableRootException
                | UnusableFileException | UnusableRegistryException | ControlSocket.CannotOpenException e) {
            err.println(e.getMessage());
            return Start.refused(REFUSED);
        } catch (Exception e) {
            return failed(e, err);
        }
    }

    /** A failure the service did not expect: one line for the owner, the causes in the log ({@code START-8}). */
    private static Start failed(Exception failure, PrintStream err) {
        LOG.log(Level.ERROR, "Crawdad could not start", failure);
        String what = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        err.println("Crawdad could not start: " + LogText.safe(what) + "; the service log has its causes");
        return Start.refused(REFUSED);
    }

    /** Stops a server that started before the start failed; its control socket goes with it. */
    private static void stopAfterFailedStart(CrawdadServer server, Exception failure) {
        try {
            server.stop();
        } catch (Exception e) {
            failure.addSuppressed(e);
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
        return run(args, () -> data, out);
    }

    /** Reads the options before it looks for the data directory: wrong options are found before anything else. */
    private static CrawdadServer run(String[] args, Supplier<DataDirectory> dataDirectory, PrintStream out)
            throws Exception {
        ServiceConfig config = ServiceConfig.fromArgs(args);
        DataDirectory data = dataDirectory.get();
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
     * Takes the instance lock before anything in {@code data} is opened, then opens the control socket before
     * the server listens on any address, so a socket that cannot be opened fails the start with nothing listened
     * on ({@code CMD-6}). The socket answers once the server has started, before the addresses are printed. It
     * goes before the lock when the server stops. A start that fails takes away every file and directory it
     * created ({@code START-2}): the files while it still holds the lock, so that no other start adds its own
     * meanwhile, the directories after.
     */
    private static CrawdadServer run(ServiceConfig config, CertificateSource certificateSource, DataDirectory data,
            PrintStream out) throws Exception {
        Optional<StartFootprint> footprint = StartFootprint.of(data);
        InstanceLock lock;
        try {
            lock = InstanceLock.acquire(data.runtime());
        } catch (AlreadyRunningException e) {
            // The files are the running service's: none of them is this start's to take away.
            throw e;
        } catch (IOException | RuntimeException e) {
            footprint.ifPresent(StartFootprint::deleteAddedFiles);
            footprint.ifPresent(StartFootprint::deleteAddedDirectories);
            throw e;
        }
        CrawdadServer server = null;
        Optional<NetworkCertificate> certificate;
        try {
            certificate = certificateSource.get();
            DeviceRegistry devices = DeviceRegistry.open(data, Clock.systemUTC());
            ConnectionRegistry connections = new ConnectionRegistry();
            PairingCodes codes = new PairingCodes();
            ControlSocket control = ControlSocket.open(data.runtime(), codes, devices, connections);
            try {
                server = CrawdadServer.start(config, CLIENT_FILES, new FakeAgentAdapter(), certificate, devices,
                        connections, codes);
            } catch (Exception e) {
                control.close();
                throw e;
            }
            server.whenStopped(control::close);
            if (config.tls().isPresent()) {
                // Only files are renewed in place; the own root issues a fresh certificate at every start.
                NetworkCertificate served = certificate.orElseThrow();
                CertificateWatcher watcher = CertificateWatcher.start(config.tls().get(),
                        server.networkTls().orElseThrow(), served.addressName(),
                        served.chain().getFirst().getNotAfter().toInstant());
                server.whenStopped(watcher::close);
            }
            control.serve(new ControlSocket.ServiceUrls(server.address(), server.networkAddress()));
        } catch (Exception e) {
            if (server != null) {
                stopAfterFailedStart(server, e);
            }
            footprint.ifPresent(StartFootprint::deleteAddedFiles);
            lock.close();
            footprint.ifPresent(StartFootprint::deleteAddedDirectories);
            throw e;
        }
        server.whenStopped(lock::close);
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
