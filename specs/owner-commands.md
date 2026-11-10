# Owner's commands

The owner manages the service with commands run on the service machine: getting a pairing code,
listing devices, revoking one, exporting the service's own root certificate. This file says how a
command reaches the running service, who may use the commands, that one OS user runs one service,
where the service keeps its files, how the owner gets a pairing code, lists and revokes devices,
and exports the root certificate.

Areas of this file: `CMD` (reaching the service, who may use the commands, one service per OS user),
`FILE` (the files of the service), `PAIRCMD` (`crawdad pair`), `DEV` (`crawdad devices` and
`crawdad revoke`), `CA` (`crawdad tls export-ca`).

What a code is and how a browser exchanges it for a device key is in
[Pairing and the device key](pairing.md). The service's own root certificate is in
[Addresses and TLS](network.md).

## What this protects against

- **Another user of the same computer.** They cannot reach the service's commands: the control
  socket is closed to other OS users by its permissions, and the service checks the user on the
  other end as well (`CMD-5`). They cannot read the device registry or the root key either: every
  file and directory of the service is open to its OS user only (`FILE-4`).
- **Two services over the same files.** A second service of the same OS user refuses to start
  (`CMD-7`), so two services never write the device registry or create the root certificate at the
  same time, and a new service never takes the control socket from a running one.
- **A file half written by a crash.** The service replaces a file whole or not at all (`FILE-5`).
- **A crafted browser that writes to the owner's terminal.** The name, address and `User-Agent` a
  browser brings are printed with control and direction characters escaped (`PAIRCMD-8`), so they
  cannot move the cursor, recolour the terminal or reorder the line.
- **Wrong attempts the owner never hears of.** A code burned by wrong attempts is reported with the
  addresses they came from — to the waiting `crawdad pair`, or, when nobody waited, to the next one
  (`PAIRCMD-3`, `PAIRCMD-5`).

Not protected against:

- **Processes of the OS user who runs the service.** They act as the owner: they can run the
  commands and read the files.
- **The superuser of the machine.**

Accepted limitation: Crawdad assumes one service per machine. Nothing stops another OS user from
starting a service of their own; it has its own files, socket and devices, and is a separate service
on another port.

- **A lost or stolen device.** The owner revokes it on the service machine: its key stops working at
  once, on every address, and its open connections are closed (`DEV-6`). A revocation is kept across
  restarts (`DEV-10`).
- **A key in use somewhere unexpected.** `crawdad devices` shows when, from which address and with
  which browser each device last connected (`DEV-1`, `DEV-2`), so the owner can spot a key used from
  a place it should not be.

- **A root certificate swapped on the way to a device.** The owner gets the root only on the
  service machine (`CA-1`), and its fingerprint is printed apart from the certificate, in the same
  form as at the service's start (`CA-2`), so the owner can compare it with the one the device
  shows.
- **The root's private key leaving the machine.** The command never prints the key (`CA-3`); only the
  certificate is meant to be copied to devices.

## Scenarios

### S-CMD-1. A command when the service is not running

Covers: `CMD-2`, `CMD-3`.

1. The service is not running. The owner runs `crawdad devices`.
2. The command prints to its error output that Crawdad is not running and how to start it, and exits
   with code `1`:

   ```
   Crawdad is not running; start it with: crawdad
   ```

3. The command does not start the service.

### S-CMD-2. A command of another OS user

Covers: `CMD-2`, `CMD-5`, `FILE-2`, `FILE-4`.

1. The owner's service is running. Another user of the same computer, `guest`, runs
   `crawdad devices`.
2. The command looks for the service of `guest`, in the directories of `guest`, finds none and
   answers as in `S-CMD-1`.
3. A program of `guest` connects to the owner's control socket by its path. The operating system
   refuses: the directory of the socket is open to the owner only.
