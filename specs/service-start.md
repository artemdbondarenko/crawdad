# Starting the service

How the service refuses to start: what the owner sees, the exit code, and what is left on disk. What
a successful start prints is in [Addresses and TLS](network.md) (`NET-4`).

Area of this file: `START`.

Most reasons to refuse have their own rule elsewhere — an address in
[Addresses and TLS](network.md), a second service or the files of the service in
[Owner's commands](owner-commands.md). This file sets the rules every refusal follows, adds the
reasons no other file covers, and collects all of them in one table ([Refusals](#refusals)).

## What this protects against

- **A service that half starts.** A start that is refused listens on no address (`START-1`), so the
  service never serves with a configuration the owner did not ask for — say, without the network
  address, or with another certificate.
- **Losing the paired devices.** A device registry the service cannot read is left as it is, and the
  service does not start with an empty one in its place (`START-7`), so an older Crawdad, a damaged
  disk or a typo in the file never silently unpairs every device. A registry that is missing is not
  a refusal, but it is written to the log as a warning (`START-7`), so a file deleted or moved by
  mistake does not go unnoticed.
- **Leftovers of a start that failed.** A refused start takes away everything it created and touches
  nothing that was there before (`START-2`), so trying again starts from the same state, and the
  owner never has to clean up after a start that did not happen.
- **A reason lost in a stack trace.** The owner gets one line that says what is wrong and, where it
  helps, what to do (`START-1`); a failure the service did not expect is written to the log with its
  causes, for whoever debugs it (`START-8`).

## Scenarios

### S-START-1. The port is taken

Covers: `START-1`, `START-5`.

1. Another program listens on `127.0.0.1:8460`. The owner starts the service with no options.
2. The service prints one line to its error output and exits with code `1`:

   ```
   Cannot listen on 127.0.0.1:8460: the port is in use; stop the program that uses it or start with --port <number>
   ```

3. Nothing is printed to the standard output, and the service listens on no address.

### S-START-2. A mistyped option

Covers: `START-1`, `START-3`.

1. The owner starts the service with `--prot 8461`.
2. The service prints one line that `--prot` is not an option of the service and exits with code
   `2`. It creates no file and no directory.
3. The same happens with `--port` and no value, `--port eighty`, `--port 70000` and `--port 8461
   --port 8462`, each with its own reason.

### S-START-3. The VPN is down

Covers: `START-1`, `START-4`.

1. The owner starts the service with `--host 100.101.102.103`, the machine's Tailscale address, but
   Tailscale is not running, so no interface of the machine has that address.
2. The service prints one line that `100.101.102.103` is not an address of this machine and exits
   with code `1`. It listens on no address, `127.0.0.1` included.
3. The owner starts Tailscale and starts the service again with the same options; it starts.

### S-START-4. A device registry from a newer Crawdad

Covers: `START-1`, `START-2`, `START-7`.

1. The owner has paired three devices with a newer build of Crawdad, which writes the device registry
   in a format this build does not know. The owner starts this build.
2. The service prints one line that names the registry file and says that it was written by a newer
   version of Crawdad, and exits with code `1`.
3. The file is unchanged. The owner starts the newer build again; the three devices are still paired.

### S-START-5. A control socket path that is too long

Covers: `START-1`, `START-6`.

1. `XDG_RUNTIME_DIR` points to a directory whose path is 120 bytes long.
2. The service prints one line that the path of its control socket is too long, with the path, and
   that a shorter `XDG_RUNTIME_DIR` fixes it, and exits with code `1`. It listens on no address.

## Requirements

### Every refusal

- **START-1.** When the service refuses to start, for any reason of the table [Refusals](#refusals)
  or of `START-8`, it MUST print the reason as one line on its error output, MUST NOT print a stack
  trace, MUST print nothing on its standard output and MUST exit with the code of the table. A
  refused start MUST NOT listen on any address. The line MUST say what is wrong and name the option,
  address or file it is about; its exact wording is not part of the contract. Entries of the service
  log written before the refusal ([The service log](service-log.md)) may come before the line.
- **START-2.** A refused start MUST leave the disk as it found it. It MUST NOT change or delete a file
  or directory that existed before it, except a control socket left by a service that stopped
  ([`CMD-8`](owner-commands.md)), and before it exits it MUST delete every file and directory it
  created: the root certificate and its key ([`TLS-7`](network.md)), the data directory and the
  directory of the socket with their missing parents ([`FILE-4`](owner-commands.md)), the control
  socket and any other file of the service, whatever their names. This holds for every reason of the
  table [Refusals](#refusals) and for `START-8`.

### Reasons no other file covers

- **START-3.** The service MUST refuse to start with exit code `2` when an argument is not one of its
  options (`--host`, `--port`, `--tls-cert`, `--tls-key`), an option has no value, an option is given
  more than once, or the value of `--port` is not a whole number from `0` to `65535`. The line MUST
  name the option.
- **START-4.** When no interface of this machine has an address of `--host`, the service MUST refuse
  to start and say that the address is not an address of this machine.
- **START-5.** When a port it must listen on is in use, the service MUST refuse to start and say which
  address and port are in use. It MUST NOT pick another port by itself.
- **START-6.** When the path of the control socket ([`FILE-2`](owner-commands.md)) is longer than the
  operating system allows for a socket, the service MUST refuse to start, print the path, and say
  that it is too long and that a shorter `XDG_RUNTIME_DIR` fixes it.
- **START-7.** When the device registry exists but cannot be read, is not a device registry, or has a
  format version the service does not know, the service MUST refuse to start, name the file and say
  which of these it is. It MUST leave the file as it is and MUST NOT start with an empty registry. A
  missing registry is not a refusal: the service starts with no devices and MUST write a warning to
  its log that names the file and says that no device is paired
  ([The service log](service-log.md), `LOG-2`).
- **START-8.** When the start fails for a reason the table does not list, the service MUST print one
  line that it could not start, with the failure, and point at the service log; write an ERROR entry
  with the failure and its causes to the log ([`LOG-3`](service-log.md)); and exit with code `1`.

## Messages

### Refusals

The order of the table is not the order of the checks, except that wrong options are found before
anything else and a running service before the device registry and the root certificate.

| Reason | Exit code | Rule |
|---|---|---|
| An unknown argument, an option without a value or given twice, a `--port` that is not `0`–`65535` | `2` | `START-3` |
| `--tls-cert` without `--tls-key`, or the other way round, or both without `--host` | `1` | `TLS-2` |
| `--host` is an IPv6 address, a name that cannot be resolved or has no IPv4 address | `1` | `NET-3` |
| `--host` is loopback, `0.0.0.0` or not in a private range | `1` | `NET-5` |
| A certificate file is missing, unreadable, not PEM, expired or not valid yet; the key does not belong to the certificate; no name in the certificate | `1` | `TLS-3` |
| Neither `XDG_DATA_HOME` nor `HOME` gives an absolute path | `1` | `FILE-1` |
| A service of the same OS user is running | `1` | `CMD-7` |
| The own root certificate has expired, cannot be read or does not cover a name | `1` | `TLS-10` |
| The device registry cannot be read, is not one, or has an unknown version | `1` | `START-7` |
| The control socket cannot be opened, its path too long among the reasons | `1` | `CMD-6`, `START-6` |
| No interface of this machine has the address of `--host` | `1` | `START-4` |
| A port is in use | `1` | `START-5` |
| Any other failure | `1` | `START-8` |

## Implementation status

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `START-1`, `START-6` | A control socket path that is too long ends the start with a stack trace. | #49 |
| `START-1`, `START-7` | A device registry that cannot be read or has an unknown version ends the start with a stack trace. | #49 |
| `START-1`, `FILE-1` | When neither `XDG_DATA_HOME` nor `HOME` gives an absolute path, the start ends with a stack trace. | #49 |
| `START-2` | A refused start leaves what it created: the directory of the socket and the lock file in it, the data directory, and the root certificate when it fails after creating it. | #49 |
| `START-7` | A missing device registry is not written to the log. | #49 |
| `START-3` | Wrong options exit with code `1`, not `2`. An option given twice is taken: the last value wins. | #49 |
| `START-4`, `START-5` | The line gives the operating system's own reason, in the language of the system, such as `Cannot listen on 192.168.77.77:8460: Cannot assign requested address`, instead of saying that the address is not of this machine or that the port is in use. | #49 |
| `START-8` | Any other failure ends the start with a stack trace and writes nothing to the log. | #49 |
