import type { ProtocolMessage, UserMessage } from "./index";

/** What a socket reports to the connection. */
export interface SocketEvents {
  open(): void;
  /** One text frame from the service. */
  message(data: string): void;
  /** The WebSocket close code; 4001 means the service revoked this device. */
  close(code: number): void;
}

/** The part of a socket the connection uses. */
export interface Socket {
  send(data: string): void;
  close(): void;
}

/** Opens a socket to `url` and reports its events; tests pass a fake one. */
export type SocketFactory = (url: string, events: SocketEvents) => Socket;

/** The part of a `fetch` response the device check reads. */
export interface DeviceResponse {
  status: number;
}

/** Sends `GET /device`; tests pass a fake one. */
export type DeviceFetch = (url: string, init: RequestInit) => Promise<DeviceResponse>;

/** The timers the reconnect pauses use; tests pass ones they fire by hand. */
export interface Timers {
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

/** What the connection needs from the outside world; each part defaults to the browser's. */
export interface ConnectionDependencies {
  socket?: SocketFactory;
  fetch?: DeviceFetch;
  timers?: Timers;
}

/**
 * `connecting` until the first session; `lost` after a close, while reconnecting; `unpaired` when the
 * service does not know this device (the page asks for a pairing code); `revoked` after close code
 * 4001. In `unpaired` and `revoked` the connection stops until `start()` is called again.
 */
export type ConnectionState = "connecting" | "connected" | "lost" | "unpaired" | "revoked";

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

/** Where the page came from, such as `location`. */
export interface PageLocation {
  protocol: string;
  host: string;
}

/** The WebSocket address of the service that served the page: `wss://` for an `https:` page. */
export function serviceUrl(page: PageLocation): string {
  const scheme = page.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${page.host}/ws`;
}

/** A socket over the browser's WebSocket. Binary frames are not part of the protocol and are dropped. */
export function browserSocket(url: string, events: SocketEvents): Socket {
  const ws = new WebSocket(url);
  ws.onopen = () => events.open();
  ws.onmessage = (event: { data: unknown }) => {
    if (typeof event.data === "string") events.message(event.data);
  };
  ws.onclose = (event: { code: number }) => events.close(event.code);
  return {
    send: (data) => ws.send(data),
    close: () => ws.close(),
  };
}

const browserFetch: DeviceFetch = (url, init) => fetch(url, init);

const browserTimers: Timers = {
  setTimeout: (callback, ms) => setTimeout(callback, ms),
  clearTimeout: (handle) => clearTimeout(handle as Parameters<typeof clearTimeout>[0]),
};

/** The service closes a socket with this code when it revokes the device. */
const CLOSE_UNPAIRED = 4001;
const FIRST_PAUSE_MS = 1000;
const LONGEST_PAUSE_MS = 30_000;

/**
 * The client side of the conversation with the service. `start()` checks the device key with
 * `GET /device`, then opens the WebSocket; after a close it checks and reconnects with growing pauses,
 * into a new session that keeps the earlier entries. Joins the parts of each answer by `message_id`
 * and tells subscribers about every change.
 */
export class ServiceConnection {
  private readonly url: string;
  private readonly openSocket: SocketFactory;
  private readonly fetchDevice: DeviceFetch;
  private readonly timers: Timers;
  private readonly listeners = new Set<(conversation: Conversation) => void>();
  private socket: Socket | undefined;
  private sessionId: string | undefined;
  private state: ConnectionState = "connecting";
  private entries: ConversationEntry[] = [];
  private waitingForAnswer = false;
  private running = false;
  /** Bumped on every stop, so a device check or a socket of an earlier run is ignored. */
  private run = 0;
  private retry: unknown;
  private pauseMs = FIRST_PAUSE_MS;

  constructor(page: PageLocation, dependencies: ConnectionDependencies = {}) {
    this.url = serviceUrl(page);
    this.openSocket = dependencies.socket ?? browserSocket;
    this.fetchDevice = dependencies.fetch ?? browserFetch;
    this.timers = dependencies.timers ?? browserTimers;
  }

  /** Connects, unless already running; also the way back after pairing in `unpaired` or `revoked`. */
  start(): void {
    if (this.running) return;
    this.running = true;
    this.pauseMs = FIRST_PAUSE_MS;
    if (this.state === "unpaired" || this.state === "revoked") {
      this.state = "connecting";
      this.changed();
    }
    void this.checkAndConnect(this.run);
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
    this.socket?.send(JSON.stringify(message));
    this.entries.push({ kind: "user", text });
    this.waitingForAnswer = true;
    this.changed();
    return true;
  }

  /** Closes the socket and stops reconnecting. */
  close(): void {
    this.stop();
    this.socket?.close();
    this.socket = undefined;
  }

  private stop(): void {
    this.running = false;
    this.run++;
    if (this.retry !== undefined) this.timers.clearTimeout(this.retry);
    this.retry = undefined;
  }

  /** Asks the service whether it knows this device, then opens the socket or decides what is next. */
  private async checkAndConnect(run: number): Promise<void> {
    let status: number | undefined;
    try {
      const response = await this.fetchDevice("/device", {
        method: "GET",
        credentials: "include",
        cache: "no-store",
      });
      status = response.status;
    } catch {
      // The service cannot be reached: the same as a 5xx, tried again later.
    }
    if (run !== this.run) return;
    if (status === 200) {
      this.connect(run);
    } else if (status === 401) {
      this.halt("unpaired");
    } else {
      // A 403 means Origin or Host do not match; it is retried like an unreachable service.
      this.scheduleRetry(run);
    }
  }

  private connect(run: number): void {
    const socket = this.openSocket(this.url, {
      open: () => {},
      message: (data) => {
        if (this.socket === socket) this.receive(data);
      },
      close: (code) => {
        if (this.socket === socket) this.closed(code, run);
      },
    });
    this.socket = socket;
  }

  private closed(code: number, run: number): void {
    this.socket = undefined;
    this.sessionId = undefined;
    this.waitingForAnswer = false;
    if (code === CLOSE_UNPAIRED) {
      this.halt("revoked");
      return;
    }
    this.state = "lost";
    this.changed();
    this.scheduleRetry(run);
  }

  /** Stops in a state only `start()` leaves: no socket, no retries. */
  private halt(state: "unpaired" | "revoked"): void {
    this.stop();
    this.state = state;
    this.changed();
  }

  private scheduleRetry(run: number): void {
    const pause = this.pauseMs;
    this.pauseMs = Math.min(pause * 2, LONGEST_PAUSE_MS);
    this.retry = this.timers.setTimeout(() => {
      this.retry = undefined;
      void this.checkAndConnect(run);
    }, pause);
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
        this.pauseMs = FIRST_PAUSE_MS;
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
