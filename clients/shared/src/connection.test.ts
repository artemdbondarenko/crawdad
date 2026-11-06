import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ServiceConnection,
  browserSocket,
  serviceUrl,
  type Conversation,
  type SocketEvents,
} from "./connection";
import type { ProtocolMessage } from "./index";

/** A socket the test drives by hand: it records what the client sends and plays the service. */
class FakeSocket {
  readonly sent: ProtocolMessage[] = [];
  closed = false;
  url = "";
  private events: SocketEvents | undefined;

  readonly factory = (url: string, events: SocketEvents) => {
    this.url = url;
    this.events = events;
    return {
      send: (data: string) => this.sent.push(JSON.parse(data) as ProtocolMessage),
      close: () => {
        this.closed = true;
      },
    };
  };

  open(): void {
    this.events?.open();
  }

  receive(message: ProtocolMessage): void {
    this.events?.message(JSON.stringify(message));
  }

  receiveRaw(data: string): void {
    this.events?.message(data);
  }

  drop(): void {
    this.events?.close();
  }
}

function connect(): { socket: FakeSocket; connection: ServiceConnection; seen: Conversation[] } {
  const socket = new FakeSocket();
  const connection = new ServiceConnection("127.0.0.1:8460", socket.factory);
  const seen: Conversation[] = [];
  connection.subscribe((conversation) => seen.push(conversation));
  return { socket, connection, seen };
}

function start(id: string): ProtocolMessage {
  return { type: "agent.message.start", session_id: "s1", message_id: id };
}

function delta(id: string, text: string): ProtocolMessage {
  return { type: "agent.message.delta", session_id: "s1", message_id: id, text };
}

function end(id: string): ProtocolMessage {
  return { type: "agent.message.end", session_id: "s1", message_id: id };
}

/** A connection that has received its session, as after a normal start. */
function connected(): ReturnType<typeof connect> {
  const parts = connect();
  parts.socket.open();
  parts.socket.receive({ type: "session.started", session_id: "s1" });
  return parts;
}

describe("serviceUrl", () => {
  it("points at /ws of the host that served the page", () => {
    expect(serviceUrl("127.0.0.1:8460")).toBe("ws://127.0.0.1:8460/ws");
  });
});

