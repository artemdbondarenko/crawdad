# Addresses and TLS

Where the service listens and how a browser on another device reaches it safely. On this machine
the client is opened over plain HTTP on `127.0.0.1`; other devices of the owner, such as a laptop
or a phone in the owner's VPN, open a private network address that is served only over TLS.

Areas of this file: `NET` (the addresses the service listens on), `TLS` (certificates of the network
address).

## What this protects against

- **Eavesdropping and tampering on the network.** Everything that reaches the network address —
  the page, the conversation, pairing codes and device keys — goes over TLS (`TLS-1`). Plain HTTP
  on the network address is refused, not redirected (`NET-6`), so nothing sensitive is ever sent in
  the clear.
- **Exposure to the internet.** The service listens only on loopback and, if the owner asks, on one
  private address (`NET-5`); never on all interfaces or a public address. Remote access goes through
  the owner's own VPN, as [ADR-0002](../docs/adr/0002-service-and-thin-clients.md), decision 5,
  requires.
- **A stolen root key.** The service's own root certificate can only sign certificates for private
  addresses and the names of this machine (`TLS-8`), so its key cannot be used to impersonate
  other sites. Browsers that ignore name constraints — Chrome on Android among them, as of 2026 —
  do not get this protection.

Loopback stays on plain HTTP: its traffic never leaves the machine and browsers treat `127.0.0.1` as
a secure context. Other processes on the same machine are kept out by device keys, not by TLS.

## Scenarios

### S-NET-1. Only this machine

Covers: `NET-1`, `NET-2`, `NET-4`.

1. The owner starts the service with no options. It prints:

   ```
   Crawdad service is running. Open http://127.0.0.1:8460/ in a browser.
   ```

2. A browser on the same machine opens that address and gets the client page.
3. Another computer on the same local network connects to the machine's network address on port
   `8460` and gets no answer: the service does not listen there.

### S-NET-2. A phone in the owner's VPN, with a Tailscale certificate

Covers: `NET-1`, `NET-2`, `NET-4`, `NET-9`, `TLS-1`, `TLS-2`, `TLS-4`.

1. The owner has issued a certificate with `tailscale cert` for `laptop.example.ts.net`, which
   resolves to the machine's Tailscale address `100.101.102.103`.
2. The owner starts the service with these options:

   ```
   --host laptop.example.ts.net --tls-cert laptop.example.ts.net.crt --tls-key laptop.example.ts.net.key
   ```

   It prints:

   ```
   Crawdad service is running.
     On this machine: http://127.0.0.1:8460/
     On the network:  https://laptop.example.ts.net:8460/
   ```

3. The owner's phone, in the same tailnet, opens `https://laptop.example.ts.net:8460/`. The browser
   shows no warning; the client page loads.
4. At the same time `http://127.0.0.1:8460/` keeps working on the machine itself.
5. If the phone opens the address by IP, `https://100.101.102.103:8460/`, the browser warns that the
   certificate is for another name. The service does nothing about it: the owner opens the printed
   address.

### S-NET-3. Plain HTTP on the network address

Covers: `NET-6`, `NET-7`.

1. The service runs as in `S-NET-2`.
2. A browser opens `http://laptop.example.ts.net:8460/`. It gets no page and no redirect to
   `https://`; the browser shows a connection error.
3. A browser on the machine opens `https://127.0.0.1:8460/`. It gets no secure connection either;
   `http://127.0.0.1:8460/` works.

### S-NET-4. An address that is not allowed

Covers: `NET-3`, `NET-5`.

1. The owner starts the service with `--host 203.0.113.5`, a public address.
2. The service does not start and listens on no address. It says that the address is not private,
   that the service never listens on the internet, and which ranges are allowed.
3. The same happens with `--host 127.0.0.1`, `--host 0.0.0.0` and an IPv6 address, each with its
   own reason.

### S-TLS-1. The service's own root certificate

Covers: `TLS-7`–`TLS-11`.

1. The owner has no certificate files and starts the service with `--host 192.168.1.20` for the
   first time. The service creates its own root certificate and prints, after both addresses:

   ```
     Root certificate SHA-256: 5F:3A:…:C1
   ```

