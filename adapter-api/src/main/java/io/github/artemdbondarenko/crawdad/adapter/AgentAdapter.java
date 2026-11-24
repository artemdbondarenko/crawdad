package io.github.artemdbondarenko.crawdad.adapter;

import java.util.function.Consumer;

/**
 * How the service talks to an agent. Holds only what the end-to-end path needs today: starting a
 * session and sending a message, and whether the agent executes configuration found in the repository.
 * A session is not ended when its connection closes: ending one comes with an adapter that has an agent
 * process to stop.
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

    /**
     * Whether the agent executes configuration found in the repository it works in: hooks, MCP servers,
     * permission rules, environment variables in its settings ({@code ADAPTER-9}). The service asks once,
     * when it creates the adapter.
     */
    boolean executesRepositoryConfiguration();
}
