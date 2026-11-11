import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Conversation, PairResponse } from "@crawdad/shared";
import { render, type ChatConnection } from "./main";

/** Plays the connection: the test decides what the conversation looks like and when it changes. */
class FakeConnection implements ChatConnection {
  conversation: Conversation = { state: "connecting", entries: [], canSend: false };
  readonly sent: string[] = [];
  accepts = true;
  started = 0;
  private listener: ((conversation: Conversation) => void) | undefined;

  subscribe(listener: (conversation: Conversation) => void): () => void {
    this.listener = listener;
    return () => {
      this.listener = undefined;
    };
  }

  send(text: string): boolean {
    this.sent.push(text);
    return this.accepts;
  }

  start(): void {
    this.started++;
  }

  change(update: Partial<Conversation>): void {
    this.conversation = { ...this.conversation, ...update };
    this.listener?.(this.conversation);
  }
}

/** Plays `POST /pair`: records each request and answers when the test says so. */
class FakePairing {
  readonly codes: string[] = [];
  private answer: ((response: PairResponse) => void) | undefined;
  private fail: ((error: Error) => void) | undefined;

  readonly fetch = (_url: string, init: RequestInit): Promise<PairResponse> => {
    this.codes.push((JSON.parse(init.body as string) as { code: string }).code);
    return new Promise((resolve, reject) => {
      this.answer = resolve;
      this.fail = reject;
    });
  };

  async respond(status: number): Promise<void> {
    this.answer?.({ status });
    await settled();
  }

  async goOffline(): Promise<void> {
    this.fail?.(new TypeError("Failed to fetch"));
    await settled();
  }
}

/** Lets the promise callbacks of the page run. */
const settled = () => new Promise((resolve) => setTimeout(resolve, 0));

let root: HTMLElement;
let connection: FakeConnection;
let pairing: FakePairing;

function find<T extends Element>(selector: string): T {
  const element = root.querySelector<T>(selector);
  if (!element) throw new Error(`Nothing matches ${selector}`);
  return element;
}

const status = () => find<HTMLElement>("[data-role=status]");
const input = () => find<HTMLInputElement>("[data-role=chat] input[name=text]");
const button = () => find<HTMLButtonElement>("[data-role=chat] button[type=submit]");
const pairingForm = () => find<HTMLFormElement>("[data-role=pairing]");
const codeField = () => find<HTMLInputElement>("[data-role=pairing] input[name=code]");
const pairButton = () => find<HTMLButtonElement>("[data-role=pairing] button[type=submit]");
const pairingError = () => find<HTMLElement>("[data-role=pairing-error]");
const chatForm = () => find<HTMLFormElement>("[data-role=chat]");

function messages(): Array<{ kind: string | undefined; text: string | null }> {
  return [...root.querySelectorAll<HTMLElement>("[data-role=messages] > li")].map((item) => ({
    kind: item.dataset["kind"],
    text: item.textContent,
  }));
}

function submit(text: string): void {
  input().value = text;
  button().click();
}

function mount(): void {
  root = document.createElement("div");
  document.body.replaceChildren(root);
  connection = new FakeConnection();
  pairing = new FakePairing();
  render(root, document, connection, { fetch: pairing.fetch });
}

beforeEach(() => {
  history.replaceState(null, "", "/");
  mount();
});

afterEach(() => {
  history.replaceState(null, "", "/");
});

