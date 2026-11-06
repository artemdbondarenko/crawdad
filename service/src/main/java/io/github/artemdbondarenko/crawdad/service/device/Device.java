package io.github.artemdbondarenko.crawdad.service.device;

import java.time.Instant;
import java.util.Objects;

/**
 * One paired device as kept in {@code devices.json}. {@code keySha256} is the hex SHA-256 of the
 * device key, never the key itself, and is {@code null} once the device is revoked. The {@code last*}
 * fields describe the last successful handshake and are {@code null} until the first one.
 */
public record Device(
        String id,
        String name,
        String keySha256,
        Listener listener,
        Instant pairedAt,
        Instant lastSeen,
        String lastAddress,
        String lastUserAgent,
        Instant revokedAt) {

    public Device {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(pairedAt, "pairedAt");
    }

    public boolean active() {
        return revokedAt == null;
    }

    Device withLogin(Instant at, String address, String userAgent) {
        return new Device(id, name, keySha256, listener, pairedAt, at, address, userAgent, revokedAt);
    }

    Device withRevocation(Instant at) {
        return new Device(id, name, null, listener, pairedAt, lastSeen, lastAddress, lastUserAgent, at);
    }
}
