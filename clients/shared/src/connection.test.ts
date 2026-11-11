import { afterEach, describe, expect, it, vi } from "vitest";
import {
  NEW_SESSION_NOTICE,
  ServiceConnection,
  browserSocket,
  serviceUrl,
  type Conversation,
  type DeviceResponse,
  type SocketEvents,
  type Timers,
} from "./connection";
import type { ProtocolMessage } from "./index";

/** A socket the test drives by hand: it records what the client sends and plays the service. */
class FakeSocket {
  readonly sent: ProtocolMessage[] = [];
  closed = false;
  url = "";
  opened = 0;
  private events: SocketEvents | undefined;

  readonly factory = (url: string, events: SocketEvents) => {
    this.url = url;
    this.events = events;
    this.opened++;
    this.closed = false;
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

  drop(code = 1006): void {
    this.events?.close(code);
  }
}

/** Plays `GET /device`: each call takes the next answer, a status or a network failure. */
class FakeDevice {
  readonly calls: Array<{ url: string; init: RequestInit }> = [];
  readonly answers: Array<number | "offline"> = [];

  readonly fetch = (url: string, init: RequestInit): Promise<DeviceResponse> => {
    this.calls.push({ url, init });
    const answer = this.answers.shift() ?? 200;
    return answer === "offline"
      ? Promise.reject(new TypeError("Failed to fetch"))
      : Promise.resolve({ status: answer });
  };
}

/** Timers the test fires by hand; it sees every delay the connection asked for. */
class FakeTimers implements Timers {
  readonly delays: number[] = [];
  private pending: Array<{ id: number; callback: () => void }> = [];
  private nextId = 1;

  setTimeout(callback: () => void, ms: number): unknown {
    const id = this.nextId++;
    this.delays.push(ms);
    this.pending.push({ id, callback });
    return id;
  }

  clearTimeout(handle: unknown): void {
    this.pending = this.pending.filter((timer) => timer.id !== handle);
  }

  get waiting(): number {
    return this.pending.length;
  }

