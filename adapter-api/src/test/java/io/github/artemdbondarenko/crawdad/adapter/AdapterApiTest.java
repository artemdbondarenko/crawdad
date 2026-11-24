package io.github.artemdbondarenko.crawdad.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("ADAPTER-8")
class AdapterApiTest {

    @Test
    void describesVersionOne() {
        assertThat(AdapterApi.VERSION).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake", "echo", "a", "my-agent-2", "abcdefghijklmnopqrstuvwxyz012345"})
    void takesValidNames(String name) {
        assertThat(AdapterApi.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Echo", "2echo", "-echo", "echo_1", "echo agent", "abcdefghijklmnopqrstuvwxyz0123456", "écho"})
    void refusesInvalidNames(String name) {
        assertThat(AdapterApi.isValidName(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "42", "999999999"})
    void takesWholeNumbers(String version) {
        assertThat(AdapterApi.isValidVersion(version)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "01", "-1", "1.0", " 1", "v1", "1234567890"})
    void refusesOtherVersions(String version) {
        assertThat(AdapterApi.isValidVersion(version)).isFalse();
    }

    @Test
    void eventsKeepTheirMessageId() {
        assertThat(new AgentEvent.Start("m").messageId()).isEqualTo("m");
        assertThat(new AgentEvent.Delta("m", "text").text()).isEqualTo("text");
        assertThat(new AgentEvent.End("m").messageId()).isEqualTo("m");
    }
}
