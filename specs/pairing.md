# Pairing and the device key

A browser talks to the service only after it has been paired. The owner gets a one-time code on the
service machine and enters it on the client page; the service answers with a device key, kept by the
browser in a cookie. From then on the browser is a *device* of the owner on that address.

Areas of this file: `PAIR` (the code and its exchange for a key), `KEY` (the device key and the
device check), `SCREEN` (the pairing screen of the client).

How the owner gets a code, lists devices and revokes them is not specified yet. Where a scenario
needs it, it says only that the owner gets a code on the service machine. Using the key on the
WebSocket is in [Conversation with the service](conversation.md).

## What this protects against

- **Anyone who can reach the service but is not the owner** — another user of the same computer, or
  another device in the owner's VPN. Without a code from the service machine they cannot get a key.
  Guessing is hopeless: one code at a time, six digits, five minutes, and five wrong attempts from all
  sources together burn it (`PAIR-2`–`PAIR-5`).
- **A foreign web page in the owner's browser.** It cannot spend the owner's attempts or pair itself:
  the code exchange checks `Origin` and `Host` before it looks at the code (`PAIR-7`). A link with a
  code fills in the field but sends nothing until the user presses the button (`SCREEN-3`).
- **Theft of the key by scripts of a page.** The key is in an `HttpOnly; SameSite=Strict` cookie
  (`KEY-2`), and the service stores only its hash (`KEY-3`).
- **Leaking the code.** A code in a link travels in the URL fragment, which the browser never sends
  to the server (`SCREEN-2`).

Not protected against:

- **Processes of the OS user who runs the service.** They act as the owner: they can get a code on
  the service machine and read the key from the browser profile.
- **Other web servers on the same host.** Browsers send a cookie to every port of its host, so the
  key cookie also reaches any other server the owner opens on the same host name or address, such as
  another local web app on `127.0.0.1`. The port in the cookie's name only keeps services on
  different ports from overwriting each other's keys; it does not hide the key from them.

## Scenarios

### S-PAIR-1. Pairing the browser on this machine

Covers: `PAIR-1`, `PAIR-6`, `PAIR-7`, `KEY-1`, `KEY-2`, `KEY-6`, `SCREEN-1`, `SCREEN-4`.

1. The owner opens `http://127.0.0.1:8460/` in a browser that has not been paired. The page asks
   `GET /device`, gets `401` and shows the pairing screen instead of the conversation.
2. The owner gets a code on the service machine, `123456`, and enters it on the page.
3. The page sends it; the service answers `204` and sets the key cookie. The page shows the
   conversation.
4. The owner reloads the page and restarts the browser. The page asks `GET /device`, gets `200`,
   and goes straight to the conversation; no code is asked again.

```
GET /device
Host: 127.0.0.1:8460
                                       → 401 {"error":"not_paired"}

POST /pair
Host: 127.0.0.1:8460
Origin: http://127.0.0.1:8460
{"code":"123456"}
                                       → 204
                                         Set-Cookie: crawdad_device_8460=<key>; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict

GET /device
Host: 127.0.0.1:8460
Cookie: crawdad_device_8460=<key>
                                       → 200 {"name":"Firefox on Linux"}
                                         Set-Cookie: crawdad_device_8460=<key>; Path=/; Max-Age=34560000; HttpOnly; SameSite=Strict
```

### S-PAIR-2. Pairing a phone by a link

Covers: `PAIR-1`, `KEY-2`, `KEY-5`, `SCREEN-2`, `SCREEN-3`.

1. The service runs with a network address, `https://laptop.example.ts.net:8460/`. The owner gets a
   code on the service machine together with a link, also offered as a QR code:
   `https://laptop.example.ts.net:8460/#pair=123456`.
2. The owner opens the link on the phone. The pairing screen shows the code already in the field;
   nothing is sent yet. The `#pair=…` part disappears from the address bar.
3. The owner presses **Pair this device**. The phone is paired; its cookie is
   `__Host-crawdad_device_8460`, with `Secure`.
4. In the requests that reach the service, the code appears only in the body of `POST /pair`, never
   in a URL.

### S-PAIR-3. Wrong codes

Covers: `PAIR-3`–`PAIR-5`, `PAIR-8`.

1. The owner has a valid code. Someone else in the VPN enters wrong codes on the pairing screen; each
   gets `401 {"error":"invalid_code"}` and a warning with their address in the service log.
2. After the fifth wrong attempt the code is burned: the owner's correct code now gets the same
   `401`. The owner needs a new code.
3. In a separate run, the owner makes two mistakes and then enters the right code: the device is
   paired.
4. Entering six digits when no code is valid, or after the code expired, gets the same `401` and adds
   no device.

### S-PAIR-4. A foreign page sends codes

Covers: `PAIR-7`, `SCREEN-3`.

1. The owner has a valid code. A foreign page open in the owner's browser posts codes to
   `http://127.0.0.1:8460/pair` and opens `http://127.0.0.1:8460/#pair=000000` in new windows.
2. Every post is refused with `403` before the code is looked at; no attempt is spent. The opened
   pages only fill in the field.
3. A page that reaches the service through its own name pointing at `127.0.0.1` is refused by
   `Host` the same way.
4. The owner's own code then pairs the owner's device.

### S-KEY-1. One browser, two addresses

Covers: `KEY-4`, `KEY-5`.

