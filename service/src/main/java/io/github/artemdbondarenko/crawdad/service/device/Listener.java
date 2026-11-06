package io.github.artemdbondarenko.crawdad.service.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The connector a device was paired through. A key works only on the connector where it was issued:
 * one browser on {@code 127.0.0.1} and on the network address is two devices.
 */
public enum Listener {
    /** {@code 127.0.0.1}, served over HTTP. */
    @JsonProperty("loopback")
    LOOPBACK,
    /** The private network address given by {@code --host}, served over TLS. */
    @JsonProperty("network")
    NETWORK
}