  /** Fires the earliest timer and lets the device check it started finish. */
  async fire(): Promise<void> {
    const timer = this.pending.shift();
    if (!timer) throw new Error("no timer is waiting");
    timer.callback();
    await settle();
  }
}

/** Lets pending promises, such as the answer of `GET /device`, run. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

interface Parts {
  socket: FakeSocket;
  device: FakeDevice;
  timers: FakeTimers;
  connection: ServiceConnection;
  seen: Conversation[];
}

function create(protocol = "http:"): Parts {
  const socket = new FakeSocket();
  const device = new FakeDevice();
  const timers = new FakeTimers();
  const connection = new ServiceConnection(
    { protocol, host: "127.0.0.1:8460" },
    { socket: socket.factory, fetch: device.fetch, timers },
  );
  const seen: Conversation[] = [];
  connection.subscribe((conversation) => seen.push(conversation));
  return { socket, device, timers, connection, seen };
}

/** A started connection whose device check passed, so its socket is open. */
async function connect(): Promise<Parts> {
  const parts = create();
  parts.connection.start();
  await settle();
  return parts;
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
async function connected(): Promise<Parts> {
  const parts = await connect();
  parts.socket.open();
  parts.socket.receive({ type: "session.started", session_id: "s1" });
  return parts;
}

describe("serviceUrl", () => {
  it("points at /ws of the host that served the page", () => {
    expect(serviceUrl({ protocol: "http:", host: "127.0.0.1:8460" })).toBe(
      "ws://127.0.0.1:8460/ws",
    );
  });

  it("uses wss when the page came over https", () => {
    expect(serviceUrl({ protocol: "https:", host: "box.tail1.ts.net:8460" })).toBe(
      "wss://box.tail1.ts.net:8460/ws",
    );
  });
});

describe("ServiceConnection", () => {
  it("opens the socket at the service address", async () => {
    const { socket } = await connect();

    expect(socket.url).toBe("ws://127.0.0.1:8460/ws");
  });

  it("is connecting until the service names the session, then connected", async () => {
    const { socket, connection } = await connect();
    expect(connection.conversation.state).toBe("connecting");

    // An open socket is not enough: the session comes with session.started.
    socket.open();
    expect(connection.conversation.state).toBe("connecting");

    socket.receive({ type: "session.started", session_id: "s1" });
    expect(connection.conversation.state).toBe("connected");
    expect(connection.conversation.canSend).toBe(true);
  });

  it("is lost when the socket closes, and cannot send any more", async () => {
    const { socket, connection } = await connected();

    socket.drop();

    expect(connection.conversation.state).toBe("lost");
    expect(connection.conversation.canSend).toBe(false);
    expect(connection.send("hi")).toBe(false);
    expect(socket.sent).toEqual([]);
  });

  it("sends nothing before session.started", async () => {
    const { socket, connection } = await connect();
    socket.open();

    expect(connection.send("too early")).toBe(false);

    expect(socket.sent).toEqual([]);
    expect(connection.conversation.entries).toEqual([]);
  });

  it("sends the text as user.message of its session and shows it", async () => {
    const { socket, connection } = await connected();

    expect(connection.send("hi")).toBe(true);

    expect(socket.sent).toEqual([{ type: "user.message", session_id: "s1", text: "hi" }]);
    expect(connection.conversation.entries).toEqual([{ kind: "user", text: "hi" }]);
  });

  it("joins the parts of an answer into one message, finished by end", async () => {
    const { socket, connection } = await connected();
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

  it("keeps the parts of two answers apart", async () => {
    const { socket, connection } = await connected();

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

  it("shows an error in the conversation and lets the user send again", async () => {
    const { socket, connection } = await connected();
    connection.send("hi");

    socket.receive({ type: "error", session_id: "s1", message: "The agent could not answer" });

    expect(connection.conversation.entries.at(-1)).toEqual({
      kind: "error",
      text: "The agent could not answer",
    });
    expect(connection.conversation.canSend).toBe(true);
  });

  it("shows a message it cannot read as an error", async () => {
    const { socket, connection } = await connected();

    socket.receiveRaw("{not json");

    expect(connection.conversation.entries).toEqual([
      { kind: "error", text: "The service sent a message the client cannot read" },
    ]);
  });

  it("ignores user.message from the service: only clients send it", async () => {
    const { socket, connection, seen } = await connected();
    const before = seen.length;

    socket.receive({ type: "user.message", session_id: "s1", text: "echo" });

    expect(connection.conversation.entries).toEqual([]);
    expect(seen).toHaveLength(before);
  });

  it("gives every subscriber a snapshot that later parts do not change", async () => {
    const { socket, connection, seen } = await connected();
    socket.receive(start("m1"));
    socket.receive({ type: "agent.message.delta", session_id: "s1", message_id: "m1", text: "a" });
    const snapshot = connection.conversation;

    socket.receive({ type: "agent.message.delta", session_id: "s1", message_id: "m1", text: "b" });

    expect(snapshot.entries).toEqual([{ kind: "agent", id: "m1", text: "a", complete: false }]);
    expect(seen.at(-1)?.entries).toEqual([
      { kind: "agent", id: "m1", text: "ab", complete: false },
    ]);
  });

  it("tells the subscriber about every change, and stops after unsubscribe", async () => {
    const { socket, connection, seen } = await connect();
    const late: Conversation[] = [];
    const unsubscribe = connection.subscribe((conversation) => late.push(conversation));

    socket.receive({ type: "session.started", session_id: "s1" });
    unsubscribe();
    connection.send("hi");

    expect(seen.map((conversation) => conversation.state)).toEqual(["connected", "connected"]);
    expect(seen.at(-1)?.entries).toEqual([{ kind: "user", text: "hi" }]);
    expect(late).toHaveLength(1);
  });

  it("closes the socket when closed by the client", async () => {
    const { socket, connection } = await connected();

    connection.close();

    expect(socket.closed).toBe(true);
  });
});

describe("ServiceConnection with device keys", () => {
  it("does nothing until started, then asks GET /device with the cookie before opening the socket", async () => {
    const { socket, device, connection } = create();
    expect(device.calls).toEqual([]);

    connection.start();
    // The socket waits for the answer of the device check.
    expect(device.calls).toHaveLength(1);
    expect(socket.opened).toBe(0);
    await settle();

    expect(device.calls[0]?.url).toBe("/device");
    expect(device.calls[0]?.init).toMatchObject({ method: "GET", credentials: "include" });
    expect(socket.opened).toBe(1);
  });

  it("starts only once while running", async () => {
    const { socket, device, connection } = create();

    connection.start();
    connection.start();
    await settle();

    expect(device.calls).toHaveLength(1);
    expect(socket.opened).toBe(1);
  });

  it("opens a wss socket when the page came over https", async () => {
    const { socket, connection } = create("https:");

    connection.start();
    await settle();

    expect(socket.url).toBe("wss://127.0.0.1:8460/ws");
  });

  it("UI-1: says the address is wrong and stops when the service refuses the page with 403", async () => {
    const { socket, device, timers, connection } = create();
    device.answers.push(403);

    connection.start();
    await settle();

    // Opened at another name of the machine, such as localhost: no retry can fix that.
    expect(connection.conversation.state).toBe("wrong_address");
    expect(connection.conversation.canSend).toBe(false);
    expect(socket.opened).toBe(0);
    expect(timers.waiting).toBe(0);
  });

  it("UI-1: stops on 403 from the device check after a lost connection too", async () => {
    const { socket, device, timers, connection } = create();
    device.answers.push(200, 403);

    connection.start();
    await settle();
    socket.drop(1006);
    await timers.fire();

    expect(connection.conversation.state).toBe("wrong_address");
    expect(socket.opened).toBe(1);
    expect(timers.waiting).toBe(0);
  });

  it("is unpaired and stops when the service does not know the device", async () => {
    const { socket, device, timers, connection } = create();
    device.answers.push(401);

    connection.start();
    await settle();

    // The page shows the pairing screen; nothing is retried behind it.
    expect(connection.conversation.state).toBe("unpaired");
    expect(connection.conversation.canSend).toBe(false);
    expect(socket.opened).toBe(0);
    expect(timers.waiting).toBe(0);
  });

  it("can be started again after pairing", async () => {
    const { socket, device, connection } = create();
    device.answers.push(401, 200);
    connection.start();
    await settle();

    connection.start();
    expect(connection.conversation.state).toBe("connecting");
    await settle();

    expect(socket.opened).toBe(1);
  });

  it("is revoked on close code 4001 and never reconnects", async () => {
    const { socket, device, timers, connection } = await connected();
    connection.send("hi");

    socket.drop(4001);

    expect(connection.conversation.state).toBe("revoked");
    expect(connection.conversation.canSend).toBe(false);
    // The conversation so far stays on the screen.
    expect(connection.conversation.entries).toEqual([{ kind: "user", text: "hi" }]);
    expect(timers.waiting).toBe(0);
    expect(device.calls).toHaveLength(1);
    expect(socket.opened).toBe(1);
  });

  it("after any other close checks the device again and reconnects into a new session", async () => {
    const { socket, device, timers, connection } = await connected();
    connection.send("hi");

    socket.drop(1006);
    expect(connection.conversation.state).toBe("lost");
    expect(timers.delays).toEqual([1000]);

    // The pause is over: GET /device answers 200 and a new socket opens.
    await timers.fire();
    expect(device.calls).toHaveLength(2);
    expect(socket.opened).toBe(2);
    expect(connection.conversation.state).toBe("lost");

    // The new session joins the old entries; sending goes to the new session.
    socket.open();
    socket.receive({ type: "session.started", session_id: "s2" });
    expect(connection.conversation.state).toBe("connected");
    expect(connection.conversation.canSend).toBe(true);
    connection.send("again");
    expect(socket.sent.at(-1)).toEqual({ type: "user.message", session_id: "s2", text: "again" });
    expect(connection.conversation.entries).toEqual([
      { kind: "user", text: "hi" },
      { kind: "notice", text: NEW_SESSION_NOTICE },
      { kind: "user", text: "again" },
    ]);
  });

  it("UI-9: marks an answer cut off by a lost connection as interrupted, and it grows no more", async () => {
    const { socket, timers, connection } = await connected();
    connection.send("hello world");
    socket.receive({ type: "agent.message.start", session_id: "s1", message_id: "m1" });
    socket.receive({
      type: "agent.message.delta",
      session_id: "s1",
      message_id: "m1",
      text: "You",
    });

    socket.drop(1006);

    expect(connection.conversation.entries).toEqual([
      { kind: "user", text: "hello world" },
      { kind: "agent", id: "m1", text: "You", complete: false, interrupted: true },
    ]);
    // A late part of the closed socket changes nothing.
    socket.receive({
      type: "agent.message.delta",
      session_id: "s1",
      message_id: "m1",
      text: " said",
    });
    expect(connection.conversation.entries.at(-1)).toMatchObject({
      text: "You",
      interrupted: true,
    });

    // In the new session it stays as it was, above the notice.
    await timers.fire();
    socket.open();
    socket.receive({ type: "session.started", session_id: "s2" });
    expect(connection.conversation.entries.at(-2)).toMatchObject({ id: "m1", interrupted: true });
  });

  it("UI-9: leaves complete answers unmarked when the connection ends", async () => {
    const { socket, connection } = await connected();
    connection.send("hi");
    socket.receive({ type: "agent.message.start", session_id: "s1", message_id: "m1" });
    socket.receive({
      type: "agent.message.delta",
      session_id: "s1",
      message_id: "m1",
      text: "You said: hi",
    });
    socket.receive({ type: "agent.message.end", session_id: "s1", message_id: "m1" });

    socket.drop(4001);

    expect(connection.conversation.entries.at(-1)).toEqual({
      kind: "agent",
      id: "m1",
      text: "You said: hi",
      complete: true,
    });
  });

  it("UI-10: announces every new session after the first, and accepts input only once it started", async () => {
    const { socket, timers, connection } = await connected();
    expect(connection.conversation.entries).toEqual([]);

    socket.drop(1006);
    await timers.fire();
    socket.open();
    expect(connection.conversation.canSend).toBe(false);
    expect(connection.conversation.entries).toEqual([]);

    socket.receive({ type: "session.started", session_id: "s2" });
    expect(connection.conversation.entries).toEqual([{ kind: "notice", text: NEW_SESSION_NOTICE }]);
    expect(connection.conversation.canSend).toBe(true);
  });

  it("becomes unpaired when the device check after a close answers 401", async () => {
    const { socket, device, timers, connection } = await connected();
    device.answers.push(401);

    // A key revoked while the socket was down: the handshake fails as a plain close.
    socket.drop(1006);
    await timers.fire();

    expect(connection.conversation.state).toBe("unpaired");
    expect(socket.opened).toBe(1);
    expect(timers.waiting).toBe(0);
  });

  it("retries an unreachable service with pauses doubling up to 30 seconds", async () => {
    const { socket, device, timers, connection } = create();
    device.answers.push("offline", 503, 502, 500, "offline", "offline", "offline", "offline");

    connection.start();
    await settle();
    for (let i = 0; i < 7; i++) await timers.fire();

    expect(timers.delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]);
    expect(connection.conversation.state).toBe("connecting");
    expect(socket.opened).toBe(0);

    // The service is back: the next check passes and the socket opens.
    await timers.fire();
    expect(socket.opened).toBe(1);
  });

  it("counts a socket that closes before its session as a failure, and session.started resets the pause", async () => {
    const { socket, timers } = await connect();

    // The handshake is refused twice: the pauses grow.
    socket.drop(1006);
    await timers.fire();
    socket.drop(1006);
    await timers.fire();
    expect(timers.delays).toEqual([1000, 2000]);

    // A session starts, then drops: the pause starts from one second again.
    socket.open();
    socket.receive({ type: "session.started", session_id: "s1" });
    socket.drop(1006);
    expect(timers.delays).toEqual([1000, 2000, 1000]);
  });

  it("close() stops a pending retry and a device check in flight", async () => {
    const lost = await connected();
    lost.socket.drop(1006);
    lost.connection.close();
    expect(lost.timers.waiting).toBe(0);

    const checking = create();
    checking.connection.start();
    checking.connection.close();
    await settle();
    expect(checking.socket.opened).toBe(0);
    expect(checking.timers.waiting).toBe(0);
  });

  it("ignores events of a socket it has closed", async () => {
    const { socket, timers, connection, seen } = await connected();
    connection.close();
    const before = seen.length;

    socket.receive({ type: "session.started", session_id: "s9" });
    socket.drop(1006);

    expect(seen).toHaveLength(before);
    expect(timers.waiting).toBe(0);
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
      onclose: ((event: { code: number }) => void) | null;
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
    ws.onclose?.({ code: 4001 });
    socket.send("out");
    socket.close();

    expect(ws.url).toBe("ws://h/ws");
    expect(events.open).toHaveBeenCalledOnce();
    expect(events.message).toHaveBeenCalledExactlyOnceWith("text");
    expect(events.close).toHaveBeenCalledExactlyOnceWith(4001);
    expect(ws.send).toHaveBeenCalledWith("out");
    expect(ws.close).toHaveBeenCalledOnce();
  });
});