describe("render", () => {
  it("shows the client title in the page and in the window title", () => {
    expect(root.querySelector("h1")?.textContent).toBe("Crawdad (desktop)");
    expect(document.title).toBe("Crawdad (desktop)");
  });

  it("shows the connecting, connected and lost states", () => {
    expect(status().dataset["state"]).toBe("connecting");
    expect(status().textContent).toBe("Connecting…");

    connection.change({ state: "connected", canSend: true });
    expect(status().dataset["state"]).toBe("connected");
    expect(status().textContent).toBe("Connected");

    connection.change({ state: "lost", canSend: false });
    expect(status().dataset["state"]).toBe("lost");
    expect(status().textContent).toBe("Connection lost");
  });

  it("says when the device is not paired or was unpaired, and keeps input disabled", () => {
    connection.change({ state: "unpaired", canSend: false });
    expect(status().dataset["state"]).toBe("unpaired");
    expect(status().textContent).toBe("This device is not paired");
    expect(input().disabled).toBe(true);

    connection.change({ state: "revoked", canSend: false });
    expect(status().dataset["state"]).toBe("revoked");
    expect(status().textContent).toBe("This device was unpaired");
    expect(input().disabled).toBe(true);
  });

  it("UI-1: says that the page must be opened at the printed address, and keeps input disabled", () => {
    connection.change({ state: "wrong_address", canSend: false });

    expect(status().dataset["state"]).toBe("wrong_address");
    expect(status().textContent).toBe(
      "Open this page at the address Crawdad prints when it starts",
    );
    expect(input().disabled).toBe(true);
    expect(pairingForm().hidden).toBe(true);
  });

  it("keeps the input disabled until the conversation can take a text", () => {
    expect(input().disabled).toBe(true);
    expect(button().disabled).toBe(true);

    connection.change({ state: "connected", canSend: true });

    expect(input().disabled).toBe(false);
    expect(button().disabled).toBe(false);
  });

  it("sends a text, waits for the answer as it grows, then accepts input again", () => {
    connection.change({ state: "connected", canSend: true });

    // The owner sends a text: it goes to the connection and the field is cleared.
    submit("hello");
    expect(connection.sent).toEqual(["hello"]);
    expect(input().value).toBe("");

    // The text is shown and input is blocked while the answer is on its way.
    connection.change({ entries: [{ kind: "user", text: "hello" }], canSend: false });
    expect(messages()).toEqual([{ kind: "user", text: "hello" }]);
    expect(input().disabled).toBe(true);

    // Parts of the answer grow one message under the text.
    for (const part of ["You", "You said:", "You said: hello"]) {
      connection.change({
        entries: [
          { kind: "user", text: "hello" },
          { kind: "agent", id: "m1", text: part, complete: false },
        ],
      });
    }
    expect(messages()).toEqual([
      { kind: "user", text: "hello" },
      { kind: "agent", text: "You said: hello" },
    ]);
    expect(input().disabled).toBe(true);

    // The answer is complete: input is available again.
    connection.change({
      entries: [
        { kind: "user", text: "hello" },
        { kind: "agent", id: "m1", text: "You said: hello", complete: true },
      ],
      canSend: true,
    });
    expect(input().disabled).toBe(false);
    expect(root.querySelector("[data-kind=agent]")?.hasAttribute("data-pending")).toBe(false);
  });

  it("marks an answer that is still arriving", () => {
    connection.change({
      state: "connected",
      entries: [{ kind: "agent", id: "m1", text: "You", complete: false }],
    });

    expect(root.querySelector("[data-kind=agent]")?.hasAttribute("data-pending")).toBe(true);
  });

  it("shows an error from the service as a message", () => {
    connection.change({
      state: "connected",
      entries: [{ kind: "error", text: "The agent could not answer" }],
      canSend: true,
    });

    expect(messages()).toEqual([{ kind: "error", text: "The agent could not answer" }]);
  });

  it("sends nothing for an empty text", () => {
    connection.change({ state: "connected", canSend: true });

    submit("   ");

    expect(connection.sent).toEqual([]);
  });

  it("keeps the text in the field when the connection does not take it", () => {
    connection.change({ state: "connected", canSend: true });
    connection.accepts = false;

    submit("hello");

    expect(input().value).toBe("hello");
  });

  it("shows agent output and errors as text, never as markup", () => {
    connection.change({
      state: "connected",
      entries: [
        { kind: "agent", id: "m1", text: "<img src=x onerror=alert(1)>", complete: true },
        { kind: "error", text: "<b>bold</b>" },
      ],
      canSend: true,
    });

    expect(messages()).toEqual([
      { kind: "agent", text: "<img src=x onerror=alert(1)>" },
      { kind: "error", text: "<b>bold</b>" },
    ]);
    expect(root.querySelector("img, b")).toBeNull();
  });
});

