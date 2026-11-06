import {
  appTitle,
  type ConnectionState,
  type Conversation,
  type ServiceConnection,
} from "@crawdad/shared";

/** The part of the connection the page uses; tests pass a fake one. */
export type ChatConnection = Pick<ServiceConnection, "conversation" | "subscribe" | "send">;

const STATE_LABELS: Record<ConnectionState, string> = {
  connecting: "Connecting…",
  connected: "Connected",
  lost: "Connection lost",
};

/**
 * Renders the desktop client into `root`: the connection state, the conversation and a text field.
 * The page only shows what the connection reports; input is available when `canSend` says so.
 */
export function render(root: HTMLElement, doc: Document, connection: ChatConnection): void {
  const title = appTitle("desktop");
  doc.title = title;

  const heading = doc.createElement("h1");
  heading.textContent = title;

  const status = doc.createElement("p");
  status.dataset["role"] = "status";

  const messages = doc.createElement("ol");
  messages.dataset["role"] = "messages";

  const form = doc.createElement("form");
  const input = doc.createElement("input");
  input.name = "text";
  input.autocomplete = "off";
  input.setAttribute("aria-label", "Message");
  const button = doc.createElement("button");
  button.type = "submit";
  button.textContent = "Send";
  form.append(input, button);

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (text !== "" && connection.send(text)) input.value = "";
  });

  const show = (conversation: Conversation): void => {
    status.dataset["state"] = conversation.state;
    status.textContent = STATE_LABELS[conversation.state];

    messages.replaceChildren(
      ...conversation.entries.map((entry) => {
        const item = doc.createElement("li");
        item.dataset["kind"] = entry.kind;
        item.textContent = entry.text;
        if (entry.kind === "agent" && !entry.complete) item.dataset["pending"] = "";
        return item;
      }),
    );

    input.disabled = !conversation.canSend;
    button.disabled = !conversation.canSend;
  };

  root.replaceChildren(heading, status, messages, form);
  show(connection.conversation);
  connection.subscribe(show);
}