4. The owner has widened the permissions of that directory by mistake, and `guest` connects again.
   The service closes the connection without an answer and writes a warning with the user `guest`
   to its log. Nothing is done.

### S-CMD-3. Starting a second service

Covers: `CMD-7`.

1. The owner's service is running with two paired devices.
2. The owner starts the service again, in another terminal, with any options.
3. The second start prints that Crawdad is already running, listens on no address and exits with
   code `1`:

   ```
   Crawdad is already running
   ```

4. The running service and its devices are unaffected; `crawdad devices` still reaches it.

### S-CMD-4. Starting after a crash

Covers: `CMD-8`.

1. The service is killed with `kill -9`; its control socket is left on disk.
2. The owner starts the service again. It starts as usual and takes over the control socket.
3. `crawdad devices` reaches the new service.

### S-FILE-1. The files after pairing

Covers: `FILE-1`–`FILE-4`.

1. The owner has neither `XDG_DATA_HOME` nor `XDG_RUNTIME_DIR` set to change, and `XDG_RUNTIME_DIR`
   is `/run/user/1000`, as the login session sets it. The owner starts the service with
   `--host 192.168.1.20` and pairs a phone.
2. The service keeps its data in `~/.local/share/crawdad/`: the device registry and, in `tls/`, its
   own root certificate and the root's private key. The control socket is
   `/run/user/1000/crawdad/control.sock`.
3. Every directory of the service is `drwx------` and every file `-rw-------`, owned by the owner.
   Another OS user can list none of them.

### S-PAIRCMD-1. Pairing the browser on this machine

Covers: `PAIRCMD-1`–`PAIRCMD-3`, `PAIRCMD-6`.

1. The service runs without `--host`. The owner runs `crawdad pair`. It prints the code, how long it
   is valid, a link for this machine and a hint for other devices, then waits:

   ```
   Pairing code: 123456 (valid for 5 minutes)
   On this machine, open: http://127.0.0.1:8460/#pair=123456
   To pair a phone, start Crawdad with --host <private address> and run pair again.
   Waiting for the code to be used; Ctrl+C stops waiting and the code stays valid.
   ```

2. The owner opens the link in Firefox on the same machine and presses **Pair this device**
   (`S-PAIR-1`, `S-PAIR-2` in [pairing.md](pairing.md)).
3. The command prints the name the device got, the address it came from and its browser, and exits
   with code `0`:

   ```
   Paired Firefox on Linux from 127.0.0.1, browser: Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0
   ```

### S-PAIRCMD-2. Pairing a phone by a QR code

Covers: `PAIRCMD-1`–`PAIRCMD-3`, `PAIRCMD-6`.

1. The service runs with `--host laptop.example.ts.net` (`S-NET-2` in [network.md](network.md)).
   The owner runs `crawdad pair --name phone`. Besides the code and the link for this machine it
   prints a QR code of the network link, and the link as text:

   ```
   Pairing code: 123456 (valid for 5 minutes)
   On this machine, open: http://127.0.0.1:8460/#pair=123456
   <QR code>
   On another device, scan the code or open: https://laptop.example.ts.net:8460/#pair=123456
   Waiting for the code to be used; Ctrl+C stops waiting and the code stays valid.
   ```

2. The owner scans the QR code with the phone and pairs it. The command prints
   `Paired phone from 100.101.102.104, browser: …` and exits with code `0`.

### S-PAIRCMD-3. A code that does not pair

Covers: `PAIRCMD-3`.

Each run starts with the owner running `crawdad pair` and waiting. The command says how the code
ended on its error output and exits with code `1`:

| What happens | The command says |
|---|---|
| Nobody enters the code for 5 minutes | `The code expired; run pair again for a new one.` |
| Five wrong attempts, from `100.64.0.7` and `100.64.0.9` | `The code burned after wrong attempts from: 100.64.0.7, 100.64.0.9` |
| The owner runs `crawdad pair` again in another terminal | `The code was replaced by a newer pair command.` |
| The code is right, but the service cannot save the device | `Pairing failed: the service could not save the device; see the service log` |
| The service stops | `Crawdad stopped before the code was used.` |

