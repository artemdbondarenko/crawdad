# Development

## Requirements

- **Java 25** (a JDK, not only a runtime). On Ubuntu: `sudo apt install openjdk-25-jdk-headless`.
- Nothing else. Maven, Node.js and pnpm are downloaded by the build, in the versions pinned in the
  repository.

If several Java versions are installed, point `JAVA_HOME` at Java 25 before building; the build
stops with a clear message on any older version.

## Build everything

```sh
./mvnw verify
```

This one command builds and checks the whole repository:

- compiles the Java service and runs its tests;
- validates the protocol schema;
- installs the pinned Node.js and pnpm into `clients/node/`, then lints, type-checks, tests and
  builds the clients;
- fails if line coverage in any module drops below 80%;
- fails if a runtime or production dependency uses a license outside the allow-list
  ([ADR-0005](adr/0005-license-policy.md)).

CI runs the same command on every pull request.

## Run the service

```sh
./mvnw package -DskipTests
"$JAVA_HOME/bin/java" -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar
```

`"$JAVA_HOME/bin/java"` is the Java 25 the build used: with several Java versions installed, a plain
`java` from `PATH` may be older and fails with `UnsupportedClassVersionError`. If Java 25 is the only
one and `JAVA_HOME` is not set, plain `java` works.

The service prints the address to open, `http://127.0.0.1:8460/` by default; open exactly that
address in a browser. The jar finds its dependencies in `service/target/lib/`, next to it. Stop the
service with Ctrl+C.

Options:

- `--port <number>`: the port, `8460` by default; `0` picks a free port.
- `--host <private address>`: also listen on this network address, over TLS only. It takes an IPv4
  address or a host name that resolves to a private address; loopback, `0.0.0.0` and public addresses are refused.
  IPv6 is not supported yet. `127.0.0.1` is always served over plain HTTP.
- `--tls-cert <file> --tls-key <file>`: the PEM certificate and key for the `--host` address, such as
  those from `tailscale cert`. Both or neither; they need `--host`. Without them the service issues
  its own certificate.

A wrong option, an unusable certificate or a second instance stops the start with a one-line
message and exit code 1. Remote access step by step: [remote-access.md](remote-access.md).

### Pair a device

Every connection needs a paired device, also on `127.0.0.1`. The owner's commands run from the same
jar, with the service running. There is no `crawdad` wrapper yet:

```sh
"$JAVA_HOME/bin/java" -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar pair [--name <name>]
"$JAVA_HOME/bin/java" -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar devices [--all]
"$JAVA_HOME/bin/java" -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar revoke <name>
"$JAVA_HOME/bin/java" -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar tls export-ca
```

- `pair` prints a six-digit code, valid for 5 minutes, and a link `http://127.0.0.1:<port>/#pair=<code>`.
  With `--host` it also prints a QR code and the network link. Open the link and press
  **Pair this device**. `pair` then waits and prints the result: the device name, address and
  browser, or that the code expired, burned after wrong attempts (with their addresses) or was
  replaced by a newer `pair`. Exit code 0 only when a device paired, otherwise 1; Ctrl+C stops
  waiting, and the code stays valid. A new `pair` replaces the previous code. Without `--name`, the
  name comes from the browser and the OS.
- `devices` lists the active devices: name, pairing time, last sign-in, address, browser and
  listener (`loopback` or `network`). `--all` adds revoked devices with the revocation time.
- `revoke <name>` revokes an active device and closes its open connections at once. An unknown
  name prints the active devices; exit code 1.
- `tls export-ca` prints the service's own root certificate, for installing on devices; see
  [remote-access.md](remote-access.md#with-the-services-own-root).

When the service is not running, the commands say so, with the command to start it, and exit with 1.
Wrong arguments exit with 2.

### Files

- Data: `$XDG_DATA_HOME/crawdad/` (by default `~/.local/share/crawdad/`): `devices.json` with the
  paired devices (only a hash of each key), and `tls/` with the service's own root.
- Control socket: `$XDG_RUNTIME_DIR/crawdad/control.sock`, next to the instance lock `crawdad.lock`.
  Without `XDG_RUNTIME_DIR`, both go to `run/` inside the data directory, and the service logs a
  WARNING.

Directories are created 0700 and files 0600, so other OS users cannot read them or use the socket.

A Unix socket path is limited to 108 bytes. If `XDG_RUNTIME_DIR` (or the data directory, without it)
is long, the service fails at start with `Unix domain path too long`. Use a shorter directory.

**One service per OS user.** A second service of the same user does not start
("Crawdad is already running"). Services of two OS users on one machine are not supported.

## Repository layout

| Path | What it is |
|---|---|
| `service/` | Java service |
| `protocol/` | JSON Schema of the messages between the service and its clients |
| `clients/` | pnpm workspace: `shared/`, `desktop/`, and `tools/` for build tooling |
| `specs/` | specifications |
| `docs/adr/` | architecture decision records |

## Working on the clients

Inside `clients/`, pnpm can be used directly. This needs Node.js and pnpm installed locally; the
versions the build uses are pinned in `clients/pom.xml` (`node.version`, `pnpm.version`).

```sh
cd clients
pnpm install
pnpm lint            # ESLint and Prettier
pnpm typecheck
pnpm test            # Vitest with coverage
pnpm build
pnpm licenses:check  # licenses of production dependencies
pnpm --filter @crawdad/desktop dev   # desktop client with live reload
```

## Tests and requirements

A test names the requirements of [`specs/`](../specs/) it checks, so that a requirement can be
followed to its tests and back:

- **Java** (service, protocol): a JUnit tag per requirement, `@Tag("PAIR-8")`.
- **TypeScript** (clients): the identifiers in front of the test name,
  `it("SCREEN-2, SCREEN-3: fills the code from a pairing link", …)`.

To find the tests of a requirement, search for its identifier:

```sh
git grep -n -e '@Tag("PAIR-8")' -e '"PAIR-8[,:]' -e ', PAIR-8[,:]'
```

## Changes

Every change goes through a pull request. A pull request holds one change with one meaning, as a
single commit with a one-line message in the imperative mood.
