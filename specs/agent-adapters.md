# Agent adapters

The service talks to a coding agent through an adapter. This file says how the owner chooses the
adapter when the service starts, where the service finds adapters that do not come with it, what an
adapter has to tell the service about itself, and when the service refuses to start with it. Why
adapters are plugins is in [ADR-0007](../docs/adr/0007-agent-adapter-plugins.md).

Area of this file: `ADAPTER`.

What an adapter does once it runs — sessions, messages, the answer — is in
[Conversation with the service](conversation.md); the adapter that comes with the service is the
fake agent there (`FAKE-*`). How a refused start looks is in [Starting the service](service-start.md).

## What this protects against

- **Talking to an agent the owner did not choose.** There is no default adapter: the owner names one
  with `--agent` at every start (`ADAPTER-1`), and the name must lead to exactly one adapter
  (`ADAPTER-5`, `ADAPTER-6`). A jar that happens to sit in the folder never takes over, and the owner
  never talks to the fake agent by mistake.
- **Code the owner did not put there.** An adapter runs with the rights of the owner's OS user. The
  service runs code only from the jar of the chosen adapter (`ADAPTER-8`), and only when that jar and
  its folder belong to the owner and nobody else can write to them (`ADAPTER-4`), so another OS user
  cannot plant or swap an adapter ([ADR-0006](../docs/adr/0006-tls-on-private-network-addresses.md),
  decision 7).
- **An adapter built for another version of the service.** The service checks the version of the
  adapter interface before it loads any code of the jar (`ADAPTER-7`), so an adapter built for an
  older or a newer service is refused with a line that says which one to update, instead of failing
  later in the middle of a conversation.
- **Repository configuration running unnoticed.** Every adapter says whether it executes
  configuration found in the repository it works in (`ADAPTER-9`), which the service needs to ask
  the owner to trust a repository ([ADR-0002](../docs/adr/0002-service-and-thin-clients.md),
  decision 5).

Not protected against:

- **An adapter the owner chose.** Once the owner names an adapter and put its jar in the folder, the
  service trusts it as the owner trusts any program they install: its code runs in the service's
  process.

## Scenarios

### S-ADAPTER-1. Starting with the fake agent

Covers: `ADAPTER-1`, `ADAPTER-2`, `ADAPTER-10`.

1. The owner starts the service with `--agent fake`. There is no folder of adapters.
2. The service starts as usual ([`NET-4`](network.md)) and writes an INFO entry to its log that the
   adapter is `fake`, built in, and that it does not execute repository configuration.
3. A text sent from a client is answered by the fake agent ([`S-MSG-1`](conversation.md)).

### S-ADAPTER-2. Forgetting `--agent`

Covers: `ADAPTER-1`, `START-3`.

1. The owner starts the service with `--port 8461` and nothing else.
2. The service prints one line that `--agent` is missing and names the built-in adapters, and exits
   with code `2`. It creates no file and no directory and listens on no address.

### S-ADAPTER-3. An adapter from the folder

Covers: `ADAPTER-3`, `ADAPTER-4`, `ADAPTER-8`, `ADAPTER-10`, `ADAPTER-11`.

1. The owner copies `echo-adapter-1.2.jar`, which names its adapter `echo` and is built for version
   `1` of the adapter interface, into `~/.local/share/crawdad/adapters/`. The folder is `0700` and the
   jar `0644`, both owned by the owner.
2. The owner starts the service with `--agent echo`.
3. The service starts and writes an INFO entry that the adapter is `echo`, from
   `~/.local/share/crawdad/adapters/echo-adapter-1.2.jar`, with whether it executes repository
   configuration.

### S-ADAPTER-4. A name that is not there

Covers: `ADAPTER-5`, `START-1`.

1. The folder of adapters holds `echo-adapter-1.2.jar` (adapter `echo`). The owner starts the
   service with `--agent ecko`.
2. The service prints one line and exits with code `1`:

   ```
   No agent adapter is named ecko; the adapters are: echo (~/.local/share/crawdad/adapters/echo-adapter-1.2.jar), fake (built in)
   ```

3. No code of `echo-adapter-1.2.jar` has run, and the service listens on no address.

### S-ADAPTER-5. Two jars with one name

Covers: `ADAPTER-6`.

1. The owner has copied a new build, `echo-adapter-1.3.jar`, into the folder and has not removed
   `echo-adapter-1.2.jar`. Both name their adapter `echo`. The owner starts the service with
   `--agent echo`.
2. The service prints one line that two adapters are named `echo`, with both files, and exits with
   code `1`.
3. The owner removes the old jar and starts again; the service starts.

### S-ADAPTER-6. An adapter built for another version

Covers: `ADAPTER-7`.

1. The folder holds `echo-adapter-0.9.jar`, built for version `0` of the adapter interface. The
   service implements version `1`. The owner starts the service with `--agent echo`.
2. The service prints one line that the adapter `echo` in that file is built for version `0` of the
   adapter interface, that the service needs version `1`, and that a build of the adapter for
   version `1` fixes it; it exits with code `1`. No code of the jar has run.

### S-ADAPTER-7. A jar someone else can change

Covers: `ADAPTER-4`.

1. The jar of `echo` is `0666`. The owner starts the service with `--agent echo`.
2. The service prints one line that other OS users can write to that file, so it does not run it,
   and exits with code `1`. It does not change the permissions of the file.

## Requirements

### Choosing the adapter