2. The owner exports the root certificate on the service machine (`S-CA-1` in
   [owner-commands.md](owner-commands.md)) and installs it once on a laptop,
   checking that its fingerprint matches the printed one.
3. The laptop opens `https://192.168.1.20:8460/` with no browser warning.
4. Later the owner restarts the service with `--host 192.168.1.30`. The laptop opens the new address
   with no warning and nothing to reinstall.
5. Nobody can download the root certificate from the service, over either address and at any path.

### S-TLS-2. A certificate file that cannot serve

Covers: `TLS-2`, `TLS-3`.

1. The owner starts the service with `--tls-cert` and `--tls-key`, but the certificate has expired.
2. The service does not start and listens on no address. The message names the certificate file and
   says that it expired, and when.
3. The service does not fall back to its own root certificate. The same happens when a file is
   missing, cannot be read, is not PEM, or the key does not belong to the certificate.

### S-TLS-3. Renewing the certificate without a restart

Covers: `TLS-5`, `TLS-6`.

1. The service runs as in `S-NET-2`; the phone is connected.
2. Thirteen days before the certificate expires, the service writes a warning to its log; it repeats
   it once a day.
3. The owner runs `tailscale cert` again; the files change on disk.
4. Without a restart, the next connection from the phone gets the renewed certificate, with its new
   expiry date. The open connection keeps working.

### S-TLS-4. A device without the root certificate

Covers: `TLS-7`, `TLS-9`.

1. Tailscale is installed on the machine, and the owner has even issued a certificate with
   `tailscale cert`. The owner starts the service with `--host laptop.example.ts.net`, but without
   `--tls-cert` and `--tls-key`.
2. The service does not look for the Tailscale files by itself. It serves the network address with
   a certificate issued by its own root certificate.
3. A phone on which the owner has not installed that root opens
   `https://laptop.example.ts.net:8460/`. The browser warns that the certificate is not trusted.
4. The service does nothing about it. The owner either installs the root on the phone (`S-TLS-1`) or
   restarts the service with the Tailscale files (`S-NET-2`).

## Requirements

### Addresses

- **NET-1.** The service MUST always listen on `127.0.0.1` over plain HTTP, on the port set by
  `--port <number>`, `8460` by default.
- **NET-2.** `--host <address>` MUST add a second address — a private network address of this
  machine, served only over TLS, on the same port number. Without `--host` the service MUST NOT
  listen on any network interface. With `--port 0` each address MUST get any free port.
- **NET-3.** The host MUST be an IPv4 address or a host name. With an IPv6 address the service MUST
  refuse to start and say that IPv6 is not supported yet. A host name MUST be resolved at startup;
  its IPv6 addresses are ignored, and a name with no IPv4 address MUST be refused.
- **NET-4.** On start the service MUST print the addresses to open in a browser: without `--host`,
  the one loopback address; with `--host`, both addresses, the loopback one first. With its own root
  certificate (`TLS-7`) it MUST also print the SHA-256 fingerprint of that root. The exact wording
  around the addresses is not part of the contract.
- **NET-5.** Every IPv4 address of `--host` MUST be in one of the private ranges `10.0.0.0/8`,
  `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10` (carrier-grade NAT, used by Tailscale) or
  `169.254.0.0/16` (link-local). A loopback address, `0.0.0.0` or any other address MUST be refused
  at startup with a message that says why. A refused start MUST NOT listen on any address.
- **NET-6.** Plain HTTP on the network address MUST fail: no page, no redirect to `https://`.
- **NET-7.** The loopback address MUST NOT offer TLS.
- **NET-8.** The network address MUST NOT accept more than 32 connections at a time; further
  connections wait until one closes. An HTTP connection to the network address with no traffic for
  30 seconds MUST be closed.
