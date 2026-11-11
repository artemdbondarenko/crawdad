package io.github.artemdbondarenko.crawdad.service.device;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The WebSocket connections each device has open, so that {@link DeviceRegistry#revoke} can close them. A
 * handshake registers its connection under the {@link DeviceRegistry}'s monitor, together with the
 * key check that admits it; the connection unregisters itself when it closes. Thread-safe.
 */
public final class ConnectionRegistry {

    /** One connection as revocation sees it. */
    public interface Connection {

        /** Closes the connection with this status; one not open yet closes as soon as it opens. */
        void close(int statusCode, String reason);
    }

    private final Map<String, Set<Connection>> byDevice = new HashMap<>();

    public synchronized void register(String deviceId, Connection connection) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(connection, "connection");
        byDevice.computeIfAbsent(deviceId, id -> new LinkedHashSet<>()).add(connection);
    }

    /** Forgets the connection; nothing happens if it is not registered under the device. */
    public synchronized void unregister(String deviceId, Connection connection) {
        Set<Connection> open = byDevice.get(deviceId);
        if (open == null) {
            return;
        }
        open.remove(connection);
        if (open.isEmpty()) {
            byDevice.remove(deviceId);
        }
    }

    /** A copy of the device's connections in the order they registered, safe to close while iterating. */
    public synchronized List<Connection> connectionsOf(String deviceId) {
        return List.copyOf(byDevice.getOrDefault(deviceId, Set.of()));
    }
}