- **ADAPTER-1.** The service MUST take the adapter from the option `--agent <name>`. The option has
  no default: a start without it MUST be refused with exit code `2`, as a missing option
  ([`START-3`](service-start.md)), and the line MUST name the built-in adapters.
- **ADAPTER-2.** The adapters built into the service are listed in the table
  [Built-in adapters](#built-in-adapters). They need no file.
- **ADAPTER-3.** The service MUST look for further adapters in the folder `adapters/` inside its
  data directory ([`FILE-1`](owner-commands.md)), by default `~/.local/share/crawdad/adapters/`.
  Every regular file directly in that folder whose name ends with `.jar` is one adapter; other files
  and directories inside it MUST be ignored. A missing folder means no adapters besides the built-in
  ones and is not a refusal. The service MUST NOT create the folder and MUST NOT change it or the
  files in it: they belong to the owner, and [`FILE-4`](owner-commands.md) does not apply to them.
- **ADAPTER-4.** When the folder exists, the service MUST refuse to start, with the path and the
  reason, if the folder, or the jar of the chosen adapter, is not owned by the OS user who runs the
  service or can be written by its group or by others. The other jars in the folder are not checked:
  their code never runs (`ADAPTER-8`).

### Telling adapters apart

- **ADAPTER-5.** When no adapter — built-in or in the folder — has the name of `--agent`, the
  service MUST refuse to start, name the value of `--agent`, and list the name and the file (or
  "built in") of every adapter it found.
- **ADAPTER-6.** When more than one adapter has the name of `--agent` — two jars, or a jar and a
  built-in adapter — the service MUST refuse to start, name the adapter and list where each of them
  comes from. It MUST NOT pick one. Adapters that share a name other than the one chosen are not a
  refusal.
- **ADAPTER-7.** When the chosen adapter is built for another version of the adapter interface than
  the one the service implements, the service MUST refuse to start and name the file, both versions,
  and which of the two — the adapter or the service — is older. The versions match only when they
  are equal.
- **ADAPTER-8.** The service MUST read the name and the version of an adapter in the folder from the
  manifest of its jar ([Adapter jar](#adapter-jar)), without loading any class of it. It MUST load
  and run code only from the jar of the chosen adapter, and only after `ADAPTER-4`–`ADAPTER-7` have
  passed. A jar whose manifest cannot be read or lacks the name or the version, or gives a name that
  is not valid, is not an adapter: the service MUST write a warning that names the file and says
  why, and go on. When the chosen jar does not hold exactly one provider of the adapter interface,
  or the provider fails to create the adapter, the service MUST refuse to start, name the file and
  say what is wrong; when the provider fails, this is a failure of `START-8`, written to the log with
  its causes.

### What an adapter tells the service

- **ADAPTER-9.** An adapter MUST report whether it executes configuration found in the repository it
  works in — hooks, MCP servers, permission rules, environment variables in its settings
  ([ADR-0002](../docs/adr/0002-service-and-thin-clients.md), decision 5). The service MUST ask this
  once, when it creates the adapter at start.
- **ADAPTER-10.** At start the service MUST write an INFO entry with the name of the adapter, where
  it comes from — "built in" or the path of its jar — and what it reported for `ADAPTER-9`.
- **ADAPTER-11.** The code of an adapter in the folder MUST see only the Java platform, the adapter
  interface ([Adapter interface](#adapter-interface)) and the classes of its own jar. Each jar MUST
  get a class loader of its own. Libraries the adapter needs MUST be inside its jar; the libraries of
  the service are not visible to it.

## Messages

### Built-in adapters

| Name | What it is | Executes repository configuration | Rules |
|---|---|---|---|
| `fake` | the fake agent: answers `You said: <text>` | no | `FAKE-1`–`FAKE-4` ([Conversation](conversation.md)) |

### Adapter jar

An adapter in the folder is one jar with these attributes in the main section of
`META-INF/MANIFEST.MF`:

| Attribute | Value | Example |
|---|---|---|
| `Crawdad-Adapter-Name` | the name the owner gives to `--agent`: 1 to 32 characters, lower-case ASCII letters, digits and `-`, starting with a letter | `echo` |
| `Crawdad-Adapter-API-Version` | the version of the adapter interface the adapter is built for, a whole number written in decimal without leading zeros | `1` |

The jar MUST also name exactly one provider of
`io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider` in
`META-INF/services/io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider`, as
[`java.util.ServiceLoader`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html)
reads it. The provider class MUST be public and have a public constructor without arguments.

### Adapter interface

The adapter interface is the Maven module `adapter-api` (artifact
`io.github.artemdbondarenko.crawdad:crawdad-adapter-api`), package
`io.github.artemdbondarenko.crawdad.adapter`. It holds:

| Type | What it is |
|---|---|
| `AdapterApi.VERSION` | the version of the interface this module describes, an `int`; this file describes version `1` |
| `AgentAdapterProvider` | what the service loads from the jar; `AgentAdapter create()` creates the adapter |
| `AgentAdapter` | starts a session and sends a text to it; `boolean executesRepositoryConfiguration()` answers `ADAPTER-9` |
| `AgentEvent` | the parts of an answer: `Start`, `Delta`, `End` ([`MSG-4`](conversation.md)) |

Any change of these types that an adapter built for the previous version cannot run with raises
`AdapterApi.VERSION` by one. How the module reaches a build outside this repository is not part of
the contract; `./mvnw install` puts it into the local Maven repository.

## Implementation status

| Requirement | What the code does now | Fixed in |
|---|---|---|
