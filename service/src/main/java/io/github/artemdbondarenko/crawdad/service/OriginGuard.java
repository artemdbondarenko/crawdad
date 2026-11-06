package io.github.artemdbondarenko.crawdad.service;

/**
 * Decides whether a WebSocket handshake comes from the page the service itself serves. Only an
 * exact match passes: {@code Host} must be the service address and {@code Origin} must be
 * {@code http://} plus that address. Other names of the same machine, such as {@code localhost}
 * for {@code 127.0.0.1}, are rejected: the owner opens the address the service prints.
 */
public final class OriginGuard {

    private final String authority;

    /** {@code authority} is the address the service listens on, as {@code host:port}. */
    public OriginGuard(String authority) {
        if (authority == null || !authority.contains(":")) {
            throw new IllegalArgumentException("Expected host:port, got: " + authority);
        }
        this.authority = authority;
    }

    public boolean allows(String origin, String host) {
        return authority.equalsIgnoreCase(host) && ("http://" + authority).equalsIgnoreCase(origin);
    }
}
