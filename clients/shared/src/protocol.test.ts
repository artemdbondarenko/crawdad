import { describe, expect, expectTypeOf, it } from "vitest";
import type {
  AgentMessageDelta,
  AgentMessageEnd,
  AgentMessageStart,
  ErrorMessage,
  ProtocolMessage,
  SessionStarted,
  UserMessage,
} from "./index";

// Type-level checks run in `pnpm typecheck`; the runtime part keeps them honest in `pnpm test`.

/** Names a message by its kind; does not compile if a kind is missing or unknown. */
function describeMessage(message: ProtocolMessage): string {
  switch (message.type) {
    case "session.started":
      return `session ${message.session_id}`;
    case "user.message":
      return `user: ${message.text}`;
    case "agent.message.start":
      return `start ${message.message_id}`;
    case "agent.message.delta":
      return `delta ${message.message_id}: ${message.text}`;
    case "agent.message.end":
      return `end ${message.message_id}`;
    case "error":
      return `error: ${message.message}`;
    default: {
      const unknown: never = message;
      return unknown;
    }
  }
}

describe("protocol types generated from the schema", () => {
  it("describe every message of the contract", () => {
    const messages: ProtocolMessage[] = [
      { type: "session.started", session_id: "s1" } satisfies SessionStarted,
      { type: "user.message", session_id: "s1", text: "hello" } satisfies UserMessage,
      {
        type: "agent.message.start",
        session_id: "s1",
        message_id: "m1",
      } satisfies AgentMessageStart,
      {
        type: "agent.message.delta",
        session_id: "s1",
        message_id: "m1",
        text: "You",
      } satisfies AgentMessageDelta,
      { type: "agent.message.end", session_id: "s1", message_id: "m1" } satisfies AgentMessageEnd,
      { type: "error", session_id: "s1", message: "bad" } satisfies ErrorMessage,
    ];

    expect(messages.map(describeMessage)).toEqual([
      "session s1",
      "user: hello",
      "start m1",
      "delta m1: You",
      "end m1",
      "error: bad",
    ]);
  });

  it("are told apart by the type field", () => {
    expectTypeOf<ProtocolMessage["type"]>().toEqualTypeOf<
      | "session.started"
      | "user.message"
      | "agent.message.start"
      | "agent.message.delta"
      | "agent.message.end"
      | "error"
    >();
    expectTypeOf<
      Extract<ProtocolMessage, { type: "agent.message.delta" }>
    >().toEqualTypeOf<AgentMessageDelta>();
  });

  it("reject messages outside the contract", () => {
    // @ts-expect-error session_id is required
    const noSession: UserMessage = { type: "user.message", text: "hello" };
    // @ts-expect-error unknown message kind
    const unknownKind: ProtocolMessage = { type: "user.typing", session_id: "s1" };
    // @ts-expect-error a field the message does not have
    const extraField: SessionStarted = { type: "session.started", session_id: "s1", extra: true };

    expect([noSession, unknownKind, extraField]).toHaveLength(3);
  });
});
