# Crawdad

**An alternative client for Claude Code that keeps you close to your code.**

> **Status:** early design. There is nothing to install yet.

## The problem

Working with an AI coding agent, it is easy to lose touch with your own code. You describe what you
want, the agent writes it, and by the time you open the pull request it reads like someone else's
work:

- **While the agent works**, you cannot see what your words turn into. You say "this needs to be
  restricted", and you don't know whether that became an `if`, an exception or a validation rule, or
  where it went.
- **When you review**, the pull request is new code to you. You understand it only slightly better
  than any other reviewer, because you didn't see it being written and can't tell which part
  implements which of your requirements.
- **Later on**, you come back to the code and can't find its entry points and exits.

## The idea

Crawdad makes the path from what you want to the code that does it visible, in both directions:

**intent → requirements → code**

- An **intent** is an abstract goal: *users must not see other users' orders*.
- A **requirement** is a concrete, verifiable condition: *requesting an order with someone else's
  `userId` returns 403*. One intent unfolds into one or more requirements.
- **Traceability** means you can follow any intent down to the code that implements it, and walk
  back from any piece of code to the requirement and intent it exists for. Crayfish walk backwards;
  so can you.

How exactly to achieve this is still being designed.

## Scope

Crawdad is a client, not an agent:

- **It wraps Claude Code** and aims to support everything the official client does.
- **It edits the standard agent settings** through its UI. Settings it doesn't cover remain
  available through the agent's own configuration files.
- **It does not include its own AI agent** or its own code editor. Working with code relies on an
  existing IDE or on open-source editor components.
- **Other model providers** may be supported, but only as long as they don't get in the way of the
  main idea.

## Platforms

Linux is the primary platform. Windows support is planned. macOS is not tested.

## How the project is built

- **Specification first.** Requirements are written down before code. The specification lives in
  `specs/` and changes in the same pull request as the code that implements it.
- **Architecture decisions** are recorded as ADRs in `docs/adr/`.
- **Every change goes through a pull request.** No direct commits to `main`.
- **Tests are part of done.** A requirement is not implemented until a test checks it. Unit test
  coverage is kept at around 80% or higher.
- **Only permissively licensed dependencies** (MIT, BSD, Apache-2.0 and similar).

## Contributing

Crawdad does not accept external pull requests, but forks are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE) © Artem Bondarenko

Crawdad is an independent project. It is not affiliated with, endorsed by or sponsored by
Anthropic. Claude and Claude Code are trademarks of Anthropic, PBC.
