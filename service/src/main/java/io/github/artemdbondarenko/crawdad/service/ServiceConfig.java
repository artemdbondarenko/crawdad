package io.github.artemdbondarenko.crawdad.service;

/**
 * Where the service listens. By default only on localhost: the service has no authentication yet,
 * so it must not be reachable from the network unless the owner asks for it.
 */
public record ServiceConfig(String host, int port) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8460;

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
    }

    /** Reads {@code --host <address>} and {@code --port <number>}; port 0 picks any free port. */
    public static ServiceConfig fromArgs(String... args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args[i + 1];
            switch (name) {
                case "--host" -> host = value;
                case "--port" -> port = parsePort(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + name);
            }
        }
        return new ServiceConfig(host, port);
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The port must be a number: " + value, e);
        }
    }
}
