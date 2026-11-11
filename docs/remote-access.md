# Remote access

The service always listens on `127.0.0.1` over plain HTTP. To use it from a phone or another computer,
start it with `--host <private address>`. The service then also listens on that address, only over
TLS. Plain `http://` on the network address is refused.

`--host` takes only a private address: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
`100.64.0.0/10` (Tailscale) or `169.254.0.0/16`. Loopback, `0.0.0.0` and public addresses are refused
at start. IPv6 is not supported yet.

There are two ways to get a certificate for the network address:

- **Tailscale** (recommended). Tailscale issues a certificate that every browser trusts. Nothing has
  to be installed on your devices, and it also works when the phone or the home network is behind
  carrier-grade NAT, without port forwarding.
- **The service's own root.** Without certificate files, the service issues its own certificate. You
  install its root certificate on every device once.

The commands below run the service jar as in [development.md](development.md#run-the-service):

```sh
CRAWDAD="$JAVA_HOME/bin/java -jar service/target/crawdad-service-0.1.0-SNAPSHOT.jar"
```

Placeholders in angle brackets, such as `<machine>.<tailnet>.ts.net`, stand for your own values.

## Never pair through a certificate warning

If the browser warns about the certificate, close the page and do not enter the code. The warning
means the browser does not talk to your service, or talks to it through someone else. A device key
received this way can be used by that someone.

## With Tailscale

> **Unverified.** The Tailscale steps follow the Tailscale documentation and were not yet checked end
> to end on a real tailnet with this version.

1. Install Tailscale on the machine of the service and on the phone, and sign in to the same tailnet.
2. In the Tailscale admin console, on the **DNS** page, turn on **MagicDNS** and, under HTTPS
   Certificates, click **Enable HTTPS**.
3. On the machine of the service, get the certificate for its name:

   ```sh
   tailscale cert --cert-file <dir>/<machine>.crt --key-file <dir>/<machine>.key <machine>.<tailnet>.ts.net
   ```

   `tailscale status` shows the full name. Without root rights, `tailscale cert` needs
   `sudo tailscale set --operator=$USER` once; with `sudo`, make the files readable by your user
   afterwards. Keep the key file readable only by you (`chmod 600`).
4. Find the Tailscale address of the machine with `tailscale ip -4`. Start the service on it:

   ```sh
   $CRAWDAD --host <tailscale IPv4> --tls-cert <dir>/<machine>.crt --tls-key <dir>/<machine>.key
   ```

   The service prints the network address by name: `https://<machine>.<tailnet>.ts.net:8460/`.
   It does not start if the files are missing, unreadable, expired or do not match each other. It never
   falls back to its own root.
5. Pair the phone: run `$CRAWDAD pair` on the machine and scan the QR code with the phone, or open
   the printed link. Tap **Pair this device**.

**Open the service by name, not by IP.** The certificate holds the name, so
`https://<tailscale IPv4>:8460/` gets a certificate warning. The service checks `Origin` and `Host`
against the name too, so pairing and connections by IP are refused. Bookmark the name.

**The machine name becomes public.** Tailscale certificates come from Let's Encrypt. Every issued
certificate is published in public Certificate Transparency logs, with the full name
`<machine>.<tailnet>.ts.net`. Do not put anything private in the machine name. Tailscale lets you
pick a randomized tailnet name in the admin console.

**Renew before 90 days.** The certificate is valid for 90 days. Run the same `tailscale cert` command
again before it expires, writing to the same files. The service picks up the renewed files without a
restart and logs `Certificate reloaded`. If the new files cannot be used, it keeps serving the
previous certificate and logs a WARNING with the reason. Starting 14 days before the expiry, the
service warns in its log at start and once a day.

### Limit the port with a Tailscale ACL

By default every device in the tailnet can reach every port of the machine. The service lets in only
paired devices, but it is safer when only your own devices reach its port. A sketch of the tailnet
policy file (**Access controls** in the admin console):

```json
{
  "hosts": { "crawdad-machine": "<tailscale IPv4>" },
  "acls": [
    { "action": "accept", "src": ["<you>@<example.com>"], "dst": ["crawdad-machine:8460"] }
  ]
}
```

Merge this with your existing rules: the default policy allows everything, and a rule list without
it allows only what is listed. Use the port you start the service with.

## With the service's own root

Use this when Tailscale is not an option, for example on a home Wi-Fi network.

1. Start the service on the private address of the machine:

   ```sh
   $CRAWDAD --host <private address>
   ```

   The first start creates the root in `$XDG_DATA_HOME/crawdad/tls/` (`ca.crt` and `ca.key`). The root
   is valid for three years and limited to private addresses and the name of this machine. Every start
   issues a fresh server certificate for `--host`. The service prints the root fingerprint:

   ```text
   Crawdad service is running.
     On this machine: http://127.0.0.1:8460/
     On the network:  https://<private address>:8460/
     Root certificate SHA-256: <fingerprint>
   ```

2. Export the root:

   ```sh
   $CRAWDAD tls export-ca > crawdad-root.crt
   ```

   The certificate goes to standard output and `SHA-256 fingerprint: <fingerprint>` to standard error.
   Check that the fingerprint matches the one the service printed. Copy only `crawdad-root.crt` to
   your devices. Never copy `ca.key`: with it, anyone can issue certificates your devices trust.
3. Install the root on each device, and compare the fingerprint shown by the device with the one the
   service printed:
   - **Laptop** (unverified): import `crawdad-root.crt` as a trusted certificate authority. Chrome
     and Chromium on Linux: **Settings → Privacy and security → Security → Manage certificates**, or
     `certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "Crawdad root" -i crawdad-root.crt`. Firefox:
     **Settings → Privacy & Security → Certificates → View Certificates → Authorities → Import**.
     macOS: open the file in Keychain Access and set it to **Always Trust**.
   - **Android** (unverified; menu names differ between vendors): copy the file to the phone, then
     **Settings → Security & privacy → More security settings → Encryption & credentials → Install a
     certificate → CA certificate**. Chrome on Android trusts such a certificate without a warning.
     It does not honour the name limits of the root, so keep `ca.key` safe.
4. Pair: run `$CRAWDAD pair`, scan the QR code or open the printed link, and tap
   **Pair this device**.

Use a stable address for `--host`, such as a DHCP reservation. A device key works only on the address
it was paired on, so a new address needs a new pairing. The root itself covers any private address.

**Replacing the root.** Stop the service, delete `$XDG_DATA_HOME/crawdad/tls/` and start it again.
The service issues a new root. Remove the old root from every device, then install and check the new
one as above. The service never replaces an unusable root by itself: it stops with a message that
names the directory to delete.

## If a phone is lost

1. Remove the phone from the tailnet: in the Tailscale admin console, on the **Machines** page, open
   the phone and remove it. The ACL above still lets in every device of your user, so this is what
   cuts the network path.
2. Revoke its device key on the machine of the service:

   ```sh
   $CRAWDAD devices
   $CRAWDAD revoke <name>
   ```

   `revoke` closes the open connections of that device at once. The device then shows
   "This device was unpaired". `devices --all` also lists revoked devices with the date of revocation.
