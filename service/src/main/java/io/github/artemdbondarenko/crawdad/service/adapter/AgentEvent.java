package io.github.artemdbondarenko.crawdad.service.adapter;

/**
 * One event of an agent's answer, in the order of Claude Code's stream-json: a {@link Start}, zero
 * or more {@link Delta} parts and an {@link End}, all with the same message id.
 */
public sealed interface AgentEvent {

    String messageId();

    record Start(String messageId) implements AgentEvent {}

    /** The next part of the answer text; the parts joined in order give the whole answer. */
    record Delta(String messageId, String text) implements AgentEvent {}

    record End(String messageId) implements AgentEvent {}
}
