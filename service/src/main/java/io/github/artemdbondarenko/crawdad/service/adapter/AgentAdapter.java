package io.github.artemdbondarenko.crawdad.service.adapter;

import java.util.function.Consumer;

/**
 * How the service talks to an agent. Holds only what the end-to-end path needs today: starting a
 * session and sending a message; the rest of the adapter interface comes with the real adapter.
 */
public interface AgentAdapter {

    /** Starts a session and returns its id. */
    String startSession();

    /**
     * Sends the user's text to the agent; the answer arrives in {@code listener} as {@link AgentEvent}s.
     *
     * @throws IllegalArgumentException if the session is unknown or the text is empty
     */
    void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener);
}
