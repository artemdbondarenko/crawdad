package io.github.artemdbondarenko.crawdad.service;

import java.net.URI;

/**
 * Decides whether a request comes from the page one connector of the service serves. Only an exact
 * match passes: {@code Host} must be the connector's authority and {@code Origin} its scheme plus that
 * authority. The default port of the scheme is left out, as browsers do. Other names of the same
 * machine, such as {@code localhost} for {@code 127.0.0.1}, are rejected: the owner opens the address
 * the service prints.
 */
public final class OriginGuard {

    private final String scheme;
    private final String authority;

    /** {@code scheme} is {@code http} or {@code https}; {@code host} a name or an IPv4 address. */
    public OriginGuard(String scheme, String host, int port) {
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Expected http or https, got: " + scheme);
        }
        if (host == null || host.isEmpty() || host.contains(":")) {
            throw new IllegalArgumentException("Expected a host without a port, got: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Expected a port between 1 and 65535, got: " + port);
        }
        int defaultPort = scheme.equals("https") ? 443 : 80;
        this.scheme = scheme;
        this.authority = port == defaultPort ? host : host + ":" + port;
    }

    /** The address of the page, such as {@code https://laptop.example.ts.net:8460/}. */
    public URI address() {
        return URI.create(scheme + "://" + authority + "/");
    }

    /** Both headers are required and must match; for {@code /ws} and {@code POST /pair}. */
    public boolean allows(String origin, String host) {
        return authority.equalsIgnoreCase(host) && (scheme + "://" + authority).equalsIgnoreCase(origin);
    }

    /**
     * {@code Host} must match, {@code Origin} only when it is sent: browsers leave it out of a GET to
     * the page's own address. An empty or {@code null} {@code Origin} header counts as sent.
     */
    public boolean allowsWithoutOrigin(String origin, String host) {
        return origin == null ? authority.equalsIgnoreCase(host) : allows(origin, host);
    }
}