1. The browser on the service machine is paired on `http://127.0.0.1:8460/`.
2. The owner opens the network address `https://laptop.example.ts.net:8460/` in the same browser.
   `GET /device` there answers `401`: the key of the loopback address does not work on the network
   address. The page shows the pairing screen.
3. After pairing with a second code, the browser is two devices, one on each address.

## Requirements

### The code and its exchange

- **PAIR-1.** The owner MUST be able to get a pairing code only on the service machine. A code is
  exactly six ASCII digits, chosen at random.
- **PAIR-2.** At most one code MUST be valid at a time: getting a new code ends the previous one.
- **PAIR-3.** A code MUST be valid for 5 minutes and MUST pair at most one device.
- **PAIR-4.** A wrong attempt MUST be counted against the valid code, from all sources together. The
  fifth wrong attempt MUST burn the code: after it, the right code is refused too. Fewer than five
  wrong attempts do not stop the right code.
- **PAIR-5.** An attempt when no code is valid MUST be refused like a wrong code. It is not counted
  against any later code.
- **PAIR-6.** `POST /pair` with the body `{"code":"<code>"}` and a valid code MUST pair a new device
  and answer `204` with the key cookie (`KEY-2`).
- **PAIR-7.** `POST /pair` MUST check `Origin` and `Host` against the site of the address it arrived
  on ([`NET-9`](network.md)), as the WebSocket handshake does, before it looks at the body. A
  mismatch, or a missing `Origin`, MUST be answered `403 {"error":"forbidden"}` and MUST NOT count
  as an attempt.
- **PAIR-8.** A wrong, expired, used or burned code MUST be answered `401 {"error":"invalid_code"}`;
  the answer MUST NOT say which of these it was. So MUST a body that is not that JSON object, that is
  larger than 1 KiB, or whose `code` is not a string of exactly six ASCII digits; each counts as a
  wrong attempt. Every wrong attempt MUST be written to the service log with the address it came
  from.
- **PAIR-9.** When the service cannot save the new device after a correct code, it MUST answer
  `500 {"error":"server_error"}`; the code stays used.
- **PAIR-10.** Codes and keys MUST NOT be written to the service log.

### The device key

- **KEY-1.** Each paired device MUST get its own key: 32 random bytes, encoded as base64url.
- **KEY-2.** The key MUST be sent in the cookie `crawdad_device_<port>` on the loopback address and
  `__Host-crawdad_device_<port>` on the network address, where `<port>` is the port of that address.
  The cookie MUST have `Path=/`, `Max-Age=34560000` (400 days), `HttpOnly` and `SameSite=Strict`,
  and on the network address also `Secure`.
- **KEY-3.** The service MUST store only the SHA-256 hash of a key, never the key itself.
- **KEY-4.** A key MUST work only on the address where its device was paired: loopback or the
  network address.
- **KEY-5.** A browser that is paired on both addresses MUST be two devices, each with its own key.
- **KEY-6.** `GET /device` MUST check `Host` against the site of the address, and `Origin` only when
  the request has one; a mismatch MUST be answered `403 {"error":"forbidden"}`. With a cookie that
  holds a key of an active device paired on this address, it MUST answer `200 {"name":"<device
  name>"}` and set the same cookie again, with a fresh `Max-Age`. Otherwise it MUST answer
  `401 {"error":"not_paired"}` and leave the cookie as it is.
- **KEY-7.** When a browser sends several cookies with the key cookie's name, the service MUST
  accept the request if any of them holds a working key.
- **KEY-8.** Each device MUST have a name, unique among active devices; `GET /device` returns it.
  How the name is chosen is not part of the contract.

### HTTP of pairing

- **PAIR-11.** `/pair` MUST accept only `POST` and `/device` only `GET`; any other method MUST be
  answered `405 {"error":"method_not_allowed"}`.
- **PAIR-12.** Every answer of `/pair` and `/device` MUST carry `Cache-Control: no-store`.

### The pairing screen

- **SCREEN-1.** Before it connects, the client MUST ask `GET /device`. On `401` it MUST show the
  pairing screen — a field for the code and the button **Pair this device** — instead of the
  conversation.
- **SCREEN-2.** When the page address has the fragment `#pair=<code>`, the client MUST put the code
  into the field and remove the fragment from the address bar.
- **SCREEN-3.** The client MUST send a code only when the user presses **Pair this device**, never
  by itself.
- **SCREEN-4.** On `204` the client MUST leave the pairing screen and connect. On `401` it MUST say
  that the code is not valid and keep the screen.

## Messages

| Request | Body | Answers |
|---|---|---|
| `POST /pair` | `{"code":"123456"}` | `204` + key cookie; `401 {"error":"invalid_code"}`; `403 {"error":"forbidden"}`; `500 {"error":"server_error"}` |
| `GET /device` | — | `200 {"name":"…"}` + key cookie; `401 {"error":"not_paired"}`; `403 {"error":"forbidden"}` |
| either, with another method | — | `405 {"error":"method_not_allowed"}` |

The `error` values are part of the contract. All bodies are JSON (`application/json`).

## Implementation status

The code on `main` has no pairing: `/pair` and `/device` answer `404`, and the client opens the
conversation right away.

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `PAIR-1`–`PAIR-12` | no codes, no `POST /pair` | #10 |
| `KEY-1`–`KEY-8` | no device keys, no `GET /device` | #10 |
| `SCREEN-1`–`SCREEN-4` | no pairing screen | #10 |
