package io.github.artemdbondarenko.crawdad.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final Connection first = (statusCode, reason) -> {};
    private final Connection second = (statusCode, reason) -> {};
    private final Connection other = (statusCode, reason) -> {};

    @Test
    void keepsTheConnectionsOfEachDeviceInTheOrderTheyRegistered() {
        connections.register("laptop", first);
        connections.register("phone", other);
        connections.register("laptop", second);

        assertThat(connections.connectionsOf("laptop")).containsExactly(first, second);
        assertThat(connections.connectionsOf("phone")).containsExactly(other);
        assertThat(connections.connectionsOf("tablet")).isEmpty();
    }

    @Test
    void registersAConnectionOnce() {
        connections.register("laptop", first);
        connections.register("laptop", first);

        assertThat(connections.connectionsOf("laptop")).containsExactly(first);
    }

    @Test
    void forgetsAnUnregisteredConnectionOnly() {
        connections.register("laptop", first);
        connections.register("laptop", second);

        connections.unregister("laptop", first);

        assertThat(connections.connectionsOf("laptop")).containsExactly(second);
        connections.unregister("laptop", second);
        assertThat(connections.connectionsOf("laptop")).isEmpty();
    }

    @Test
    void ignoresAConnectionThatIsNotRegisteredUnderTheDevice() {
        connections.register("laptop", first);

        connections.unregister("phone", first);
        connections.unregister("laptop", other);

        assertThat(connections.connectionsOf("laptop")).containsExactly(first);
    }

    @Test
    void registersAgainAfterTheLastConnectionLeft() {
        connections.register("laptop", first);
        connections.unregister("laptop", first);

        connections.register("laptop", second);

        assertThat(connections.connectionsOf("laptop")).containsExactly(second);
    }

    @Test
    void givesACopyThatClosingConnectionsCannotChange() {
        connections.register("laptop", first);
        List<Connection> open = connections.connectionsOf("laptop");

        // A connection unregisters itself when it closes, possibly while revocation walks the list.
        connections.unregister("laptop", first);

        assertThat(open).containsExactly(first);
        assertThatThrownBy(() -> open.add(second)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void needsADeviceAndAConnection() {
        assertThatThrownBy(() -> connections.register(null, first)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> connections.register("laptop", null)).isInstanceOf(NullPointerException.class);
    }
}
