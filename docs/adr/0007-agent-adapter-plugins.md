# ADR-0007: Agent adapters are plugin jars

- **Status:** Proposed
- **Date:** 2026-11-24
- **Refines:** decision 2 of [ADR-0002](0002-service-and-thin-clients.md)

## Context

Decision 2 of [ADR-0002](0002-service-and-thin-clients.md) puts every coding agent behind an
adapter interface. Today the interface lives inside the service, and the service always creates the
one adapter it has, the fake agent of the [conversation](../specs/conversation.md) (`FAKE-*`).

More adapters are coming: the adapter of [ADR-0003](0003-api-key-adapters.md), and adapters that are
built and released apart from this repository, on their own schedule and by their own authors. The
service needs one way to take any of them without being rebuilt, and the owner needs to see, at every
start, which agent the service talks to.

What the adapter interface itself has to grow — permission requests, tool events, the end of a
session — is not part of this decision.

## Decision

1. **The adapter interface is a module of its own.** A new Maven module, `adapter-api` (artifact
   `crawdad-adapter-api`), holds what an adapter is built against and nothing else:
   - `AgentAdapter` and `AgentEvent`, moved out of the service;
   - `AgentAdapterProvider`, the entry point the service loads from a jar;
   - `AdapterApi.VERSION`, one whole number, the version of the interface.

   It has no dependencies besides the Java platform, so an adapter never inherits the libraries of
   the service. For now a build outside this repository gets the module with `./mvnw install`;
   publishing it to a repository of artifacts is decided when that is needed.

2. **An adapter is a jar in a folder of the data directory.** The service looks in `adapters/` inside
   its data directory ([`FILE-1`](../specs/owner-commands.md)). Each `*.jar` there is one adapter,
   with its libraries inside it, and gets a class loader of its own that sees only the platform,
   `adapter-api` and the jar. The service does not create, narrow or otherwise change the folder: it
   is the owner's. It refuses to start with a folder or a chosen jar that another OS user owns or can
   write to, because the code runs with the owner's rights
   ([ADR-0006](0006-tls-on-private-network-addresses.md), decision 7).

3. **The jar names itself in its manifest.** `Crawdad-Adapter-Name` gives the name and
   `Crawdad-Adapter-API-Version` the version of the interface it is built for. The service reads
   them without loading any class, and loads code, through `java.util.ServiceLoader`, only from the
   jar of the chosen adapter, after its name and version have passed. Versions match only when they
   are equal; any incompatible change of `adapter-api` raises the version.

4. **The owner chooses with `--agent <name>`, and there is no default.** A start without `--agent`
   is refused like any missing option. A name that leads to no adapter, or to more than one, refuses
   the start and lists what the service found. The service never picks an adapter by itself, the fake
   one included.

5. **The fake agent stays built in.** It is the only built-in adapter, named `fake`. It needs no jar
   and keeps the rules `FAKE-*`.

6. **An adapter tells the service what it is.** Besides its name and version, an adapter reports
   whether it executes configuration found in the repository, which decision 5 of ADR-0002 needs to
   ask the owner to trust a repository. The service writes the chosen adapter, where it comes from
   and that answer to its log at every start.

7. **Who answers for an adapter.** The adapters this repository ships follow
   [ADR-0001](0001-legal-ground-rules.md) and [ADR-0003](0003-api-key-adapters.md). An adapter from
   elsewhere is a program the owner installs and chooses; its author answers for how it talks to its
   agent, and Crawdad runs it as it runs any adapter.

The rules are in [Agent adapters](../specs/agent-adapters.md).

## Consequences

- Every start names its adapter. Scripts and documentation that start the service with no options
  need `--agent fake` or another name.
- Adapters can be released without a release of the service, as long as the version of
  `adapter-api` stays the same; a change of the interface needs a new build of every adapter, and
  the service says so in one line instead of failing later.
- An adapter brings its own libraries, so a library of the service can be upgraded without breaking
  an adapter, at the cost of larger jars.
- The adapter runs in the service's process: a crash or a hang of an adapter is a crash or a hang of
  the service.
- `AgentAdapter` and `AgentEvent` change their package; the service and its tests follow.

## Alternatives considered

- **A build of the service that includes the adapter, made in another repository.** Not chosen:
  every adapter would need its own build of the whole service, kept up to date with every change of
  the service, and the owner would have to know which build has which adapter.
- **An adapter as a separate process that talks to the service over standard input and output.** Not
  chosen: it needs a protocol of its own, in both directions, with versions, framing and errors,
  before the first adapter can be written, and the agents the adapters drive already run as
  processes of their own.
- **`fake` as the default when `--agent` is missing.** Not chosen: the owner could talk to the fake
  agent without noticing, and a start that hides which agent runs is the thing decision 4 avoids.
- **A name read from the adapter's code.** Not chosen: to learn the names the service would have to
  run code of every jar in the folder, also of the ones the owner did not choose, and an interface of
  another version may fail before it answers.
- **A folder given with an option, `--adapters <dir>`.** Not chosen: one more option to give at every
  start for no gain; the data directory is already the owner's place for the files of the service.
- **A folder per adapter, with its libraries next to it.** Not chosen: more rules on the layout of the
  folder; one jar per adapter is simpler to copy, replace and remove.

## Sources

Checked on 2026-11-24:

- [`java.util.ServiceLoader`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html).
- [JAR file specification: the manifest](https://docs.oracle.com/en/java/javase/25/docs/specs/jar/jar.html#jar-manifest).
- [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/latest/).
