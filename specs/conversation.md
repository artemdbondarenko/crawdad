# Conversation with the service

The owner opens the client in a browser, sends a text and sees the agent's answer arrive part by
part. This version talks to a **fake agent**: no Claude Code runs yet (see [Fake agent](#fake-agent)).

Areas of this file: `HTTP` (what a port serves), `WS` (the WebSocket handshake), `MSG` (the messages of the conversation), `UI` (what the client shows),
`FAKE` (the fake agent).

## What this protects against

The service runs an agent that changes code and runs programs, so a connection to it is a way to run
code on the owner's machine. The handshake rules (`WS-1`–`WS-4`) stop a web page from another
origin from talking to the service through the owner's browser, including a page that points its
own host name at the service's address (DNS rebinding). Where the service listens, and how the
network address is protected, is in [Addresses and TLS](network.md).

### Limitation of this version

[ADR-0002](../docs/adr/0002-service-and-thin-clients.md), decision 5, requires pairing, a device
key on every connection and TLS (`wss://`). **This version has none of them.** Any process on the
same computer can connect to the service and talk to the agent. With `--host` set to a network
address, so can anyone who reaches that address. Pairing, device keys and TLS come with a separate
security change.

## Scenarios

### S-MSG-1. A conversation

Covers: `NET-1`, `NET-4`, `HTTP-1`, `WS-1`, `WS-2`, `MSG-1`–`MSG-6`, `UI-1`–`UI-4`, `FAKE-1`, `FAKE-2`.

1. The owner starts the service with no options. It prints the address to open:
   `http://127.0.0.1:8460/`.
2. The owner opens that address. The service serves the client from the same port; the client
   connects to the service over WebSocket and shows that it is connected.
3. The owner types `hello world` and sends it. Input is disabled until the answer is complete.
4. The answer appears under the sent text, growing as its parts arrive, and becomes one message:
   `You said: hello world`.
5. When the answer is complete, the client accepts input again.

Exchange over the WebSocket:

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

### S-MSG-2. A message the service does not accept

Covers: `MSG-7`, `MSG-8`.

1. A client connected as in `S-MSG-1` sends a `user.message` with an empty `text`.
2. The service answers with `error` carrying the `session_id` of the connection. No agent answer
   starts.
3. The connection stays open: the next valid `user.message` gets an answer as in `S-MSG-1`.

```
client  → {"type":"user.message","session_id":"3f2b9c1e","text":""}
service → {"type":"error","session_id":"3f2b9c1e","message":"…"}
```

### S-WS-1. A page from another origin

Covers: `WS-1`–`WS-4`.

1. The service runs on `127.0.0.1:8460`.
2. The owner opens `http://localhost:8460/` instead of the printed address. The page loads, but its
   WebSocket handshake carries `Origin: http://localhost:8460` and `Host: localhost:8460`.
3. The service answers `403` without the upgrade, opens no session and writes the refusal to its
   log. The agent is not reached.
4. The client shows that the connection is lost.

### S-UI-1. The service stops

Covers: `UI-5`, `MSG-11`.

1. A client is connected as in `S-MSG-1`.
2. The owner stops the service.
3. The client shows that the connection is lost and no longer accepts input.

### S-UI-2. Spaces around the text

Covers: `UI-6`.

1. A client is connected as in `S-MSG-1`.
2. The owner types `   ` (only spaces) and presses Send. Nothing is sent; the field keeps its
   content.
3. The owner types `  hello  ` and sends it. The client sends `hello`, and the answer is
   `You said: hello`.

## Requirements

### What the port serves

- **HTTP-1.** Each address of the service ([`NET-1`, `NET-2`](network.md)) MUST serve everything on
  its one port:

  | Request | Answer |
  |---|---|
  | `GET /`, `GET /index.html` | the client page |
  | `GET /assets/<file>` | a file of the client |
  | `GET /ws` with a WebSocket upgrade | the WebSocket of the conversation, see `WS-1`–`WS-4` |
  | `POST /pair`, `GET /device` | pairing and the device check, see [Pairing](pairing.md) |
  | anything else, including `GET /ws` without an upgrade | `404` |

- **HTTP-2.** A directory, such as `GET /assets/`, MUST be answered with `403` and no listing;
  `GET /assets` MUST redirect to `/assets/`.

### Handshake

- **WS-1.** The `Host` header of the handshake MUST equal the `Host` of the site of the address
  the request arrived on ([`NET-9`](network.md)), such as `127.0.0.1:8460`.
- **WS-2.** The `Origin` header of the handshake MUST equal the `Origin` of that site, such as
  `http://127.0.0.1:8460`.
- **WS-3.** Letter case MUST be ignored when comparing these headers, and nothing else: other names
  of the same machine do not match. With the service on `127.0.0.1`, a page opened as
  `http://localhost:8460/` is refused; the owner opens the address the service prints.
- **WS-4.** When a header does not match, the service MUST answer `403` without the upgrade, MUST
  NOT open a session or reach the agent, and MUST write the refusal to its log.

### Messages

- **MSG-1.** Every message MUST be one JSON object in one WebSocket text frame of at most
  **64 KiB** (65,536 bytes).
- **MSG-2.** The `type` field names the message; `session_id` names the session it belongs to.
- **MSG-3.** One WebSocket connection MUST be one session. The service MUST send `session.started`
  first; the client MUST use its `session_id` in every message.
- **MSG-4.** An answer MUST be `agent.message.start`, one or more `agent.message.delta` and
  `agent.message.end`, all with the same `message_id`. Each answer MUST have its own `message_id`.
- **MSG-5.** The client MUST join the `text` of the deltas in the order they arrive; the result is
  the whole answer.
- **MSG-6.** Every event of an answer MUST carry the `session_id` of the connection.
- **MSG-7.** A message the schema does not accept MUST be rejected, with no conversions: a number
  or `true` where the schema wants a string is rejected, and so is a field that appears twice.
- **MSG-8.** The service MUST answer with `error`, and keep the connection open, when the client
  sends:
  - a text that is not JSON, or not a message of the schema (unknown `type`, a missing, extra or
    repeated field, a field of the wrong type, an empty `text`);
  - a message only the service sends, such as `agent.message.end`;
  - a `session_id` other than the one of this connection.
- **MSG-9.** When the agent fails to answer a `user.message`, the service MUST answer with `error`
  and keep the connection open. The cause MUST stay in the service log; the client MUST NOT be told
  the details. An answer that had already started MAY stop without its `agent.message.end`.
- **MSG-10.** The `message` of an `error` is for people to read; its wording is not part of the
  contract.
- **MSG-11.** The connection can end in these ways; the client MUST treat each of them as a lost
  connection:

  | Close code | When |
  |---|---|
  | `1009` | the client sent a frame larger than 64 KiB |
  | `1011` | an unexpected failure inside the service |
  | any other code, or none | the service stopped or the connection broke |

### Client

- **UI-1.** The client MUST connect to the service that served the page, over WebSocket, and show
  whether it is connected.
- **UI-2.** After the owner sends a text, input MUST be disabled until the answer is complete.
- **UI-3.** The answer MUST appear under the sent text and grow as its parts arrive, as one message.
- **UI-4.** When the answer is complete, the client MUST accept input again.
- **UI-5.** When the connection ends, the client MUST show that the connection is lost.
- **UI-6.** The client MUST remove whitespace at both ends of the typed text before sending it. If
  nothing is left, it MUST send nothing and keep the input as it is.
- **UI-7.** When the service answers with `error`, the client MUST show its `message` in the
  conversation and accept input again.

### Fake agent

This version has no real agent behind the service; a fake agent inside the service answers.

- **FAKE-1.** The fake agent MUST answer every text with exactly `You said: <text>`.
- **FAKE-2.** The answer MUST be split into parts before each whitespace character (space, tab, line
  feed, carriage return, vertical tab, form feed), so there are always at least two parts and the
  parts joined give the answer back unchanged.
- **FAKE-3.** The fake agent MUST NOT start a process, use a key or make network calls.

## Messages

The exact format of each message is the JSON Schema
[`protocol/schema/crawdad-protocol.schema.json`](../protocol/schema/crawdad-protocol.schema.json).

| `type` | Direction | Fields besides `type` | Meaning |
|---|---|---|---|
| `"session.started"` | service → client | `session_id` | sent right after the connection opens |
| `"user.message"` | client → service | `session_id`, `text` (not empty) | a text for the agent |
| `"agent.message.start"` | service → client | `session_id`, `message_id` | the agent starts an answer |
| `"agent.message.delta"` | service → client | `session_id`, `message_id`, `text` (may be empty) | the next part of the answer |
| `"agent.message.end"` | service → client | `session_id`, `message_id` | the answer is complete |
| `"error"` | service → client | `session_id`, `message` | the last message from the client was rejected |

## Implementation status

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `HTTP-1` | `POST /pair` and `GET /device` answer `404` | #10 |
