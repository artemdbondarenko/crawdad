# Conversation with the service

The owner opens the client in a browser, sends a text and sees the agent's answer arrive part by
part. This version talks to a **fake agent**: no Claude Code runs yet (see [Fake agent](#fake-agent)).

## Scenario

1. The owner starts the service. It prints the address to open, such as
   `http://127.0.0.1:8460/`.
2. The owner opens that address. The service serves the client from the same port; the client
   connects to the service over WebSocket and shows that it is connected.
3. The owner types a text and sends it. Input is disabled until the answer is complete.
4. The answer appears under the sent text, growing as its parts arrive, and becomes one message.
5. When the answer is complete, the client accepts input again.
6. If the service stops, the client shows that the connection is lost.

## Service

- **Address.** The service listens on `127.0.0.1`, port `8460` by default. Both can be changed:
  `--host <address>` and `--port <number>`; port `0` picks any free port.
- **One port for everything:**

  | Request | Answer |
  |---|---|
  | `GET /`, `GET /index.html` | the client page |
  | `GET /assets/<file>` | a file of the client |
  | a directory, such as `GET /assets/` | `403`, no listing; `GET /assets` redirects to `/assets/` |
  | `GET /ws` with a WebSocket upgrade | the WebSocket of the conversation, see [Handshake](#handshake) |
  | anything else, including `GET /ws` without an upgrade | `404` |

## Handshake

The service accepts a WebSocket only from the page it serves itself. Both headers must match the
address the service listens on; letter case is ignored, nothing else is:

- `Host` must be `<host>:<port>` of the service, such as `127.0.0.1:8460`;
- `Origin` must be `http://<host>:<port>`, such as `http://127.0.0.1:8460`.

Other names of the same machine do not match: with the service on `127.0.0.1`, a page opened as
`http://localhost:8460/` is refused. The owner opens the address the service prints.

When a header does not match, the service answers `403` without the upgrade, opens no session and
does not reach the agent. The refusal is written to the service log.

## Messages

Every message is one JSON object in one WebSocket text frame of at most **64 KiB** (65,536 bytes). The `type` field names the message;
`session_id` names the session it belongs to. **One WebSocket connection is one session.** The
exact format of each message is the JSON Schema
[`protocol/schema/crawdad-protocol.schema.json`](../protocol/schema/crawdad-protocol.schema.json);
a message the schema does not accept is rejected, with no conversions: a number or `true` where the
schema wants a string is rejected, and so is a field that appears twice.

| `type` | Direction | Fields besides `type` | Meaning |
|---|---|---|---|
| `"session.started"` | service → client | `session_id` | sent right after the connection opens |
| `"user.message"` | client → service | `session_id`, `text` (not empty) | a text for the agent |
| `"agent.message.start"` | service → client | `session_id`, `message_id` | the agent starts an answer |
| `"agent.message.delta"` | service → client | `session_id`, `message_id`, `text` (may be empty) | the next part of the answer |
| `"agent.message.end"` | service → client | `session_id`, `message_id` | the answer is complete |
| `"error"` | service → client | `session_id`, `message` | the last message from the client was rejected |

### Exchange

```
service → {"type":"session.started","session_id":"3f2b9c1e"}
client  → {"type":"user.message","session_id":"3f2b9c1e","text":"hello world"}
service → {"type":"agent.message.start","session_id":"3f2b9c1e","message_id":"m1"}
service → {"type":"agent.message.delta","session_id":"3f2b9c1e","message_id":"m1","text":"You"}
service → {"type":"agent.message.delta","session_id":"3f2b9c1e","message_id":"m1","text":" said:"}
service → {"type":"agent.message.delta","session_id":"3f2b9c1e","message_id":"m1","text":" hello"}
service → {"type":"agent.message.delta","session_id":"3f2b9c1e","message_id":"m1","text":" world"}
service → {"type":"agent.message.end","session_id":"3f2b9c1e","message_id":"m1"}
```

### Rules

- The service sends `session.started` first; the client uses its `session_id` in every message.
- An answer is `agent.message.start`, one or more `agent.message.delta` and `agent.message.end`,
  all with the same `message_id`. The client joins the `text` of the deltas in the order they
  arrive; the result is the whole answer. Each answer has its own `message_id`.
- Every event of an answer carries the `session_id` of the connection.
- The service answers with `error`, and keeps the connection open, when the client sends:
  - a text that is not JSON, or not a message of the schema (unknown `type`, a missing, extra or
    repeated field, a field of the wrong type, an empty `text`);
  - a message only the service sends, such as `agent.message.end`;
  - a `session_id` other than the one of this connection.

  The service also answers with `error`, and keeps the connection open, when the agent fails to
  answer a `user.message`. The cause stays in the service log; the client is not told the details.
  An answer that had already started may stop without its `agent.message.end`.

  The `message` of an `error` is for people to read; its wording is not part of the contract.
- The connection can end in these ways; the client treats each of them as a lost connection:

  | Close code | When |
  |---|---|
  | `1009` | the client sent a frame larger than 64 KiB |
  | `1011` | an unexpected failure inside the service |
  | any other code, or none | the service stopped or the connection broke |

## Fake agent

This version has no real agent behind the service. A fake agent inside the service answers every
text with exactly `You said: <text>`, split into parts before each ASCII whitespace character (space,
tab, line break), so there
are always at least two parts and the parts joined give the answer back unchanged. It starts no
process, uses no key and makes no network calls.

## Limitation of this version

[ADR-0002](../docs/adr/0002-service-and-thin-clients.md), decision 5, requires pairing, a device
key on every connection and TLS (`wss://`). **This version has none of them.** It only listens on
localhost by default and checks `Origin` and `Host` (see [Handshake](#handshake)). Any process on
the same computer can connect to the service and talk to the agent. With `--host` set to a network
address, so can anyone who reaches that address. Pairing, device keys and TLS come with a separate
security change.
