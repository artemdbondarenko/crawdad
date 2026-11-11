import {
  appTitle,
  pairDevice,
  pairingCodeFromHash,
  type ConnectionState,
  type Conversation,
  type PairFetch,
  type ServiceConnection,
} from "@crawdad/shared";

/** The part of the connection the page uses; tests pass a fake one. */
export type ChatConnection = Pick<
  ServiceConnection,
  "conversation" | "subscribe" | "send" | "start"
>;

/** What the page needs besides the connection; `fetch` defaults to the browser's. */
export interface PageDependencies {
  fetch?: PairFetch;
}

const STATE_LABELS: Record<ConnectionState, string> = {
  connecting: "Connecting…",
  connected: "Connected",
  lost: "Connection lost",
  unpaired: "This device is not paired",
  revoked: "This device was unpaired",
  wrong_address: "Open this page at the address Crawdad prints when it starts",
};

/** States in which the page shows the pairing screen instead of the chat. */
const PAIRING_STATES: ReadonlySet<ConnectionState> = new Set(["unpaired", "revoked"]);

/**
 * Renders the desktop client into `root`: the connection state, then either the conversation with a
 * text field or, while the device is not paired, the pairing screen. A `#pair=<code>` link only fills
 * the code field and is removed from the address; the code is sent when the owner presses the button.
 */
export function render(
  root: HTMLElement,
  doc: Document,
  connection: ChatConnection,
  dependencies: PageDependencies = {},
): void {
  const title = appTitle("desktop");
  doc.title = title;

  const heading = doc.createElement("h1");
  heading.textContent = title;

  const status = doc.createElement("p");
  status.dataset["role"] = "status";

  const messages = doc.createElement("ol");
  messages.dataset["role"] = "messages";

  const form = doc.createElement("form");
  form.dataset["role"] = "chat";
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

  const pairing = pairingForm(doc, connection, dependencies.fetch);

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

    const pairingShown = PAIRING_STATES.has(conversation.state);
    pairing.hidden = !pairingShown;
    messages.hidden = pairingShown;
    form.hidden = pairingShown;
  };

  root.replaceChildren(heading, status, pairing, messages, form);
  show(connection.conversation);
  connection.subscribe(show);
}

/** The pairing screen: the code field, the button and the error line. Fills the code from a link. */
function pairingForm(
  doc: Document,
  connection: ChatConnection,
  fetchPair: PairFetch | undefined,
): HTMLFormElement {
  const form = doc.createElement("form");
  form.dataset["role"] = "pairing";

  const hint = doc.createElement("p");
  hint.textContent = "Run crawdad pair on the computer with Crawdad and enter the code it shows.";

  const code = doc.createElement("input");
  code.name = "code";
  code.inputMode = "numeric";
  code.autocomplete = "one-time-code";
  code.maxLength = 6;
  code.placeholder = "6-digit code";
  code.setAttribute("aria-label", "Pairing code");

  const button = doc.createElement("button");
  button.type = "submit";
  button.textContent = "Pair this device";

  const error = doc.createElement("p");
  error.dataset["role"] = "pairing-error";
  error.setAttribute("role", "alert");

  form.append(hint, code, button, error);

  const view = doc.defaultView;
  const linked = view ? pairingCodeFromHash(view.location.hash) : undefined;
  if (view && linked !== undefined) {
    code.value = linked;
    // Keeps the code out of bookmarks and the address bar; path and query stay.
    const { pathname, search } = view.location;
    view.history.replaceState(view.history.state, "", pathname + search);
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const entered = code.value.trim();
    if (entered === "") {
      error.textContent = "Enter the 6-digit code";
      return;
    }
    error.textContent = "";
    button.disabled = true;
    void pairDevice(entered, fetchPair).then((result) => {
      button.disabled = false;
      if (result.paired) {
        code.value = "";
        connection.start();
      } else {
        error.textContent = result.error;
      }
    });
  });

  return form;
}