In the third row the second command goes on with its own, new code.

### S-PAIRCMD-4. Stopping the wait

Covers: `PAIRCMD-4`, `PAIRCMD-5`.

1. The owner runs `crawdad pair`, gets `123456` and presses Ctrl+C. The command exits with code `130`.
2. The code is still valid: entered within the 5 minutes, it pairs the device.
3. In another run the owner presses Ctrl+C as well, and someone in the VPN then burns the code from
   `100.64.0.7`.
4. The next `crawdad pair` says so before the new code, once:

   ```
   The previous code burned after wrong attempts from: 100.64.0.7
   Pairing code: 654321 (valid for 5 minutes)
   ```

   The `crawdad pair` after it does not repeat it.

### S-PAIRCMD-5. Device names

Covers: `PAIRCMD-6`, `PAIRCMD-7`.

1. The owner pairs three tablets, each with `crawdad pair --name tablet`. They are named `tablet`,
   `tablet-2` and `tablet-3`.
2. The owner runs `crawdad pair --name ""`. The command says that the name must be 1 to 64
   characters without control characters, and exits with code `1`. No code is issued; a code that
   was valid stays valid.
3. The owner pairs a laptop with Chrome on Windows without `--name`. It is named
   `Chrome on Windows`.

### S-DEV-1. Listing devices

Covers: `DEV-1`, `DEV-2`, `DEV-4`.

1. The owner has paired the browser on the service machine as `Firefox on Linux` and a phone as
   `phone`. The phone has never opened the conversation yet.
2. The owner runs `crawdad devices`. It prints one line per active device, in the order they were
   paired, and exits with code `0`:

   ```
   NAME              PAIRED                LAST SEEN             ADDRESS    BROWSER                                                                  LISTENER
   Firefox on Linux  2026-11-10T09:15:02Z  2026-11-10T09:20:41Z  127.0.0.1  Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0  loopback
   phone             2026-11-10T09:31:17Z  never                 -          -                                                                        network
   ```

3. The phone opens the conversation from `100.101.102.104`. The next `crawdad devices` shows that
   time, address and browser in the phone's line.
4. Someone tries the WebSocket with a wrong key, and the revoked key of another device. The list does
   not change.
5. No device key and no hash of a key appears in the output.

### S-DEV-2. Revoking a device

Covers: `DEV-3`, `DEV-6`, `DEV-9`.

1. The phone is connected. The owner runs `crawdad revoke phone`. The command prints
   `Revoked phone; its open connections are closed.` and exits with code `0`.
2. The phone shows that it was unpaired (`S-MSG-4` in [conversation.md](conversation.md)).
3. `crawdad devices` no longer lists the phone. `crawdad devices --all` does, with the time it was
   revoked in the column `REVOKED`; active devices have `-` there.
4. The owner pairs the phone again with `crawdad pair --name phone`. The new device is named `phone`,
   without a suffix; `crawdad devices --all` shows both, the old one revoked.

### S-DEV-3. A name that cannot be revoked

Covers: `DEV-7`.

The owner has two active devices, `Firefox on Linux` and `tablet`, and the revoked `phone`. Each
command prints to its error output and exits with code `1`:

| Command | The command says |
|---|---|
| `crawdad revoke laptop` | `No active device named laptop. Active devices: Firefox on Linux, tablet` |
| `crawdad revoke Tablet` | `No active device named Tablet. Active devices: Firefox on Linux, tablet` |
| `crawdad revoke phone` | `phone was already revoked at 2026-11-10T10:02:55Z.` |

### S-DEV-4. After a restart

Covers: `DEV-10`.

1. The owner has paired a phone and revoked a laptop, then restarts the service.
2. The phone connects with its key; no code is asked. `crawdad devices --all` shows the same devices
   with the same times.
