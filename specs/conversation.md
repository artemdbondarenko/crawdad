# Conversation with the service

The owner opens the client in a browser, sends a text and sees the agent's answer arrive part by
part. This version talks to a **fake agent**: no Claude Code runs yet (see [Fake agent](#fake-agent)).

Areas of this file: `HTTP` (what a port serves), `WS` (the WebSocket handshake), `MSG` (the
messages of the conversation and the life of a connection), `UI` (what the client shows), `FAKE`
(the fake agent).

## What this protects against

The service runs an agent that changes code and runs programs, so a connection to it is a way to run
code on the owner's machine. The handshake rules (`WS-1`–`WS-4`) stop a web page from another
origin from talking to the service through the owner's browser, including a page that points its
own host name at the service's address (DNS rebinding). The device key (`WS-5`) keeps out everyone
who has not paired — another user of the same computer, another device in the network — and
revoking a device (`MSG-14`) cuts it off at once. The page itself is protected too: a strict
content security policy and other headers (`HTTP-3`) keep foreign scripts, frames and leaks of the
address out, and the agent's output is only ever shown as text (`UI-11`), so an answer that contains
markup cannot run in the page that holds the owner's connection. How a device is paired is in
[Pairing and the device key](pairing.md); where the service listens, and how the network address is
protected, is in [Addresses and TLS](network.md).

## Scenarios

### S-MSG-1. A conversation

Covers: `NET-1`, `NET-4`, `HTTP-1`, `WS-1`, `WS-2`, `WS-5`, `MSG-1`–`MSG-6`, `UI-1`–`UI-4`,
`FAKE-1`, `FAKE-2`.

1. The owner starts the service with no options. It prints the address to open:
   `http://127.0.0.1:8460/`.
2. The owner opens that address in a browser paired there (see `S-PAIR-1` in
   [Pairing](pairing.md)). The service serves the client from the same port; the client connects to
   the service over WebSocket with its device key and shows that it is connected.
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

### S-MSG-3. A long pause

Covers: `MSG-12`, `MSG-13`.

1. A client is connected as in `S-MSG-1`. The owner sends nothing for ten minutes.
2. The service pings the client every 30 seconds; the browser answers by itself. The connection
   stays open, and the next text gets an answer in the same session.
3. The owner's phone leaves the network without closing the connection. About 90 seconds later the
   service closes that connection.

```
client  → {"type":"user.message","session_id":"3f2b9c1e","text":""}
service → {"type":"error","session_id":"3f2b9c1e","message":"…"}
```

### S-WS-1. A page from another origin

Covers: `WS-1`–`WS-4`, `UI-1`.

1. The service runs on `127.0.0.1:8460`.
2. The owner opens `http://localhost:8460/` instead of the printed address. The page loads, but its
   WebSocket handshake carries `Origin: http://localhost:8460` and `Host: localhost:8460`.
3. The device check and the handshake are refused with `403`; the WebSocket gets no upgrade, no
   session is opened, and the refusal is written to the service log. The agent is not reached.
4. The client says that the page must be opened at the address the service prints, and does not
   retry.

### S-WS-2. A connection without a key

Covers: `WS-5`, `WS-6`.

1. A process of another OS user on the same computer opens a WebSocket to `127.0.0.1:8460` with the
   right `Origin` and `Host`, but without a device key — or with a made-up one, or with a key paired
   on the other address.
2. The service answers `401` without the upgrade, opens no session and writes the refusal, with the
   address it came from, to its log. The agent is not reached.

### S-MSG-4. Revoking a device

Covers: `MSG-11`, `MSG-14`, `UI-8`.

1. A laptop is paired as `laptop`; its client is open in two tabs. A phone is connected too.
2. The owner revokes `laptop` on the service machine.
3. Both tabs of the laptop are closed at once with the code `4001`. Each shows
   **This device was unpaired** and the pairing screen, and makes no further attempts to connect.
4. The phone keeps working.
5. When the laptop's page is reloaded, `GET /device` answers `401`, and the pairing screen is shown.

### S-UI-1. The service stops and comes back

Covers: `UI-5`, `UI-9`, `UI-10`, `MSG-11`.

1. A client is connected as in `S-MSG-1`; an answer is arriving.
2. The owner stops the service. The answer stops growing and is marked **Interrupted**.
3. The client shows that the connection is lost and no longer accepts input. It does not say that
   the device was unpaired.
4. The client keeps trying to reach the service, waiting longer between attempts.
5. The owner starts the service again. The client's next attempt finds its key still valid and
   connects in a new session; no code is asked.
6. The earlier messages stay on the page. Below them the client shows
   **Reconnected — new session. The agent does not remember the messages above.** It accepts input
   again once the new session has started.

### S-UI-2. Spaces around the text

Covers: `UI-6`.

1. A client is connected as in `S-MSG-1`.
2. The owner types `   ` (only spaces) and presses Send. Nothing is sent; the field keeps its
   content.
3. The owner types `  hello  ` and sends it. The client sends `hello`, and the answer is
   `You said: hello`.

### S-UI-3. Markup in an answer

Covers: `UI-11`.

1. A client is connected as in `S-MSG-1`.
2. The owner sends `<img src=x onerror=alert(1)>`.
3. The answer is shown literally as `You said: <img src=x onerror=alert(1)>`: no image is loaded
   and no script runs. The same holds for the `message` of an `error`.

### S-HTTP-1. The page in a foreign frame

Covers: `HTTP-3`.

1. A foreign site embeds `http://127.0.0.1:8460/` in a frame.
2. The browser refuses to show the page in the frame; the foreign site cannot overlay it or make the
   owner click on it.

## Requirements

### What the port serves

- **HTTP-1.** Each address of the service ([`NET-1`, `NET-2`](network.md)) MUST serve everything on
  its one port:

  | Request | Answer |
  |---|---|
  | `GET /`, `GET /index.html` | the client page |
  | `GET /assets/<file>` | a file of the client |
  | `GET /ws` with a WebSocket upgrade | the WebSocket of the conversation, see `WS-1`–`WS-6` |
  | `POST /pair`, `GET /device` | pairing and the device check, see [Pairing](pairing.md) |
  | anything else, including `GET /ws` without an upgrade | `404` |

- **HTTP-2.** A directory, such as `GET /assets/`, MUST be answered with `403` and no listing;
  `GET /assets` MUST redirect to `/assets/`.
- **HTTP-3.** Every HTTP answer of the service — the page, its files, `/pair`, `/device` and errors
  such as `404` — MUST carry these headers; only the answer to a WebSocket handshake MAY lack them:

  | Header | Value |
  |---|---|
  | `Content-Security-Policy` | `default-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'` |
  | `X-Content-Type-Options` | `nosniff` |
  | `Referrer-Policy` | `no-referrer` |
  | `Cross-Origin-Opener-Policy` | `same-origin` |

- **HTTP-4.** The client page MUST work under that policy: it MUST have no inline script, inline
  style or event handler attribute, and MUST load nothing from another origin.

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
- **WS-5.** After the headers, the handshake MUST carry a key of an active device paired on this
  address, in the key cookie ([`KEY-2`, `KEY-4`, `KEY-7`](pairing.md)). Otherwise the service MUST
  answer `401` without the upgrade, MUST NOT open a session or reach the agent, and MUST write the
  refusal to its log with the address it came from, but never the key.
- **WS-6.** A browser cannot see why a handshake was refused, so the client MUST NOT rely on the
  `401` or `403` of the handshake; it learns whether its key works from `GET /device`
  ([`KEY-6`](pairing.md)).

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
- **MSG-11.** The connection can end in these ways:

  | Close code | When | The client |
  |---|---|---|
  | `4001` `unpaired` | the device was revoked (`MSG-14`) | shows that the device was unpaired (`UI-8`) |
  | `1009` | the client sent a frame larger than 64 KiB | treats it as a lost connection (`UI-5`) |
  | `1011` | an unexpected failure inside the service | treats it as a lost connection |
  | any other code, or none | the service stopped, the connection broke or stopped answering (`MSG-13`) | treats it as a lost connection |

- **MSG-12.** The service MUST send a WebSocket ping on every open connection every 30 seconds. A
  connection MUST NOT be closed for lack of messages while the client answers the pings.
- **MSG-13.** The service MUST close a connection that has carried nothing — no message, ping or
  pong — for 90 seconds.
- **MSG-14.** When the owner revokes a device, the service MUST close all open connections of that
  device at once with the code `4001` and the reason `unpaired`, and MUST NOT touch the connections
  of other devices. A handshake that had passed the key check just before the revocation MUST be
  closed the same way; a handshake whose key is checked after the revocation MUST be refused as in
  `WS-5`. Once the revocation is done, the device MUST have no open connections.

### Client

- **UI-1.** The client MUST connect to the service that served the page, over WebSocket, and show
  whether it is connected. Before it connects, it MUST ask `GET /device`: on `200` it connects; on
  `401` it shows the pairing screen ([`SCREEN-1`](pairing.md)); on `403` it says that the page must
  be opened at the address the service prints, and does not retry.
- **UI-2.** After the owner sends a text, input MUST be disabled until the answer is complete.
- **UI-3.** The answer MUST appear under the sent text and grow as its parts arrive, as one message.
- **UI-4.** When the answer is complete, the client MUST accept input again.
- **UI-5.** When the connection ends with any code but `4001`, the client MUST show that the
  connection is lost and stop accepting input. Then it MUST try again as in `UI-1`, starting with
  `GET /device`; while the service cannot be reached it MUST keep trying, first after 1 second and
  then twice as long each time, up to 30 seconds between attempts. A new connection is a new
  session.
- **UI-6.** The client MUST remove whitespace at both ends of the typed text before sending it. If
  nothing is left, it MUST send nothing and keep the input as it is.
- **UI-7.** When the service answers with `error`, the client MUST show its `message` in the
  conversation and accept input again.
- **UI-8.** When the connection is closed with `4001`, the client MUST show
  **This device was unpaired** and the pairing screen, and MUST NOT try to connect again by itself.
- **UI-9.** When the connection ends, the messages already shown MUST stay on the page. An answer
  that had not ended MUST be marked **Interrupted** and MUST NOT grow any more.
- **UI-10.** When the client connects again in a new session, it MUST show, below the earlier
  messages, **Reconnected — new session. The agent does not remember the messages above.** It MUST
  accept input only after `session.started` of the new session.
- **UI-11.** The client MUST show the text of answers and the `message` of errors only as text,
  never as markup.

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
| `HTTP-3` | no security headers | #10 |
| `WS-5` | any handshake with the right `Origin` and `Host` is accepted; there are no keys | #10 |
| `MSG-11`, `MSG-14` | no code `4001`; devices cannot be revoked | #10 |
| `MSG-12`, `MSG-13` | no pings; a connection with no messages for 30 seconds is closed with `1001` | #17 |
| `UI-1` | the client connects at once, without `GET /device`, and treats a refused handshake as a lost connection | #10 |
| `UI-5` | the client shows that the connection is lost and never tries again | #10 |
| `UI-8` | no pairing, so nothing to show | #10 |
| `UI-9` | an unfinished answer is not marked | #10 |
| `UI-10` | the client never connects again | #10 |