- **NET-9.** Each address has a *site*: the scheme, name and port a browser uses to open it. The
  `Host` and `Origin` checks of [the handshake](conversation.md#handshake) compare against the site
  of the address the request arrived on:

  | Address | Site | `Host` | `Origin` |
  |---|---|---|---|
  | loopback | `http://127.0.0.1:<port>` | `127.0.0.1:<port>` | `http://127.0.0.1:<port>` |
  | network | `https://<name>:<port>` | `<name>:<port>` | `https://<name>:<port>` |

  `<name>` is the name of the network certificate (`TLS-4`, `TLS-9`). On port `443` the port is left
  out of the network site, `Host` and `Origin`, as browsers do. A page of one address is refused on
  the other.
- **NET-10.** On the network address, a request whose `Host` differs from the TLS server name the
  browser asked for MUST be refused.

### Certificates of the network address

- **TLS-1.** The network address MUST accept only TLS 1.2 and TLS 1.3.
- **TLS-2.** With `--tls-cert <file>` and `--tls-key <file>`, the service MUST serve the network
  address with that certificate (PEM, the server certificate first, then its chain) and that private
  key (PEM). The two options MUST be given together; one without the other, or both without
  `--host`, MUST be refused at startup.
- **TLS-3.** With certificate files, the service MUST refuse to start, listening on no address, when
  a file is missing or cannot be read, is not a PEM certificate or key, the key does not belong to
  the certificate, the certificate is not valid yet or has expired, or it has no DNS name or IP
  address in its subject alternative name. The message MUST name the file and the problem. The
  service MUST NOT fall back to its own root certificate.
- **TLS-4.** With certificate files, the name of the network address MUST be the first DNS name in
  the certificate's subject alternative name, or else its first IP address.
- **TLS-5.** When the certificate files change, the service MUST start serving the new certificate
  to new connections without a restart, about a second after the last change; open connections MUST
  keep working. A new certificate that fails a check of `TLS-3`, or whose name (`TLS-4`) differs from
  the one being served, MUST NOT be used: the service MUST keep the previous one and write a warning
  to its log. A changed name needs a restart.
- **TLS-6.** With certificate files, the service MUST write a warning to its log at startup and then
  once a day while the certificate expires in less than 14 days.
- **TLS-7.** Without certificate files, the service MUST serve the network address with a certificate
  issued by its own root certificate. The root MUST be created on the first start with `--host` and
  reused on every later start. A start without `--host` MUST NOT create it.
- **TLS-8.** The own root certificate MUST be valid for 3 years and MUST carry critical name
  constraints that permit only the private ranges of `NET-5` and the DNS names of this machine: its
  host name and the `--host` name, if `--host` was given as a name on the start that created the root.
- **TLS-9.** At every start the service MUST issue a new server certificate from its root, valid
  for 397 days, for the addresses of `--host`, the `--host` name if it is a name, and the host name
  of the machine. The name of the network address is `--host` as given.
- **TLS-10.** When the own root has expired, cannot be read, or does not cover a name it must sign,
  the service MUST refuse to start and say how to start over: delete the root and install the new
  one on the owner's devices. The service MUST NOT replace the root by itself, because the owner's
  devices trust that exact root.
- **TLS-11.** The service MUST NOT serve its root certificate or any private key on any address or
  path. The owner gets the root certificate on the service machine with `crawdad tls export-ca`
  ([Owner's commands](owner-commands.md), `CA-1`).
- **TLS-12.** The own root certificate MUST have an EC P-256 key and be signed with ECDSA and
  SHA-256. It MUST carry critical basic constraints `CA:true` with a path length of `0`, so that it
  signs only server certificates and no other CA, and a critical key usage of only `keyCertSign` and
  `cRLSign`.
- **TLS-13.** A server certificate issued by the own root MUST have an EC P-256 key and be signed
  with ECDSA and SHA-256. It MUST carry critical basic constraints `CA:false`, a critical key usage
  of only `digitalSignature`, and an extended key usage of only `serverAuth`.

## Messages

This file adds no messages. The startup output of `NET-4` is shown in `S-NET-1`, `S-NET-2` and
`S-TLS-1`.

## Implementation status

| Requirement | What the code does now | Fixed in |
|---|---|---|