describe("ServiceConnection", () => {
  it("opens the socket at the service address", () => {
    const { socket } = connect();

    expect(socket.url).toBe("ws://127.0.0.1:8460/ws");
  });

  it("is connecting until the service names the session, then connected", () => {
    const { socket, connection } = connect();
    expect(connection.conversation.state).toBe("connecting");

    // An open socket is not enough: the session comes with session.started.
    socket.open();
    expect(connection.conversation.state).toBe("connecting");

    socket.receive({ type: "session.started", session_id: "s1" });
    expect(connection.conversation.state).toBe("connected");
    expect(connection.conversation.canSend).toBe(true);
  });

  it("is lost when the socket closes, and cannot send any more", () => {
    const { socket, connection } = connected();

    socket.drop();

    expect(connection.conversation.state).toBe("lost");
    expect(connection.conversation.canSend).toBe(false);
    expect(connection.send("hi")).toBe(false);
    expect(socket.sent).toEqual([]);
  });

  it("sends nothing before session.started", () => {
    const { socket, connection } = connect();
    socket.open();

    expect(connection.send("too early")).toBe(false);

    expect(socket.sent).toEqual([]);
    expect(connection.conversation.entries).toEqual([]);
  });

  it("sends the text as user.message of its session and shows it", () => {
    const { socket, connection } = connected();

    expect(connection.send("hi")).toBe(true);

    expect(socket.sent).toEqual([{ type: "user.message", session_id: "s1", text: "hi" }]);
    expect(connection.conversation.entries).toEqual([{ kind: "user", text: "hi" }]);
  });

  it("joins the parts of an answer into one message, finished by end", () => {
    const { socket, connection } = connected();
    connection.send("hi");
    // Waiting for the answer: no second text until it is complete.
    expect(connection.conversation.canSend).toBe(false);
    expect(connection.send("again")).toBe(false);

    socket.receive(start("m1"));
    socket.receive(delta("m1", "You"));
    socket.receive(delta("m1", " said:"));
    socket.receive(delta("m1", " hi"));
    expect(connection.conversation.entries.at(-1)).toEqual({
      kind: "agent",
      id: "m1",
      text: "You said: hi",
      complete: false,
    });

    socket.receive(end("m1"));

    expect(connection.conversation.entries).toEqual([
      { kind: "user", text: "hi" },
      { kind: "agent", id: "m1", text: "You said: hi", complete: true },
    ]);
    expect(connection.conversation.canSend).toBe(true);
  });

  it("keeps the parts of two answers apart", () => {
    const { socket, connection } = connected();

    socket.receive(start("m1"));
    socket.receive(start("m2"));
    socket.receive(delta("m1", "one"));
    socket.receive(delta("m2", "two"));
    // A part whose start was not seen still lands in its own message.
    socket.receive(delta("m3", "three"));

    expect(connection.conversation.entries).toEqual([
      { kind: "agent", id: "m1", text: "one", complete: false },
      { kind: "agent", id: "m2", text: "two", complete: false },
      { kind: "agent", id: "m3", text: "three", complete: false },
    ]);
  });

  it("shows an error in the conversation and lets the user send again", () => {
    const { socket, connection } = connected();
    connection.send("hi");

    socket.receive({ type: "error", session_id: "s1", message: "The agent could not answer" });

    expect(connection.conversation.entries.at(-1)).toEqual({
      kind: "error",
      text: "The agent could not answer",
    });
    expect(connection.conversation.canSend).toBe(true);
  });

  it("shows a message it cannot read as an error", () => {
    const { socket, connection } = connected();

    socket.receiveRaw("{not json");

    expect(connection.conversation.entries).toEqual([
      { kind: "error", text: "The service sent a message the client cannot read" },
    ]);
  });

  it("ignores user.message from the service: only clients send it", () => {
    const { socket, connection, seen } = connected();
    const before = seen.length;

    socket.receive({ type: "user.message", session_id: "s1", text: "echo" });

    expect(connection.conversation.entries).toEqual([]);
    expect(seen).toHaveLength(before);
  });

  it("gives every subscriber a snapshot that later parts do not change", () => {
    const { socket, connection, seen } = connected();
    socket.receive(start("m1"));
    socket.receive({ type: "agent.message.delta", session_id: "s1", message_id: "m1", text: "a" });
    const snapshot = connection.conversation;

    socket.receive({ type: "agent.message.delta", session_id: "s1", message_id: "m1", text: "b" });

    expect(snapshot.entries).toEqual([{ kind: "agent", id: "m1", text: "a", complete: false }]);
    expect(seen.at(-1)?.entries).toEqual([
      { kind: "agent", id: "m1", text: "ab", complete: false },
    ]);
  });

  it("tells the subscriber about every change, and stops after unsubscribe", () => {
    const { socket, connection, seen } = connect();
    const late: Conversation[] = [];
    const unsubscribe = connection.subscribe((conversation) => late.push(conversation));

    socket.receive({ type: "session.started", session_id: "s1" });
    unsubscribe();
    connection.send("hi");

    expect(seen.map((conversation) => conversation.state)).toEqual(["connected", "connected"]);
    expect(seen.at(-1)?.entries).toEqual([{ kind: "user", text: "hi" }]);
    expect(late).toHaveLength(1);
  });

  it("closes the socket when closed by the client", () => {
    const { socket, connection } = connected();

    connection.close();

    expect(socket.closed).toBe(true);
  });
});

describe("browserSocket", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("wires a browser WebSocket to the events and passes sends and close through", () => {
    const created: Array<{
      url: string;
      onopen: (() => void) | null;
      onmessage: ((event: { data: unknown }) => void) | null;
      onclose: (() => void) | null;
      send: ReturnType<typeof vi.fn>;
      close: ReturnType<typeof vi.fn>;
    }> = [];
    vi.stubGlobal(
      "WebSocket",
      class {
        onopen = null;
        onmessage = null;
        onclose = null;
        send = vi.fn();
        close = vi.fn();
        constructor(public url: string) {
          created.push(this);
        }
      },
    );
    const events = { open: vi.fn(), message: vi.fn(), close: vi.fn() };

    const socket = browserSocket("ws://h/ws", events);
    const ws = created[0];
    if (!ws) throw new Error("no WebSocket was created");
    ws.onopen?.();
    ws.onmessage?.({ data: "text" });
    // A binary frame is not part of the protocol; it is not passed on.
    ws.onmessage?.({ data: new ArrayBuffer(1) });
    ws.onclose?.();
    socket.send("out");
    socket.close();

    expect(ws.url).toBe("ws://h/ws");
    expect(events.open).toHaveBeenCalledOnce();
    expect(events.message).toHaveBeenCalledExactlyOnceWith("text");
    expect(events.close).toHaveBeenCalledOnce();
    expect(ws.send).toHaveBeenCalledWith("out");
    expect(ws.close).toHaveBeenCalledOnce();
  });
});
