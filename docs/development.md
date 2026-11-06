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
address in a browser. Options: `--port <number>` (`0` picks a free port) and `--host <address>`.
The jar finds its dependencies in `service/target/lib/`, next to it. Stop the service with Ctrl+C.

The service has no authentication yet; see the
[limitation of this version](../specs/conversation.md#limitation-of-this-version).

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

## Changes

Every change goes through a pull request. A pull request holds one change with one meaning, as a
single commit with a one-line message in the imperative mood.