3. The laptop's key is still refused.

### S-CA-1. Exporting the root certificate

Covers: `CA-1`–`CA-4`.

1. The service has created its own root on a start with `--host 192.168.1.20` and printed
   `Root certificate SHA-256: 5F:3A:…:C1` (`S-TLS-1` in [network.md](network.md)). It may be running
   or not.
2. The owner runs `crawdad tls export-ca > crawdad-root.crt`. The file gets the root certificate as
   PEM; the terminal shows the fingerprint, and the command exits with code `0`:

   ```
   SHA-256 fingerprint: 5F:3A:…:C1
   ```

3. The owner copies `crawdad-root.crt` to a laptop, installs it, and checks that the laptop shows the
   same fingerprint.

### S-CA-2. No root certificate yet

Covers: `CA-5`.

1. The owner has only ever started the service without `--host`, or with certificate files of
   Tailscale.
2. `crawdad tls export-ca` prints to its error output that there is no root certificate yet and that
   a start with `--host` and no certificate files creates it, and exits with code `1`. Nothing is
   created.

## Requirements

### Reaching the service

- **CMD-1.** The commands MUST be subcommands of the program that runs the service:
  `crawdad <command> [<arguments>]`. When the first argument is `pair`, `devices`, `revoke` or
  `tls`, the program MUST run the command; otherwise the arguments are the options of the service
  ([Addresses and TLS](network.md)). A command with arguments it does not take MUST print its usage
  to the error output and exit with code `2`.
- **CMD-2.** A command that needs the service MUST talk to the running service of the same OS user
  through its control socket (`FILE-2`). A command MUST NOT start the service.
- **CMD-3.** When nothing accepts connections on the control socket — the file is missing, or nobody
  listens on it — the command MUST say on its error output that Crawdad is not running and how to
  start it, and MUST exit with code `1`. The exact wording is not part of the contract.
- **CMD-4.** When the service closes the connection without an answer, the command MUST say so,
  point at the service log and exit with code `1`.
- **CMD-5.** The control socket MUST accept commands only from the OS user who runs the service: the
  socket MUST be `0600` in a `0700` directory (`FILE-4`), and the service MUST check the user on the
  other end of every connection. A connection of another user MUST be closed without an answer, and
  the service MUST write a warning with that user to its log; repeated refusals of one user MAY be
  written as one aggregated warning.
- **CMD-6.** The control socket MUST accept commands from the moment the service prints its addresses
  ([`NET-4`](network.md)) until it stops. When the service cannot open the control socket, it MUST
  refuse to start and listen on no address.

### One service per OS user

- **CMD-7.** At most one service MUST run for one OS user. A start while a service of the same user
  is running MUST be refused with a message that Crawdad is already running and exit code `1`,
  before the service listens on any address and before it opens the device registry or the root
  certificate. The running service MUST NOT be affected.
- **CMD-8.** However the service stopped, including a crash, the next start MUST succeed; a control
  socket left on disk MUST be replaced. A service that stops normally MUST delete its control socket.

### Files of the service

- **FILE-1.** The service MUST keep its data in `$XDG_DATA_HOME/crawdad/`, or in
  `$HOME/.local/share/crawdad/` when `XDG_DATA_HOME` is not set, empty or not an absolute path. When
  neither gives an absolute path, the service MUST refuse to start and say why.
- **FILE-2.** The control socket MUST be `$XDG_RUNTIME_DIR/crawdad/control.sock`. When
  `XDG_RUNTIME_DIR` is not set, empty or not an absolute path, the socket MUST be in the `run/`
  directory inside the data directory, and the service MUST write a warning to its log. The
  commands MUST find the socket the same way, without the warning.
- **FILE-3.** The data directory holds the device registry and, once created
  ([`TLS-7`](network.md)), the service's own root certificate and its private key in `tls/`.
  Deleting `tls/` deletes the root ([`TLS-10`](network.md)). The format of the files and the names
  of the files are not part of the contract.
