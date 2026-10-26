# ADR-0002: Architecture: a service and thin clients

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

Crawdad has to:

- keep the developer close to the code through traceability between chat messages, plan tasks and
  code ([#2](https://github.com/artemdbondarenko/crawdad/issues/2));
- support what Claude Code's own interface does and edit its standard settings;
- let the developer work with the agent on their own machine from other devices, including a phone
  on a mobile network.

Constraints: the legal ground rules of [ADR-0001](0001-legal-ground-rules.md) and
[ADR-0003](0003-api-key-adapters.md); Linux first; Java for the service; permissive dependency
licenses.

## Decision

1. **Service.** A Java service runs on the owner's Linux machine as a systemd user service. It owns
   everything that touches the machine: the agent, MCP servers, files, git and traceability data.
   The target platform is Ubuntu 26.10 or newer with GNOME.

2. **Agent adapters.** The service talks to coding agents through an adapter interface: start or
   resume a session, send a user message, receive events as JSON, answer permission requests, and
   report whether the adapter executes configuration found in the repository. Adapters in this
   repository follow [ADR-0003](0003-api-key-adapters.md).

3. **Clients.** Clients are thin: they show the conversation, code and changes, take text input and
   send it to the service. They do no agent work. Clients are built with web technologies
   (TypeScript) on top of one protocol, but are not necessarily the same client everywhere:
   - a full desktop client shows code and diffs with [Monaco](https://github.com/microsoft/monaco-editor)
     (MIT), the editor component of VS Code, which provides diff views and per-line hovers;
   - a phone client may be a simplified one: the conversation and links that open individual files.

   How each client is packaged (browser or PWA, Tauri, or another wrapper) is decided when that
   client is built.

4. **Protocol.** WebSocket with JSON messages and an explicit message schema, compressed with
   `permessage-deflate`. Heavy data, such as diffs and file contents, is fetched on demand, so a
   simplified client never has to load it.

5. **Security.** The service accepts commands that change code and run programs, so it is treated
   as a remote code execution surface:
   - The service listens only on localhost or on an interface the owner explicitly configures,
     never on all interfaces by default.
   - **One owner.** The service belongs to the OS user that runs it. A client can connect only
     after pairing: the service shows a one-time code, and the paired device receives its own key.
     Each device key can be revoked.
   - Every connection is authenticated with a device key. The service checks the `Origin` and
     `Host` headers on every WebSocket connection and always uses TLS (`wss://`).
   - **Remote access** goes through the owner's own VPN. Crawdad provides no relay and never
     exposes the service to the internet. [Tailscale](https://tailscale.com) is known to work for
     this, including when both the home network and the phone are behind carrier-grade NAT; any
     other VPN will do.
   - **Repository trust.** A repository can contain configuration that Claude Code executes
     automatically: hooks, MCP servers, permission rules and environment variables in its settings.
     Before the first session with a given pair of folder and adapter, if the adapter executes such
     configuration and the folder contains it, the service shows what it found and asks the owner to
     trust it. Otherwise, it does not ask.

6. **Permissions.** Claude Code sends its permission requests to an MCP tool provided by the service
   (`--permission-prompt-tool`). The service forwards each request to a client, and the owner
   allows or denies it there. The same MCP server is a candidate for hosting traceability tools.

7. **Voice input.** Crawdad does not include speech recognition. Clients rely on the system's input
   methods: on Ubuntu, a speech-to-text input method such as Canonical's Myna; on Android, keyboard
   dictation.

## Consequences

- The project uses two languages: Java for the service and TypeScript for the clients.
- A web interface to a local service is the class of software where Origin checks and DNS
  rebinding protection are most often missing, so the rules in decision 5 are mandatory, not
  optional hardening.
- Monaco has to be checked in whatever wrapper each desktop client uses.
- A phone client that keeps a connection open in the background needs an Android foreground
  service and reconnection logic.
- Traceability data lives in the service, outside the repository, as #2 requires.

## Alternatives considered

- **A desktop client in JavaFX.** Rejected: Java desktop UI is a niche with few libraries, the main
  JavaFX UI testing library is under a copyleft license (EUPL) and barely maintained, and Monaco
  would need a web view inside it anyway.
- **A plugin for an existing IDE.** Not chosen: it does not give thin clients on other devices.
- **Claude Code's Remote Control as the transport.** Not possible: only Anthropic's own apps can
  connect to it; there is no public protocol for other clients.
- **Driving Claude Code's interactive terminal interface through a pseudo-terminal.** Rejected: not
  a documented interface ([ADR-0001](0001-legal-ground-rules.md), rule 3).
- **A Crawdad relay server on the internet.** Rejected: it would be a hosted service for other
  people's traffic ([ADR-0001](0001-legal-ground-rules.md), rule 6).
- **gRPC instead of WebSocket.** Not chosen: a stream of events between a service and its clients
  needs no more than WebSocket, which is simpler to debug and available everywhere.

## Sources

Checked on 2026-10-26:

- [Claude Code: Hooks](https://code.claude.com/docs/en/hooks) and
  [Run Claude Code programmatically](https://code.claude.com/docs/en/headless.md): permission
  handling, repository configuration under `-p`.
- [Claude Code: Security](https://code.claude.com/docs/en/security.md).
- [Tailscale: connection types](https://tailscale.com/kb/1257/connection-types).
