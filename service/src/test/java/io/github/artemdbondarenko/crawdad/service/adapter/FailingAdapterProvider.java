package io.github.artemdbondarenko.crawdad.service.adapter;

import io.github.artemdbondarenko.crawdad.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider;

/** A provider that fails to create its adapter, for {@link AgentAdaptersTest}. */
public final class FailingAdapterProvider implements AgentAdapterProvider {

    @Override
    public AgentAdapter create() {
        throw new IllegalStateException("no agent installed");
    }
}
