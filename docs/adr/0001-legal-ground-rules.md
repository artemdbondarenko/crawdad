# ADR-0001: Legal ground rules for working with Claude Code

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

Crawdad is a public, MIT-licensed project. It works with Claude Code, which is proprietary software
from Anthropic, governed by Anthropic's terms. The project must not create even potential legal
risk: not for its author, not for its users, and not through its dependencies or its name.

Before choosing an architecture, we need to know which ways of working with Claude Code are allowed
and which are not. This record states those rules. It does not choose a technical integration; that
is a separate decision, which must stay within these rules.

The rules below are based on Anthropic's public documents as of the date above (see
[Sources](#sources)). They are our reading of those documents, not legal advice. Anthropic's
policies change: the rules on subscription sign-in in third-party tools were changed during 2026.

## Decision

1. **Claude Code is installed by the user.** Crawdad works with an unmodified Claude Code that the
   user installed from Anthropic. Crawdad does not bundle, redistribute or modify the Claude Code
   binary. The documentation may state the minimum or tested version of Claude Code, and nothing
   more.

2. **The user signs in to Claude Code through Anthropic's own flow.** The primary way to sign in
   is a Claude subscription. Signing in with an API key inside Claude Code is not blocked, but
   Crawdad does nothing special to support it. Crawdad never collects, stores or passes on Claude
   credentials or session tokens, and never removes or disables a sign-in method built into Claude
   Code.

3. **Only official ways to talk to Claude Code.** Crawdad sends requests to Claude Code and gets
   responses only in ways that Anthropic officially supports and permits. No hacks, unofficial
   protocols, credential interception or circumvention of limits.

4. **No Claude Agent SDK.** The SDK packages ship the proprietary Claude Code binary inside them.
   If Crawdad depended on the SDK, every copy of Crawdad would carry Claude Code with it, which
   contradicts rule 1, where Crawdad never bundles or redistributes Claude Code. On top of that,
   Anthropic does not allow third-party products built on the SDK to offer Claude subscription
   sign-in: they must use API keys, so users would pay per token. That contradicts rule 2, where a
   subscription is the primary way to sign in.

5. **No direct calls to the Claude API.** If Crawdad called the Claude API itself, Crawdad would be
   the third-party application talking to Anthropic. Anthropic does not allow third-party
   applications to use Claude subscription sign-in, so users would need an API key and would pay
   per token. That contradicts rule 2, where a subscription is the primary way to sign in. When
   Crawdad runs Claude Code instead, it is Claude Code, Anthropic's own application, that talks to
   Anthropic, and subscription sign-in is allowed there. Calling the API directly would also mean
   writing our own agent loop, which Claude Code already provides. We may revisit this if Crawdad
   ever needs something Claude Code cannot do.

6. **Ordinary individual use.** Crawdad is an interactive tool for a person working with Claude
   Code. It is not a service that runs Claude Code on other people's behalf, and it does not pay
   for, resell or intermediate anyone's Claude usage.

7. **Names and brands.**
   - The project has its own name, Crawdad, unrelated to "Claude" or "Clawd". Anthropic has asked
     at least one open-source project to rename itself over a name too similar to "Claude".
   - Claude Code is mentioned only in plain text, in the form Anthropic permits: Crawdad *runs
     Claude Code*. The project is not described as an alternative to, or replacement for, Claude
     Code.
   - No Anthropic or Claude Code names or logos in Crawdad's name or logo. No Anthropic logos, no
     Clawd mascot, no Claude Code-style ASCII art or visual style.
   - Nothing suggests that Anthropic built, endorses or is partnered with Crawdad. The README says
     so explicitly.

8. **Permissive dependencies only.** Every dependency must use a permissive license: MIT,
   BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC or similar. No copyleft licenses (GPL, AGPL), which
   would impose their terms on the whole project. A license check runs automatically in CI on every
   pull request; the tools are chosen together with the technology stack.

## Consequences

- Crawdad takes on no obligations to Anthropic beyond those each user already has under their own
  agreement.
- Every user needs their own Claude Code installation and their own Claude account.
- The integration with Claude Code has to be built on an officially supported interface of Claude
  Code itself, without the convenience of the SDK.
- If Anthropic changes its terms, this record is revisited. A complaint from Anthropic is answered
  by changing the project, not by arguing.

## Alternatives considered

- **Claude Agent SDK as a dependency.** Rejected: see rule 4.
- **Own agent loop on the Claude API.** Rejected: see rule 5.
- **Shipping Claude Code with Crawdad.** Rejected: it would require accepting Anthropic's
  Commercial Terms as a vendor and keeping the bundled binary up to date, for no benefit to the
  user.

## Sources

Checked on 2026-10-26:

- [Claude Code: Legal and compliance](https://code.claude.com/docs/en/legal-and-compliance.md):
  running Claude Code in third-party products, authentication and credential use, use of the
  Claude Code name and logo.
- [Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview): license and terms,
  sign-in restrictions, branding guidelines.
- [Anthropic Commercial Terms of Service](https://www.anthropic.com/legal/commercial-terms),
  section D.4.
- [Anthropic Consumer Terms of Service](https://www.anthropic.com/legal/consumer-terms), section 3.
- [Anthropic Trademark Guidelines](https://www.anthropic.com/legal/trademark-guidelines).
- [Claude Code license](https://github.com/anthropics/claude-code/blob/main/LICENSE.md).