- **FILE-4.** Every directory of the service — the data directory, the directory of the socket and
  the directories inside them — MUST be `0700`, and every file and the socket MUST be `0600`, from
  the moment they exist. Missing parent directories MUST be created `0700`; an existing directory of
  the service with wider permissions MUST be narrowed to `0700` at start.
- **FILE-5.** The service MUST replace a file so that a reader, or the service after a crash, finds
  either the whole old content or the whole new one.

### `crawdad pair`

- **PAIRCMD-1.** `crawdad pair [--name <name>]` MUST get a new code from the service, which ends the
  code valid before ([`PAIR-2`](pairing.md)), and print on its output:
  - the code and how long it is valid;
  - the link for this machine, `http://127.0.0.1:<port>/#pair=<code>`;
  - with a network address ([`NET-2`](network.md)), the link
    `https://<name>:<port>/#pair=<code>` for the network site ([`NET-9`](network.md)), both as a
    QR code and as text; without one, a hint that other devices need `--host`.

  The links MUST be built from the addresses of the running service, not from the options of the
  command. The exact wording is not part of the contract.
- **PAIRCMD-2.** After printing the code, the command MUST wait until the code is used, expires, is
  burned or replaced, or the service stops, and then exit.
- **PAIRCMD-3.** How the code ended MUST be printed and decide the exit code:

  | The code | Output | Exit code |
  |---|---|---|
  | paired a device | the device's name, the address it came from and its `User-Agent` | `0` |
  | expired ([`PAIR-3`](pairing.md)) | that it expired | `1` |
  | burned ([`PAIR-4`](pairing.md)) | that it burned, and the addresses of the wrong attempts | `1` |
  | was replaced by a newer `crawdad pair` | that it was replaced | `1` |
  | was right, but the device was not saved ([`PAIR-9`](pairing.md)) | that pairing failed; a pointer to the service log | `1` |
  | — the service stopped | that the service stopped before the code was used | `1` |

  Every outcome but the first goes to the error output.
- **PAIRCMD-4.** Ctrl+C MUST stop the wait and exit with code `130`. It MUST NOT end the code: the
  code stays valid for the rest of its 5 minutes. The same holds when the command goes away in any
  other way.
- **PAIRCMD-5.** When a code burns while no `crawdad pair` waits for it, the next `crawdad pair`
  MUST say so, with the addresses of the wrong attempts, before it prints its own code. Each burned
  code MUST be reported once: to the waiting command or, if none waited, to the next one.
- **PAIRCMD-6.** With `--name <name>`, the device paired with this code MUST get that name, with
  white space at both ends removed. Without `--name` the service MUST name the device after its
  browser and operating system, such as `Firefox on Linux`; that name is not part of the contract.
- **PAIRCMD-7.** A name MUST be 1 to 64 characters long and MUST NOT contain control characters,
  format characters such as direction marks, or line and paragraph separators. Another name MUST
  be refused with a message that says what a name may be and exit code `1`, before any code is
  issued; the code valid before stays valid. When an active device already has the name, the
  device MUST get the name with the first free suffix `-2`, `-3`, … ([`KEY-8`](pairing.md)).
- **PAIRCMD-8.** Values that come from a browser — its device name, address and `User-Agent` — and
  the addresses of wrong attempts MUST be printed with control characters, direction controls and
  line separators escaped, and cut to at most 200 characters.

### `crawdad devices` and `crawdad revoke`

- **DEV-1.** `crawdad devices` MUST print the active devices, in the order they were paired, and exit
  with code `0`. For each device it MUST print:
  - its name;
  - when it was paired;
  - when it last connected (`DEV-2`), from which address and with which `User-Agent`, or that it
    never connected;
  - the address it was paired on: `loopback` or `network` ([`KEY-4`](pairing.md)).

  With no active devices it MUST say so and exit with code `0`. The output MUST NOT contain a device
  key or the hash of one. The layout of the output is not part of the contract.
