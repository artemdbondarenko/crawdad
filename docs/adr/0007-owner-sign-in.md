# ADR-0007: The agent runs under the owner's own sign-in to Claude Code

- **Status:** Proposed
- **Date:** 2026-11-24
- **Supersedes:** decision 1 of [ADR-0003](0003-api-key-adapters.md); rule 2 of
  [ADR-0001](0001-legal-ground-rules.md), as ADR-0003 had replaced it

## Context

[ADR-0003](0003-api-key-adapters.md) required adapters to authenticate Claude Code with an API key
and to run it in bare mode (`--bare`), which never uses subscription sign-in. It relied on two
statements in Claude Code's documentation: that `claude -p` is *"the Agent SDK via the CLI"*, and
that developers building products on the Agent SDK *"should use API key authentication"*.

Read again on 2026-11-24, the documentation draws the line elsewhere:

- What is forbidden is a developer **offering** Claude.ai sign-in in their own application, routing
  requests through subscription credentials **on behalf of their users**, and collecting, storing or
  intermediating Claude.ai credentials or session tokens. Sign-in must complete through Anthropic's
  own flow.
- What is not forbidden is an end user **signing in to the unmodified Claude Code binary with their
  own Claude subscription**, including where a platform hosts Claude Code. Each end user
  authenticates with their own API key, their own subscription or their own cloud provider
  credentials.
- `claude setup-token` is documented for *"CI pipelines, scripts, or other environments where
  interactive browser login isn't available"*, and needs a subscription plan.

Crawdad fits the permitted side. It is not a hosted service: one person runs it on their own machine
([ADR-0001](0001-legal-ground-rules.md), rule 6), and the Claude Code it starts is the one that
person installed and signed in to themselves.

Two statements still point the other way, and no page reconciles them with the above:

- The Agent SDK overview says that, unless previously approved, Anthropic does not allow third-party
  developers to offer claude.ai login for their products, including agents built on the Agent SDK.
- The Consumer Terms (section 3, item 7) forbid accessing the services *"through automated or
  non-human means, whether through a bot, script, or otherwise"*, except with an API key or where
  Anthropic explicitly permits it. A message the owner types in Crawdad is human use, but it reaches
  Claude Code through a program.

Bare mode also turned out not to give what ADR-0003 expected of it: Claude Code *"still reads
settings files and their permission rules"* in bare mode, so a repository's settings take effect
either way.

## Decision

1. **Sign-in belongs to Claude Code.** Adapters run Claude Code under whatever sign-in the owner set
   up in Claude Code itself: a Claude subscription, `claude setup-token`, an API key or a cloud
   provider. Crawdad offers no sign-in of its own, never asks for credentials, and never reads,
   stores, copies, logs or passes on credentials or tokens. It does not add credentials to the
   environment of Claude Code and does not remove the ones the owner put there. This replaces
   decision 1 of ADR-0003.

2. **No bare mode.** Adapters run Claude Code in its programmatic mode without `--bare`, because
   bare mode never reads a subscription sign-in. Decision 2 of ADR-0003 stays: programmatic mode
   (`claude -p` with `stream-json`), no Agent SDK packages.

3. **Repository trust is required.** Without bare mode, Claude Code runs the configuration it finds
   in the repository: hooks, MCP servers, `CLAUDE.md`, permission rules and environment variables in
   its settings. The Claude Code adapter therefore reports that it executes repository
   configuration, and the service asks the owner to trust a folder before the first session in it,
   as decision 5 of [ADR-0002](0002-service-and-thin-clients.md) says. The trust check is part of the
   adapter's specification, not a later addition.

4. **One owner, their own sign-in.** The sign-in Claude Code uses is the owner's own. Paired devices
   are the owner's devices; Crawdad never lets another person use the owner's sign-in, and offers no
   way to share it.

All other rules of ADR-0001 and ADR-0003 stay in force.

## Consequences

- An owner with a Claude subscription can use it, and does not need an API key. An owner with an
  API key or a cloud provider uses that instead; Crawdad works the same way with each.
- If `ANTHROPIC_API_KEY` is in the environment of the service, Claude Code in programmatic mode
  always uses it, even when the owner is signed in with a subscription. The documentation of
  Crawdad says so; Crawdad does not change the environment to prevent it.
- The service must specify and build the repository trust check before the agent runs anywhere.
- **Risk.** Whether programmatic use of a subscription through Crawdad counts as permitted under the
  Consumer Terms (section 3, item 7) is not stated by Anthropic. The README says that using a
  subscription through Crawdad is the owner's decision under their own agreement with Anthropic,
  and that an API key is always an option. If Anthropic states that it is not permitted, or asks the
  project to change, this record is revisited and the project changes, as ADR-0001 says.
- How the owner signs in to Claude Code, and how long that sign-in lasts, is between the owner and
  Claude Code. Crawdad only reports to the owner that Claude Code could not sign in.

## Alternatives considered

- **Keep ADR-0003: API key only.** Not chosen: it makes the owner pay per token although the
  documentation does not forbid an end user's own subscription in the unmodified binary, and bare
  mode does not keep repository settings out anyway.
- **Ask the owner for a token from `claude setup-token` and pass it to Claude Code.** Rejected:
  Crawdad would store and pass on a credential, which the documentation forbids.
- **Drive Claude Code's interactive terminal interface through a pseudo-terminal.** Rejected, as in
  ADR-0002: not a documented interface (ADR-0001, rule 3).

## Sources

Checked on 2026-11-24:

- [Claude Code: Legal and compliance](https://code.claude.com/docs/en/legal-and-compliance.md):
  offering Claude.ai login, intermediating credentials, an end user signing in to the unmodified
  binary with their own subscription, each end user authenticating with their own credentials.
- [Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview): third-party developers
  offering claude.ai login.
- [Run Claude Code programmatically](https://code.claude.com/docs/en/headless.md): `claude -p` as
  the Agent SDK via the CLI; bare mode never reads OAuth credentials and still reads settings files
  and their permission rules.
- [Claude Code: Identity and access management](https://code.claude.com/docs/en/iam.md):
  `claude setup-token` for scripts and CI; authentication precedence; in programmatic mode an API
  key in the environment is always used.
- [Anthropic Consumer Terms of Service](https://www.anthropic.com/legal/consumer-terms), section 3,
  item 7.
