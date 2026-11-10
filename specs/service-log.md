# The service log

The service writes a log for the owner: who paired, connected and was revoked, what was refused and
from where, and what went wrong. This file says which events are written, at which level and with
what in them, how values from outside are written, what never gets into the log, and how a source
that repeats itself is kept from flooding it.

Area of this file: `LOG`.

Each event has its own rule elsewhere — a refused handshake in
[Conversation with the service](conversation.md), a wrong code in
[Pairing and the device key](pairing.md), a certificate in [Addresses and TLS](network.md), the
control socket in [Owner's commands](owner-commands.md). This file collects them in one table
([Events](#events)) and sets the rules all of them follow. Where the log goes and how it is kept is
not part of the contract.

## What this protects against

- **A key used where it should not be.** Every accepted connection is written with the device name
  and the address it came from (`LOG-2`), so the owner can spot a copied key.
- **Guessing and probing the owner never hears of.** Wrong codes and refused requests are written
  with the address they came from (`LOG-2`).
- **A forged or garbled log.** A value from outside — a device name made from a `User-Agent`, a
  header, an address — cannot start a new line, move the cursor or reorder the text of a line when
  the log is read in a terminal (`LOG-3`).
- **Secrets in the log.** Codes, keys and private keys are never written (`LOG-4`), so whoever can
  read the log cannot pair or connect.
- **A stranger who fills the log.** Repeated warnings from one source are written as one entry per
  minute with their number (`LOG-5`); a device that reconnects writes no warnings at all (`LOG-6`).

Not protected against:

- **Processes of the OS user who runs the service.** They can read and change the log.

## Scenarios

### S-LOG-1. Pairing, connecting, revoking

Covers: `LOG-1`, `LOG-2`, `LOG-6`.

1. The owner pairs a laptop on `http://127.0.0.1:8460/` with the name `laptop`. The service writes
   an INFO entry that `laptop` was paired from `127.0.0.1`.
2. The client of the laptop connects in two tabs. For each handshake the service writes an INFO entry
   that `laptop` connected from `127.0.0.1`.
3. The owner revokes `laptop`. The service writes an INFO entry that `laptop` was revoked and that
   two connections were closed.
4. The owner reloads a tab of the laptop. `GET /device` answers `401`; nothing is written.

The wording is not part of the contract; the entries may look like this:

```
INFO: Device paired: laptop from 127.0.0.1
INFO: Device connected: laptop from 127.0.0.1
INFO: Device connected: laptop from 127.0.0.1
INFO: Device revoked: laptop, closed 2 connections
```

### S-LOG-2. Someone guesses codes

Covers: `LOG-4`, `LOG-5`.

1. The owner has a valid code. Within one minute, a device at `100.101.102.7` in the owner's VPN
   sends 40 wrong codes to `POST /pair`.
2. At the first one the service writes a warning about a wrong pairing code from `100.101.102.7`.
3. The other 39 are not written one by one. At the end of the minute the service writes one warning
   that 39 more wrong codes came from `100.101.102.7`.
4. No entry contains a code, the right one or a wrong one.
5. A wrong code from another address, `100.101.102.8`, within the same minute is written at once:
   each source has its own minute.

### S-LOG-3. A header that tries to take over the terminal

Covers: `LOG-3`.

1. The owner reads the log in a terminal. A script in the owner's VPN sends `POST /pair` with an
   `Origin` that starts with the escape sequence that clears the screen, `ESC [2J`, followed by
   `INFO: Device revoked: phone, closed 1 connections`.
2. The request is refused for its `Origin`, and the service writes a warning with that `Origin`. The
   escape character is written as `\x1B`: the screen is not cleared, and the forged text stays
   inside the warning instead of passing for an entry of its own.

## Requirements

- **LOG-1.** Every entry MUST have a level: INFO for what happens in normal work, WARNING for a
  request the service refused or a failure it went on after, ERROR for a failure that stopped what
  the owner asked for.
- **LOG-2.** The service MUST write every event of the table [Events](#events), at its level and with
  everything the table lists for it. The wording of an entry is not part of the contract.
- **LOG-3.** A value that comes from outside the service — a device name, an address, the value of a
  header, the name of an OS user, a file name, the text of a cause — MUST be written with control
  characters, direction controls and line separators escaped and a backslash doubled, and cut to at
  most 200 characters, as [`PAIRCMD-8`](owner-commands.md) says for the commands. One entry MUST
  stay one line.
- **LOG-4.** An entry MUST NOT contain a pairing code, a device key or its hash, the value of a cookie, or
  a private key ([`PAIR-10`](pairing.md)).
- **LOG-5.** The warnings the table marks as aggregated MUST be written like this. The first warning
  of a kind from a source MUST be written at once. Further warnings of the same kind from the same
  source within the minute after it MUST NOT be written one by one; at the end of that minute the
  service MUST write one warning with their number, if there were any. The next warning after that
  minute starts a new one. A source is the address a request came from, or, on the control socket,
  the OS user on the other end.
- **LOG-6.** `GET /device` MUST NOT be written as a warning or an error when it answers `200` or
  `401`: a client asks it every time it connects again, and a revoked device would fill the log.
- **LOG-7.** When the service cannot record the last connection of a device
  ([`DEV-2`](owner-commands.md)), it MUST still accept the connection and write a warning with the
  device name, the address and the cause: the key has passed, and a failing disk must not cut off
  every device.

## Messages

### Events

| Event | Level | What the entry holds | Aggregated (`LOG-5`) | Rule |
|---|---|---|---|---|
| A device is paired | INFO | device name, address | — | `PAIR-6` |
| A WebSocket handshake is accepted | INFO | device name, address | — | `WS-5` |
| A device is revoked | INFO | device name, number of connections closed | — | `MSG-14`, `DEV-6` |
| A renewed certificate is taken | INFO | certificate file, valid until | — | `TLS-5` |
| `POST /pair` is refused for `Origin` or `Host` | WARNING | address, `Origin`, `Host` | yes | `PAIR-7` |
| A wrong pairing attempt | WARNING | address | yes | `PAIR-8` |
| `GET /device` is refused for `Origin` or `Host` | WARNING | address, `Origin`, `Host` | yes | `KEY-6` |
| A WebSocket handshake is refused for `Origin` or `Host` | WARNING | address, `Origin`, `Host` | yes | `WS-4` |
| A WebSocket handshake is refused for its key | WARNING | address | yes | `WS-5` |
| A control socket connection of another OS user | WARNING | that user | yes | `CMD-5` |
| The last connection of a device cannot be recorded | WARNING | device name, address, cause | — | `LOG-7` |
| The agent fails to answer | WARNING | session, cause | — | `MSG-9` |
| A renewed certificate is not taken | WARNING | certificate file, why | — | `TLS-5` |
| The certificate expires in less than 14 days | WARNING | certificate file, expiry date | — | `TLS-6` |
| `XDG_RUNTIME_DIR` does not give the control socket a place | WARNING | where the socket goes instead | — | `FILE-2` |
| A paired device cannot be saved | ERROR | address, cause | — | `PAIR-9` |
| A revocation cannot be saved | ERROR | device name, cause | — | `DEV-8` |

## Implementation status

The code on `main` has no pairing, no devices and no TLS, so most events cannot happen yet.

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `LOG-2` | an accepted handshake is written with its `Origin`, without a device name or address; there are no pairing, revoking or certificate events | #10 |
| `LOG-3` | values are written as they came, unescaped and uncut | #10 |
| `LOG-5` | every refused handshake is written on its own | #10 |
| `LOG-7` | no devices, so no last connection | #10 |
