# ADR-0006: TLS on private network addresses; loopback stays on plain HTTP

- **Status:** Proposed
- **Date:** 2026-11-08
- **Refines:** decision 5 of [ADR-0002](0002-service-and-thin-clients.md) ("always uses TLS")

## Context

Decision 5 of [ADR-0002](0002-service-and-thin-clients.md) says the service always uses TLS. On
`127.0.0.1` TLS protects nothing: the traffic does not leave the machine, and browsers treat
`http://127.0.0.1` as a secure context. A certificate there would need a locally trusted root or a
browser exception on the owner's own machine.

Devices on the owner's VPN reach the service over a network address. That traffic does leave the
machine and needs TLS.

This work, done in [#10](https://github.com/artemdbondarenko/crawdad/issues/10), is a proof of
concept; the specification of pairing, keys and TLS is written separately.

## Decision

1. **Loopback.** The service always listens on `127.0.0.1` over plain HTTP. HTTPS is not served
   there.

2. **Network address.** `--host` adds one private IPv4 address, served over TLS only, at the same
   time as loopback:
   - Accepted ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10` and
     `169.254.0.0/16`. A host name is resolved, and each of its IPv4 addresses must be in these
     ranges.
   - Loopback, `0.0.0.0` and public addresses are refused at start with an explanation, also for a
     name that resolves to them. IPv6 addresses are not supported yet.
   - Plain HTTP on that address fails the TLS handshake. There is no redirect to `https://`.
   - TLS 1.2 and 1.3 only.
   - At most 32 connections are served at once; further ones wait in the queue of the OS. A
     connection that stays silent for 30 seconds is closed.

3. **Certificate from files.** `--tls-cert <file> --tls-key <file>` name PEM files of a certificate
   and its key, for example those written by `tailscale cert`. Both or neither must be given, and
   they need `--host`.
   - At start the key must belong to the certificate and the certificate must be valid now.
     Otherwise the service does not start and names the file and the problem. It never falls back
     to its own root.
   - The printed address uses the name from the certificate, such as
     `https://<machine>.<tailnet>.ts.net:<port>/`.
   - The service watches both files. A renewed pair is loaded for new connections without a
     restart. A pair that cannot be used, or that names another address, leaves the previous one in
     place with a WARNING.
   - At start and once a day the log warns when the served certificate has less than 14 days left.

4. **Own root certificate.** Without these files the service issues its own root, which the owner
   exports with `crawdad tls export-ca` and installs on devices. The root is never served over the
   network.
   - The root is created once in `<data>/tls/` (`ca.key`, `ca.crt`): an EC P-256 key, valid for
     three years, `pathLen` 0, with critical Name Constraints that permit only the private IPv4
     ranges above, the machine name and a `--host` name.
   - Each start issues a fresh server certificate for `--host`, valid for 397 days. Its key lives
     only in memory.
   - A root that is unreadable, expired, or does not cover the needed name is refused, never
     replaced: devices trust that exact root.

5. **Headers.** Each listener checks `Origin` and `Host` against its own address, on `/ws` and on
   `POST /pair`. Only an exact match passes. On the network address Jetty also answers 400 to a
   `Host` that the served certificate does not name.

6. **Device keys.** Every connection, loopback included, is authenticated with a device key. A key
   is valid only on the listener where the device was paired: a key from `127.0.0.1` works only on
   loopback, a key from the network address only there.

7. **Trust boundary.** Nobody but the owner has physical access to the machine. Processes of the
   owner's OS user are trusted, as with any desktop software. Processes of other OS users, including
   system services and containers that share the host network, are not.

## Consequences

- Locally nothing changes for the owner: `http://127.0.0.1` keeps working.
- On loopback the device key, not TLS, protects the service.
- With a Tailscale certificate, devices open the address by name. Opening it by IP gives a browser
  warning, because the certificate names the machine, not the address.
- With the service's own root, each device needs the root installed once. Changing `--host` to a
  covered address needs no reinstall; a name outside the root's constraints needs a new root.
- Tailscale certificates expire after about 90 days and are renewed by running `tailscale cert`
  again; the service picks up the new files itself.

## Alternatives considered

- **TLS on loopback too.** Not chosen: no protection gained, and the owner would have to trust a
  certificate on their own machine.
- **Only Tailscale certificates.** Not chosen: Crawdad must work with any VPN.
- **Find Tailscale files automatically.** Not chosen: only the explicit paths turn on the owner's
  certificate, so the source of the certificate is always visible in the command.

## Sources

- [MDN: Secure contexts](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts).
- [Tailscale: Enabling HTTPS](https://tailscale.com/kb/1153/enabling-https).
- [RFC 5280, section 4.2.1.10: Name Constraints](https://www.rfc-editor.org/rfc/rfc5280#section-4.2.1.10).
