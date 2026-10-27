# ADR-0003: Agent adapters use Claude Code's programmatic mode with API keys

- **Status:** Accepted; decision 3 superseded by [ADR-0005](0005-license-policy.md)
- **Date:** 2026-10-26
- **Supersedes:** rules 2, 4 and 8 of [ADR-0001](0001-legal-ground-rules.md)

## Context

[ADR-0001](0001-legal-ground-rules.md) assumed that running a user-installed Claude Code from
Crawdad is separate from the Claude Agent SDK. Claude Code's documentation says otherwise: its
programmatic mode, `claude -p` with JSON streaming, is described as *"the Agent SDK via the CLI"*.
For third-party products built on the Agent SDK, Anthropic requires API key authentication and does
not allow Claude subscription sign-in.

ADR-0001 also allowed only permissive dependency licenses, while the Java platform the service runs
on is licensed under GPLv2 with the Classpath Exception.

## Decision

1. **API keys.** Adapters in this repository authenticate Claude Code with the user's own Anthropic
   API key, or with the credentials of a cloud provider that Claude Code supports. They run Claude
   Code in bare mode (`--bare`), which does not use subscription sign-in. This replaces rule 2 of
   ADR-0001.

2. **Programmatic mode, no SDK packages.** Adapters run the user-installed, unmodified Claude Code
   in its documented programmatic mode (`claude -p` with `stream-json` input and output). The Agent
   SDK packages are not used as dependencies, because they ship the Claude Code binary inside them,
   which would conflict with rule 1 of ADR-0001. This replaces rule 4 of ADR-0001.

3. **Dependency licenses.** Libraries must use permissive licenses: MIT, BSD-2-Clause,
   BSD-3-Clause, Apache-2.0, ISC or similar, with no copyleft. The Java platform (OpenJDK) is
   allowed under GPLv2 with the Classpath Exception, because the exception explicitly permits code
   that uses the platform to be released under any license. The automated license check in CI lists
   this exception explicitly, and any distributed build that includes platform components ships
   their license texts. This replaces rule 8 of ADR-0001.

All other rules of ADR-0001 stay in force.

## Consequences

- Users of the adapters in this repository need an Anthropic API key, or cloud provider
  credentials, and pay for usage per token.
- Bare mode does not read hooks, MCP servers, settings or `CLAUDE.md` from the repository. The
  adapter passes everything it needs explicitly, which also means repository configuration never
  runs without the owner knowing.

## Sources

Checked on 2026-10-26:

- [Run Claude Code programmatically](https://code.claude.com/docs/en/headless.md): *"This page
  covers using the Agent SDK via the CLI (`claude -p`)"*; bare mode *"doesn't use your subscription
  login"*.
- [Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview): *"Unless previously
  approved, Anthropic does not allow third party developers to offer claude.ai login or rate limits
  for their products, including agents built on the Claude Agent SDK."*
- [Claude Code: Legal and compliance](https://code.claude.com/docs/en/legal-and-compliance.md):
  *"Developers building products or services that interact with Claude's capabilities, including
  those using the Agent SDK, should use API key authentication"*.
- [OpenJDK license](https://openjdk.org/legal/gplv2+ce.html): GPLv2 with the Classpath Exception.
