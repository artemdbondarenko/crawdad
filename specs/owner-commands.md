# Owner's commands

The owner manages the service with commands run on the service machine: getting a pairing code,
listing devices, revoking one, exporting the service's own root certificate. This file says how a
command reaches the running service, who may use the commands, that one OS user runs one service,
and where the service keeps its files.

Areas of this file: `CMD` (reaching the service, who may use the commands, one service per OS user),
`FILE` (the files of the service).

The commands themselves — `pair`, `devices`, `revoke`, `tls export-ca` — are not specified yet.

## What this protects against

- **Another user of the same computer.** They cannot reach the service's commands: the control
  socket is closed to other OS users by its permissions, and the service checks the user on the
  other end as well (`CMD-5`). They cannot read the device registry or the root key either: every
  file and directory of the service is open to its OS user only (`FILE-4`).
- **Two services over the same files.** A second service of the same OS user refuses to start
  (`CMD-7`), so two services never write the device registry or create the root certificate at the
  same time, and a new service never takes the control socket from a running one.
- **A file half written by a crash.** The service replaces a file whole or not at all (`FILE-5`).

Not protected against:

- **Processes of the OS user who runs the service.** They act as the owner: they can run the
  commands and read the files.
- **The superuser of the machine.**

Accepted limitation: Crawdad assumes one service per machine. Nothing stops another OS user from
starting a service of their own; it has its own files, socket and devices, and is a separate service
on another port.

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
