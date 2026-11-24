package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.service.log.LogText;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the service listens. Without {@code --host} only on {@code 127.0.0.1}; {@code --host} names one
 * private network address, never loopback, all interfaces or a public address. {@code tls} holds the
 * owner's certificate files for that address; empty means the service issues its own certificate.
 */
public record ServiceConfig(String host, int port, Optional<TlsFiles> tls) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8460;

    /** The options of the service, in the order its help names them. */
    static final List<String> OPTIONS = List.of("--host", "--port", "--tls-cert", "--tls-key");

    /**
     * Options the service refuses to start with, told in one line ({@code START-1}): a host it does not serve
     * ({@code NET-3}, {@code NET-5}) or certificate files without their pair or without {@code --host}
     * ({@code TLS-2}). Exit code {@code 1}.
     */
    public static class RefusedOptionException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        RefusedOptionException(String message) {
            super(message);
        }

        RefusedOptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Arguments the service does not take: not an option, an option without a value or given twice, a
     * {@code --port} that is not {@code 0}–{@code 65535}. The start exits with code {@code 2} ({@code START-3}),
     * unlike a value the service takes but cannot serve.
     */
    public static final class WrongOptionException extends RefusedOptionException {

        private static final long serialVersionUID = 1L;

        WrongOptionException(String message) {
            super(message);
        }
    }

    /** PEM files of the certificate and its private key, such as those written by {@code tailscale cert}. */
    public record TlsFiles(Path certificate, Path privateKey) {
        public TlsFiles {
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(privateKey, "privateKey");
        }
    }

    /** Turns a host name into its addresses; tests pass a fake one instead of asking DNS. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public ServiceConfig {
        if (host == null || host.isBlank()) {
            throw new RefusedOptionException("The host must not be empty");
        }
        // Only IPv6 addresses contain a colon; the address and the Origin check would need brackets.
        if (host.contains(":")) {
            throw new RefusedOptionException(
                    "IPv6 addresses are not supported yet: " + host + "; use an IPv4 address or a host name");
        }
        if (port < 0 || port > 65535) {
            throw new RefusedOptionException("The port must be between 0 and 65535: " + port);
        }
        Objects.requireNonNull(tls, "tls");
    }

    public ServiceConfig(String host, int port) {
        this(host, port, Optional.empty());
    }

    /**
     * Reads {@code --host <address>}, {@code --port <number>} (0 picks any free port) and
     * {@code --tls-cert <file> --tls-key <file>}, which go together and need {@code --host}.
     */
    public static ServiceConfig fromArgs(String... args) {
        return fromArgs(InetAddress::getAllByName, args);
    }

    static ServiceConfig fromArgs(HostResolver resolver, String... args) {
        Map<String, String> given = options(args);
        String host = given.get("--host");
        int port = given.containsKey("--port") ? parsePort(given.get("--port")) : DEFAULT_PORT;
        Path certificate = given.containsKey("--tls-cert") ? Path.of(given.get("--tls-cert")) : null;
        Path privateKey = given.containsKey("--tls-key") ? Path.of(given.get("--tls-key")) : null;
        if ((certificate == null) != (privateKey == null)) {
            throw new RefusedOptionException(
                    "--tls-cert and --tls-key go together: set both or neither");
        }
        Optional<TlsFiles> tls = certificate == null ? Optional.empty()
                : Optional.of(new TlsFiles(certificate, privateKey));
        if (host == null) {
            if (tls.isPresent()) {
                throw new RefusedOptionException("--tls-cert and --tls-key are for the network address; "
                        + "set it with --host. 127.0.0.1 is always served over plain HTTP");
            }
            return new ServiceConfig(DEFAULT_HOST, port);
        }
        ServiceConfig config = new ServiceConfig(host, port, tls);
        requirePrivateNetworkAddress(host, resolver);
        return config;
    }

    private static void requirePrivateNetworkAddress(String host, HostResolver resolver) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new RefusedOptionException("Cannot resolve --host " + host, e);
        }
        boolean hasIpv4 = false;
        for (InetAddress address : addresses) {
            // IPv6 is not supported yet; a Tailscale name also resolves to an IPv6 address, which is skipped.
            if (!(address instanceof Inet4Address)) {
                continue;
            }
            hasIpv4 = true;
            String ip = address.getHostAddress();
            String shown = ip.equals(host) ? host : host + " (" + ip + ")";
            if (address.isLoopbackAddress()) {
                throw new RefusedOptionException("--host " + shown + " is a loopback address. The service "
                        + "always serves 127.0.0.1 over plain HTTP; --host is for a network address, "
                        + "which is served over TLS");
            }
            if (address.isAnyLocalAddress()) {
                throw new RefusedOptionException("--host " + shown + " would listen on all interfaces; "
                        + "use the address of one private network interface, such as your VPN address");
            }
            if (!isPrivate(address)) {
                throw new RefusedOptionException("--host " + shown + " is not a private address, and the "
                        + "service never listens on the internet. Use an address from 10.0.0.0/8, "
                        + "172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10 or 169.254.0.0/16");
            }
        }
        if (!hasIpv4) {
            throw new RefusedOptionException("--host " + host + " has no IPv4 address; "
                    + "IPv6 addresses are not supported yet");
        }
    }

    /** RFC 1918 ranges, carrier-grade NAT (100.64.0.0/10, used by Tailscale) and link-local. */
    private static boolean isPrivate(InetAddress address) {
        byte[] b = address.getAddress();
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254);
    }

    /**
     * The options by name, each with its value. An argument that is not an option, an option without a value
     * or given twice refuses the start ({@code START-3}); a value that is the name of an option counts as missing,
     * so that {@code --host --port 8461} does not take {@code --port} for the host.
     */
    private static Map<String, String> options(String... args) {
        Map<String, String> given = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (!OPTIONS.contains(name)) {
                throw new WrongOptionException(LogText.safe(name) + " is not an option of the service; its options are "
                        + String.join(", ", OPTIONS));
            }
            if (i + 1 >= args.length || OPTIONS.contains(args[i + 1])) {
                throw new WrongOptionException(name + " needs a value");
            }
            if (given.putIfAbsent(name, args[i + 1]) != null) {
                throw new WrongOptionException(name + " is given more than once; give it once");
            }
        }
        return given;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // Told below, the same as a number out of range.
        }
        throw new WrongOptionException("--port must be a whole number from 0 to 65535, not " + LogText.safe(value));
    }
}