- **DEV-2.** The last connection of a device is the last WebSocket handshake its key passed
  ([`WS-5`](conversation.md)). A refused attempt and `GET /device` MUST NOT change it.
- **DEV-3.** `crawdad devices --all` MUST print the revoked devices as well, each with when it was
  revoked.
- **DEV-4.** Times MUST be printed in UTC, in ISO 8601 with the letter `Z`. Their precision is not
  part of the contract.
- **DEV-5.** Values that come from a browser — its device name, address and `User-Agent` — MUST be
  printed as `PAIRCMD-8` says.
- **DEV-6.** `crawdad revoke <name>` MUST revoke the active device with exactly that name; names are
  compared with case. From then on its key MUST be refused on every address
  ([`KEY-6`](pairing.md), [`WS-5`](conversation.md)), and its open connections MUST be closed
  ([`MSG-14`](conversation.md)). The command MUST say that the device was revoked and exit with code
  `0`.
- **DEV-7.** When no active device has the name, `crawdad revoke` MUST revoke nothing and exit with
  code `1`. If a revoked device has the name, it MUST say that the device was already revoked, and
  when — the latest time, if several were. Otherwise it MUST say that there is no active device with
  that name and list the names of the active devices.
- **DEV-8.** When the service cannot save the revocation, the command MUST say so, point at the
  service log and exit with code `1`. The device MUST stay active, and its connections open.
- **DEV-9.** A revoked device MUST stay in the list of `crawdad devices --all`, and its key MUST never
  work again. Its name is free: a device paired later may get it without a suffix
  (`PAIRCMD-7`). Pairing the same browser again makes a new device with a new key.
- **DEV-10.** Devices, their last connections and revocations MUST be kept across restarts of the
  service.

### `crawdad tls export-ca`

- **CA-1.** `crawdad tls export-ca` MUST print the service's own root certificate
  ([`TLS-7`](network.md)) to its output as one PEM certificate, and nothing else there, so that the
  output can be redirected to a file. It MUST exit with code `0`.
- **CA-2.** The command MUST print the SHA-256 fingerprint of the root certificate to its error
  output, in the same form as the service prints it at start ([`NET-4`](network.md)): the hash of
  the DER encoding, as pairs of upper-case hex digits separated by colons.
- **CA-3.** The command MUST NOT print, copy or read the private key of the root.
- **CA-4.** The command MUST read the root from the data directory (`FILE-3`), and MUST work whether
  the service is running or not. It MUST print the root as it is, even when it has expired; checking
  the root is the service's job ([`TLS-10`](network.md)).
- **CA-5.** When there is no root, the command MUST say so and how to get one — a start with `--host`
  and no certificate files — and exit with code `1`. It MUST NOT create the root. When the root
  cannot be read or is not a certificate, the command MUST name the file and the problem and exit
  with code `1`.

## Messages

This file adds no messages. The requests and answers between a command and the service are internal:
both ends ship in the same program.

## Implementation status

The code on `main` has no commands, no control socket and no files.

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `CMD-1` | every argument is an option of the service; `pair` is an unknown option | #10 |
| `CMD-2`–`CMD-6` | no control socket | #10 |
| `CMD-7`, `CMD-8` | a second start fails only if the port is taken; with `--port 0` two services run | #10 |
| `FILE-1`–`FILE-5` | the service keeps no files | #10 |
| `PAIRCMD-1`–`PAIRCMD-8` | no `crawdad pair` and no pairing codes | #10 |
| `DEV-1`–`DEV-10` | no devices: no `crawdad devices`, no `crawdad revoke` | #10 |
| `CA-1`–`CA-5` | no own root certificate and no `crawdad tls export-ca` | #10 |
