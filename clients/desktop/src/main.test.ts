import { beforeEach, describe, expect, it } from "vitest";
import type { Conversation } from "@crawdad/shared";
import { render, type ChatConnection } from "./main";

/** Plays the connection: the test decides what the conversation looks like and when it changes. */
class FakeConnection implements ChatConnection {
  conversation: Conversation = { state: "connecting", entries: [], canSend: false };
  readonly sent: string[] = [];
  accepts = true;
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

  change(update: Partial<Conversation>): void {
    this.conversation = { ...this.conversation, ...update };
    this.listener?.(this.conversation);
  }
}

let root: HTMLElement;
let connection: FakeConnection;

function find<T extends Element>(selector: string): T {
  const element = root.querySelector<T>(selector);
  if (!element) throw new Error(`Nothing matches ${selector}`);
  return element;
}

const status = () => find<HTMLElement>("[data-role=status]");
const input = () => find<HTMLInputElement>("input[name=text]");
const button = () => find<HTMLButtonElement>("button[type=submit]");

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

beforeEach(() => {
  root = document.createElement("div");
  document.body.replaceChildren(root);
  connection = new FakeConnection();
  render(root, document, connection);
});

describe("render", () => {
  it("shows the client title in the page and in the window title", () => {
    expect(root.querySelector("h1")?.textContent).toBe("Crawdad (desktop)");
    expect(document.title).toBe("Crawdad (desktop)");
  });

  it("shows each of the three connection states", () => {
    expect(status().dataset["state"]).toBe("connecting");
    expect(status().textContent).toBe("Connecting…");

    connection.change({ state: "connected", canSend: true });
    expect(status().dataset["state"]).toBe("connected");
    expect(status().textContent).toBe("Connected");

    connection.change({ state: "lost", canSend: false });
    expect(status().dataset["state"]).toBe("lost");
    expect(status().textContent).toBe("Connection lost");
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
});
