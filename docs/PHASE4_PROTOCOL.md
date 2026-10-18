# Phase 4 protocol (connection layer only)

Scope: Phase 4 gate = PC receiver skeleton + USB (AOA) + Wi-Fi links, 6-digit pairing,
throughput/RTT numbers. No video, no input, no PC lock, no QUIC/WebRTC (ARCHITECTURE Q6:
plain TCP over Wi-Fi this phase; Q6 decided in Phase 5). D16: Rust receiver in `pc/`,
WinUSB (not libusb) for USB, standard crypto only.

## Transport per link

| Link | Transport | Who connects |
|---|---|---|
| Wi-Fi | plain TCP (Q6 this phase) | PC -> phone's port 47600 (phone advertises via NSD/mDNS, `_udlink._tcp`), same network |
| USB (AOA) | raw bulk endpoint streams (no IP, no TCP underneath) | PC -> phone streams opened via `UsbManager.openAccessory`; framing+TLS sit directly on the streams |
| USB (tethering fallback, D8) | plain TCP | PC -> phone's gateway address on the tether subnet |

PC is always the TCP/TLS client; the phone always listens (TCP 47600 or the AOA stream
pair). One receiver `exe`, CLI subcommands: `pair --addr HOST:PORT`, `bench --addr ...`,
`trust list|forget`, plus listen/accept helpers.

## Encryption, identity, pairing (D16 + ALGORITHMS 9)

TLS 1.3 (rustls on the PC, `javax.net.ssl` SSLSocket on Android - platform-maintained on
both sides; no home-made cryptography). Both sides own a **persistent self-signed
certificate** (PC: ECDSA P-256 via rcgen, stored in `%APPDATA%\unified-desktop-receiver\`;
phone: EC P-256 self-signed X.509 built in-app, stored in app-internal storage). The PC
presents its cert as a TLS **client certificate**; the phone presents its cert as the
server certificate. Both sides use a custom "first-time-relaxed, then pinned" verifier.

First connection ("numeric comparison over the TLS transcript", same security pattern as
Bluetooth LE numeric comparison):

1. TLS 1.3 handshake completes with both certs in flight (relaxed verifiers).
2. Both sides compute the shared 6-digit code: let `h1 = SHA256(certA DER)`,
   `h2 = SHA256(certB DER)` (each 32 bytes); sort the two hashes lexicographically;
   `n = SHA256(MIN || MAX)` taken as a big-endian integer mod 1,000,000; the code is `n`
   formatted as 6 decimal digits with leading zeros. Identical inputs (the certs actually
   used in this handshake) on both platforms, so both sides show the same 6 digits.
3. Phone shows the code with Yes/No; the user checks the PC's printed code and taps Yes.
4. On Yes the phone stores the PC's cert fingerprint (trusted-PC list, `files/p4/`);
   the PC stores the phone's fingerprint. Both switch their verifiers to
   "fingerprint-only". A later connect with a different cert is refused on either end.
5. Phone "forget PC" deletes the entry (and cuts its live connection); the PC can
   `trust forget`. The pair must re-run the code compare.

Why this construction: TLS 1.3 gives standard encryption + transcript integrity; the
self-signed certs + fingerprints give D16's "TLS 1.3 with pinned self-signed keys"; the
code is derived from the certificates that were actually used, so a MITM terminates two
different TLS sessions with two different cert sets and shows two different codes - the
user's compare catches it. No export-keying-material dependency (unavailable on the JVM
TLS socket API), no separate handshake library.

## Framing (inside TLS, byte stream)

```
offset  size  field
0       1     protocol version (1)          -> other value: close the session
1       1     channel (1=control 2=input 3=video 4=bench)
2       1     flags (bit0 = keep-alive echo request, bit1 = MORE_FOLLOWS: the logical
              message continues with another frame on this channel)
3       1     reserved (0)
4       4     payload length, big-endian (0..65536, "one chunk" cap enforced)
8..8+len      payload bytes
```

Chunking: a logical message MAY be any length up to 1 MiB this phase, but every frame on
the wire is at most 64 KiB (65536 B) of payload; senders set MORE_FOLLOWS on all but the
last chunk. This bounds how long a small input frame can wait behind a large video/bench
payload: at 10 MB/s one 64 KiB chunk costs ~6.6 ms, never one whole MiB. Frames of a
message keep their order; interleaving BETWEEN messages is by channel priority (below) at
chunk granularity.

Keep-alive: either side may send an empty payload with flags bit0 on any channel; the peer
responds with the same flags bit0 echo at its next send slot. 15 s without ANY inbound
byte = link dead. Interval 5 s.

Channels and priority (both directions; one TLS stream, one writer thread per direction,
per-channel FIFO queues drained strictly by priority: **input > control > video > bench**):
drain happens per 64 KiB chunk, so a 1 MiB bench message waits behind one chunk at most
and a small input/control frame can never sit behind an entire large message. Channel
semantics this phase: `control` = pair confirm/keep-alive, `bench` = throughput/RTT echo.
Input/video reserved for Phase 5 (ids already fixed so a big frame never delays a small
one by construction).

Bench protocol (bench channel only): throughput = PC pushes 1 MiB logical payload
messages (chunked by the writer) for T seconds, phone echoes each complete message back;
MB/s counted on the echoed bytes. RTT = 1000 64-byte ping messages (single chunk), phone
echoes each; per-sample time recorded, p50/p95/p99.

Versioning: version byte + cert pinning both stable; a future breaking protocol change
bumps the version byte and re-pairing, nothing else (Phase-4 scope has no discovery of
feature sets; keep it dumb).

Discovery (Wi-Fi): the phone advertises `_udlink._tcp` via `NsdManager` (instance
`unified-desktop`); the PC browses the same type with a hand-written mDNS query
(`udreceiver discover`: unicast-response bit set, PTR -> SRV -> A follow-ups) and
`pair`/`bench` accept `--addr auto` to use the first answer. The SRV record carries the
actual listening port (47600 today).

AOA note: the PC switches the phone with WinUSB control requests GET_PROTOCOL (51),
SEND_STRING (52; manufacturer `unified-desktop`, model `desktop` - the strings the
accessory filter matches) and START (53) (`udreceiver aoa start`); the phone then starts
the link service from the attach activity and adopts the accessory FD
(`ACC adopted fd=...`, status `acc=true`). The manifest side (`usb accessory` filter +
`UsbManager.getPermission` + `openAccessory`) is the same Phase-4 code; AOA only changes
the byte transport. The TLS-over-accessory-stream transport itself is not wired yet -
that is item 8's remaining work.
