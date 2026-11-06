package io.github.artemdbondarenko.crawdad.service.adapter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A stand-in for a real agent: no process, no key, no network. It answers {@code You said: <text>},
 * streamed word by word, so tests can check that the whole answer arrives intact and in order.
 */
public final class FakeAgentAdapter implements AgentAdapter {

    static final String ANSWER_PREFIX = "You said: ";

    /** Splits before every whitespace character, so the parts joined back give the exact text. */
    private static final Pattern PARTS = Pattern.compile("(?=\\s)");

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public String startSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.add(sessionId);
        return sessionId;
    }

    @Override
    public void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener) {
        if (!sessions.contains(sessionId)) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("The message is empty");
        }
        String messageId = UUID.randomUUID().toString();
        listener.accept(new AgentEvent.Start(messageId));
        for (String part : PARTS.split(ANSWER_PREFIX + text)) {
            listener.accept(new AgentEvent.Delta(messageId, part));
        }
        listener.accept(new AgentEvent.End(messageId));
    }
}
