# Specifications

This directory describes what Crawdad does: its observable behaviour, from the point of view of the
person using it and of the clients talking to the service.

- **What goes here:** behaviour and contracts — user scenarios, the messages between the service
  and its clients, the rules the service enforces.
- **What does not:** how things are built and why a design was chosen. Those are architecture
  decisions, recorded in [`docs/adr/`](../docs/adr/).

## Specification first

Requirements are agreed before code is written, and the specification reaches `main` before the
code that implements it. So on `main` the specification may run ahead of the code. Every place where
the code does not match it yet is listed in the specification itself, in the
[Implementation status](#implementation-status) section of the file it belongs to. When the two
disagree, the specification wins and the code is fixed.

## Structure of a specification file

Each file covers one part of the behaviour and has these sections, in this order:

1. **Scenarios** — end-to-end stories that are the reference for the observable behaviour:
   preconditions, steps, the expected result, an example exchange and, where it helps, a sequence
   diagram. A scenario lists the requirements it covers. Scenarios describe what is visible from
   outside — what the person does and sees, what goes over the wire — never class names or
   internal steps.
2. **Requirements** — normative rules, one rule each.
3. **Messages** — formats, with a reference to a machine-readable schema where one exists.
4. **Implementation status** — where the code on `main` does not match the file yet.

Each file also says briefly what its rules protect against.

### Requirement levels

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** and **MAY** are used as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and
[RFC 8174](https://www.rfc-editor.org/rfc/rfc8174): they carry this meaning only when written in
capitals.

### Identifiers

- **Requirements** are named by area and number: `PAIR-1`, `KEY-1`, `TLS-1`. Each area belongs to
  one file.
- **Scenarios** use the same areas with `S-`: `S-PAIR-1`.
- A scenario refers to the requirements it covers. One requirement may be covered by several
  scenarios or by none.
- **Identifiers are never reused.** A removed requirement or scenario keeps its number, marked as
  removed, so that old references never point at a different rule.
- **Tests refer to the identifiers they check**, so that a requirement can be followed to its
  tests and back.

### Implementation status

A table describing `main`:

| Requirement | What the code does now | Fixed in |
|---|---|---|
| `PAIR-1` | what actually happens instead | the issue or pull request that fixes it |

An empty table means the code matches the file. A change that fixes a mismatch removes its row in
the same pull request.

## Specifications

- [Conversation with the service](conversation.md) — sending a text and receiving the streamed
  answer; the messages between the service and its clients; the WebSocket handshake.
- [Addresses and TLS](network.md) — where the service listens; the network address, served only
  over TLS; certificates from files and the service's own root certificate.
- [Pairing and the device key](pairing.md) — pairing a browser with a one-time code; the device
  key in a cookie; the device check; the pairing screen of the client.
- [Owner's commands](owner-commands.md) — how the owner's commands reach the service and who may use
  them; one service per OS user; where the service keeps its files; getting a pairing code with
  `crawdad pair`; listing and revoking devices; exporting the service's own root certificate.
- [The service log](service-log.md) — which events the service writes, at which level and with what
  in them; escaping values from outside; no secrets; aggregating repeated warnings.
