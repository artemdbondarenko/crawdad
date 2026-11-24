package io.github.artemdbondarenko.crawdad.adapter;

/**
 * What the service loads from the jar of an adapter with {@link java.util.ServiceLoader}. The jar names
 * exactly one provider in {@code META-INF/services/io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider};
 * the class is public and has a public constructor without arguments. The name of the adapter and the version
 * of this interface it is built for are in the manifest of the jar, not here: the service reads them without
 * loading any class ({@link AdapterApi}).
 */
public interface AgentAdapterProvider {

    /** Creates the adapter. The service calls it once, at start, and only for the adapter the owner chose. */
    AgentAdapter create();
}
