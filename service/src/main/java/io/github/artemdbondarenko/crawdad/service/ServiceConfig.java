package io.github.artemdbondarenko.crawdad.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
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
            throw new IllegalArgumentException("The host must not be empty");
        }
        // Only IPv6 addresses contain a colon; the address and the Origin check would need brackets.
        if (host.contains(":")) {
            throw new IllegalArgumentException(
                    "IPv6 addresses are not supported yet: " + host + "; use an IPv4 address or a host name");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("The port must be between 0 and 65535: " + port);
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
        String host = null;
        int port = DEFAULT_PORT;
        Path certificate = null;
        Path privateKey = null;
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args[i + 1];
            switch (name) {
                case "--host" -> host = value;
                case "--port" -> port = parsePort(value);
                case "--tls-cert" -> certificate = Path.of(value);
                case "--tls-key" -> privateKey = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + name);
            }
        }
        if ((certificate == null) != (privateKey == null)) {
            throw new IllegalArgumentException(
                    "--tls-cert and --tls-key go together: set both or neither");
        }
        Optional<TlsFiles> tls = certificate == null ? Optional.empty()
                : Optional.of(new TlsFiles(certificate, privateKey));
        if (host == null) {
            if (tls.isPresent()) {
                throw new IllegalArgumentException("--tls-cert and --tls-key are for the network address; "
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
            throw new IllegalArgumentException("Cannot resolve --host " + host, e);
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
                throw new IllegalArgumentException("--host " + shown + " is a loopback address. The service "
                        + "always serves 127.0.0.1 over plain HTTP; --host is for a network address, "
                        + "which is served over TLS");
            }
            if (address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("--host " + shown + " would listen on all interfaces; "
                        + "use the address of one private network interface, such as your VPN address");
            }
            if (!isPrivate(address)) {
                throw new IllegalArgumentException("--host " + shown + " is not a private address, and the "
                        + "service never listens on the internet. Use an address from 10.0.0.0/8, "
                        + "172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10 or 169.254.0.0/16");
            }
        }
        if (!hasIpv4) {
            throw new IllegalArgumentException("--host " + host + " has no IPv4 address; "
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

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The port must be a number: " + value, e);
        }
    }
}
