import type { ProtocolMessage, UserMessage } from "./index";

/** What a socket reports to the connection. */
export interface SocketEvents {
  open(): void;
  /** One text frame from the service. */
  message(data: string): void;
  close(): void;
}

/** The part of a socket the connection uses. */
export interface Socket {
  send(data: string): void;
  close(): void;
}

/** Opens a socket to `url` and reports its events; tests pass a fake one. */
export type SocketFactory = (url: string, events: SocketEvents) => Socket;

/** `connecting` until the service names the session; `lost` once the socket closes. */
export type ConnectionState = "connecting" | "connected" | "lost";

export type ConversationEntry =
  | { kind: "user"; text: string }
  | { kind: "agent"; id: string; text: string; complete: boolean }
  | { kind: "error"; text: string };

/** Everything a client shows; a new object on every change. */
export interface Conversation {
  state: ConnectionState;
  entries: readonly ConversationEntry[];
  /** Connected and not waiting for an answer. */
  canSend: boolean;
}

/** The WebSocket address of the service that served the page at `host`, such as `127.0.0.1:8460`. */
export function serviceUrl(host: string): string {
  return `ws://${host}/ws`;
}

/** A socket over the browser's WebSocket. Binary frames are not part of the protocol and are dropped. */
export function browserSocket(url: string, events: SocketEvents): Socket {
  const ws = new WebSocket(url);
  ws.onopen = () => events.open();
  ws.onmessage = (event: { data: unknown }) => {
    if (typeof event.data === "string") events.message(event.data);
  };
  ws.onclose = () => events.close();
  return {
    send: (data) => ws.send(data),
    close: () => ws.close(),
  };
}

/**
 * The client side of one conversation with the service: one WebSocket, one session. Sends the user's
 * texts, joins the parts of each answer by `message_id` and tells subscribers about every change.
 */
export class ServiceConnection {
  private readonly socket: Socket;
  private readonly listeners = new Set<(conversation: Conversation) => void>();
  private sessionId: string | undefined;
  private state: ConnectionState = "connecting";
  private entries: ConversationEntry[] = [];
  private waitingForAnswer = false;

  constructor(host: string, factory: SocketFactory = browserSocket) {
    this.socket = factory(serviceUrl(host), {
      open: () => {},
      message: (data) => this.receive(data),
      close: () => {
        this.state = "lost";
        this.changed();
      },
    });
  }

  get conversation(): Conversation {
    return {
      state: this.state,
      entries: this.entries.map((entry) => ({ ...entry })),
      canSend: this.state === "connected" && !this.waitingForAnswer,
    };
  }

  /** Calls `listener` on every change; returns the function that stops it. */
  subscribe(listener: (conversation: Conversation) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Sends `text` to the agent; false, and nothing sent, when the conversation cannot take it now. */
  send(text: string): boolean {
    if (!this.conversation.canSend || this.sessionId === undefined) return false;
    const message: UserMessage = { type: "user.message", session_id: this.sessionId, text };
    this.socket.send(JSON.stringify(message));
    this.entries.push({ kind: "user", text });
    this.waitingForAnswer = true;
    this.changed();
    return true;
  }

  close(): void {
    this.socket.close();
  }

  private receive(data: string): void {
    let message: ProtocolMessage;
    try {
      message = JSON.parse(data) as ProtocolMessage;
    } catch {
      this.entries.push({
        kind: "error",
        text: "The service sent a message the client cannot read",
      });
      this.changed();
      return;
    }
    switch (message.type) {
      case "session.started":
        this.sessionId = message.session_id;
        this.state = "connected";
        break;
      case "agent.message.start":
        this.answer(message.message_id);
        break;
      case "agent.message.delta":
        this.answer(message.message_id).text += message.text;
        break;
      case "agent.message.end":
        this.answer(message.message_id).complete = true;
        this.waitingForAnswer = false;
        break;
      case "error":
        this.entries.push({ kind: "error", text: message.message });
        this.waitingForAnswer = false;
        break;
      case "user.message":
        // Only clients send it; the service never does.
        return;
    }
    this.changed();
  }

  /** The answer with this id, created on its first event. */
  private answer(id: string): { text: string; complete: boolean } {
    const found = this.entries.find((entry) => entry.kind === "agent" && entry.id === id);
    if (found?.kind === "agent") return found;
    const created = { kind: "agent" as const, id, text: "", complete: false };
    this.entries.push(created);
    return created;
  }

  private changed(): void {
    const conversation = this.conversation;
    for (const listener of this.listeners) listener(conversation);
  }
}
