package io.github.artemdbondarenko.crawdad.service.adapter;

import io.github.artemdbondarenko.crawdad.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider;
import io.github.artemdbondarenko.crawdad.adapter.AgentEvent;
import java.util.function.Consumer;

/**
 * An adapter that {@link AgentAdaptersTest} copies into a jar of its own, the way an adapter from outside the
 * service arrives. Provider and adapter in one class, so the jar needs only this class.
 */
public final class TestAdapterProvider implements AgentAdapterProvider, AgentAdapter {

    @Override
    public AgentAdapter create() {
        return new TestAdapterProvider();
    }

    @Override
    public String startSession() {
        return "session";
    }

    @Override
    public void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener) {
        listener.accept(new AgentEvent.Start("m"));
        listener.accept(new AgentEvent.Delta("m", text));
        listener.accept(new AgentEvent.End("m"));
    }

    @Override
    public boolean executesRepositoryConfiguration() {
        return true;
    }
}
