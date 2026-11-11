package io.github.artemdbondarenko.crawdad.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent.Delta;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent.End;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent.Start;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FakeAgentAdapterTest {

    @Test
    @Tag("FAKE-3")
    void theFakeAgentStartsNoProcessUsesNoKeyAndMakesNoNetworkCalls() throws Exception {
        // Read from its bytecode: every class it refers to, none of processes, the network or a key.
        byte[] bytes;
        try (var in = FakeAgentAdapter.class.getResourceAsStream("FakeAgentAdapter.class")) {
            bytes = in.readAllBytes();
        }
        List<String> referenced = new ArrayList<>();
        for (PoolEntry entry : ClassFile.of().parse(bytes).constantPool()) {
            if (entry instanceof ClassEntry type) {
                referenced.add(type.asInternalName());
            }
        }

        assertThat(referenced).isNotEmpty().noneMatch(name -> name.equals("java/lang/ProcessBuilder")
                || name.equals("java/lang/Process") || name.equals("java/lang/Runtime")
                || name.startsWith("java/net/") || name.startsWith("java/nio/channels/")
                || name.startsWith("java/security/") || name.startsWith("javax/crypto/")
                || name.startsWith("javax/net/"));
    }

    private final FakeAgentAdapter adapter = new FakeAgentAdapter();

    @Test
    @Tag("MSG-3")
    void givesEverySessionItsOwnId() {
        String first = adapter.startSession();
        String second = adapter.startSession();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @Tag("MSG-4")
    @Tag("FAKE-2")
    void answersWithAStreamOfStartPartsAndEnd() {
        List<AgentEvent> events = send(adapter.startSession(), "hello world");

        assertThat(events.getFirst()).isInstanceOf(Start.class);
        assertThat(events.getLast()).isInstanceOf(End.class);
        assertThat(events.subList(1, events.size() - 1)).hasSizeGreaterThanOrEqualTo(2).allMatch(Delta.class::isInstance);
        assertThat(events).extracting(AgentEvent::messageId).containsOnly(events.getFirst().messageId());
    }

    @Test
    @Tag("FAKE-1")
    void theAssembledAnswerRepeatsTheUserText() {
        assertThat(answer(send(adapter.startSession(), "hello world"))).isEqualTo("You said: hello world");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hi", "  two  spaces  ", "line\nbreak", "tab\there"})
    @Tag("FAKE-1")
    @Tag("FAKE-2")
    void keepsTheUserTextExactlyAndSplitsEvenOneWord(String text) {
        List<AgentEvent> events = send(adapter.startSession(), text);

        assertThat(answer(events)).isEqualTo("You said: " + text);
        assertThat(events).filteredOn(Delta.class::isInstance).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("MSG-4")
    void everyAnswerHasItsOwnMessageId() {
        String session = adapter.startSession();

        String first = send(session, "one").getFirst().messageId();
        String second = send(session, "two").getFirst().messageId();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void rejectsAnUnknownSession() {
        assertThatThrownBy(() -> adapter.sendMessage("no-such-session", "hello", event -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-session");
    }

    @Test
    void rejectsAnEmptyMessage() {
        String session = adapter.startSession();

        assertThatThrownBy(() -> adapter.sendMessage(session, "", event -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<AgentEvent> send(String session, String text) {
        List<AgentEvent> events = new ArrayList<>();
        adapter.sendMessage(session, text, events::add);
        return events;
    }

    private static String answer(List<AgentEvent> events) {
        return events.stream()
                .filter(Delta.class::isInstance)
                .map(event -> ((Delta) event).text())
                .collect(Collectors.joining());
    }
}
