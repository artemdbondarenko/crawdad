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