describe("pairing screen", () => {
  it("is shown instead of the chat while the device is not paired", () => {
    expect(pairingForm().hidden).toBe(true);
    expect(chatForm().hidden).toBe(false);

    connection.change({ state: "unpaired" });

    expect(pairingForm().hidden).toBe(false);
    expect(chatForm().hidden).toBe(true);
    expect(codeField().inputMode).toBe("numeric");
    expect(codeField().placeholder).toBe("6-digit code");
    expect(pairButton().textContent).toBe("Pair this device");
    expect(pairingError().textContent).toBe("");
  });

  it("is shown after the device was unpaired, saying so", () => {
    connection.change({ state: "revoked" });

    expect(pairingForm().hidden).toBe(false);
    expect(chatForm().hidden).toBe(true);
    expect(status().textContent).toBe("This device was unpaired");
  });

  it("fills the code from a pairing link and removes it from the address, sending nothing", () => {
    // The owner opens the link from `crawdad pair`, with a path and a query to keep.
    history.replaceState(null, "", "/chat?x=1#pair=123456");
    mount();
    connection.change({ state: "unpaired" });

    // The code is in the field, the fragment is gone, the rest of the address stays.
    expect(codeField().value).toBe("123456");
    expect(location.hash).toBe("");
    expect(location.pathname).toBe("/chat");
    expect(location.search).toBe("?x=1");

    // Nothing goes to the service until the owner presses the button.
    expect(pairing.codes).toEqual([]);
  });

  it("leaves the address alone when it is not a pairing link", () => {
    history.replaceState(null, "", "/chat#section");
    mount();

    expect(codeField().value).toBe("");
    expect(location.hash).toBe("#section");
  });

  it("pairs on the button and starts the connection, then the chat returns", async () => {
    history.replaceState(null, "", "/#pair=123456");
    mount();
    connection.change({ state: "unpaired" });

    // The owner presses the button: the code goes to the service once, the button waits.
    pairButton().click();
    expect(pairing.codes).toEqual(["123456"]);
    expect(pairButton().disabled).toBe(true);

    // The service accepts it: the connection starts again.
    await pairing.respond(204);
    expect(connection.started).toBe(1);
    expect(pairingError().textContent).toBe("");
    expect(codeField().value).toBe("");

    // The connection reports the new session: the chat is back.
    connection.change({ state: "connected", canSend: true });
    expect(pairingForm().hidden).toBe(true);
    expect(chatForm().hidden).toBe(false);
    expect(input().disabled).toBe(false);
  });

  it("says the code is invalid or expired on 401 and lets the owner try again", async () => {
    connection.change({ state: "unpaired" });
    codeField().value = "000000";

    pairButton().click();
    await pairing.respond(401);

    expect(pairingError().textContent).toBe("Invalid or expired code");
    expect(connection.started).toBe(0);
    expect(pairButton().disabled).toBe(false);
    expect(codeField().value).toBe("000000");
  });

  it("says when the service refuses or cannot be reached", async () => {
    connection.change({ state: "unpaired" });
    codeField().value = "123456";

    pairButton().click();
    await pairing.respond(403);
    expect(pairingError().textContent).toBe("Pairing was refused by the service");

    pairButton().click();
    await pairing.goOffline();
    expect(pairingError().textContent).toBe("Could not reach the service");
    expect(connection.started).toBe(0);
  });

  it("asks for the code instead of sending an empty one", () => {
    connection.change({ state: "unpaired" });
    codeField().value = "  ";

    pairButton().click();

    expect(pairing.codes).toEqual([]);
    expect(pairingError().textContent).toBe("Enter the 6-digit code");
  });
});
