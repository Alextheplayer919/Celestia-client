# Celestia — local Bedrock relay for Android

A local, on-device UDP relay for Minecraft Bedrock Edition on Android, built with
`VpnService`. It sits between your Minecraft client and whatever server you connect to
and reports what your connection is actually doing.

## What works today

- **Relays Minecraft's UDP traffic** to the real server and back, byte-for-byte in both
  directions. Scope is limited to `com.mojang.minecraftpe` via
  `addAllowedApplication`, so other apps are untouched.
- **Runs as a foreground service** with a live status notification, because a VPN that
  isn't foregrounded gets killed exactly when the game is in front.
- **Connection statistics from real packet data** — all read-only, all derived from the
  bytes already flowing:
  - ping: last / min / avg / max, from RakNet `ConnectedPing`/`ConnectedPong` echoes
  - RTT jitter (smoothed) *and* an independent measure of how evenly packets arrive
  - packet loss per direction, from RakNet's own ACKs/NACKs — i.e. what the peer says
    it did and didn't receive, not a guess from gaps
  - throughput up/down, per flow and total
- **Server metadata without touching the session**: MOTD, game version, protocol version,
  player counts, plus negotiated MTU and the client's RakNet protocol version.
- **Connection phase**: handshake → login → encrypted → play, visible because the Bedrock
  handshake is cleartext before encryption starts.
- **Honest handling of traffic it does not relay**: TCP and everything else that isn't
  UDP gets an ICMP "administratively prohibited" reply so it fails immediately and
  visibly instead of hanging until it times out.

## What it deliberately does not do

- **No packet rewriting, no timing games.** Upstream packets are forwarded immediately
  and unchanged. Nothing here can change *when* your input reaches the server.
- **No ESP / entity-through-walls / data the server did not send you.**
- **No decode of encrypted game traffic.** This is a protocol property, not a TODO:
  Bedrock derives its AES key via ECDH between the client and the server, and a passive
  relay holds neither private key, so coordinates/chat/inventory are unreadable to it.
  The full explanation, plus what the alternatives would cost, is in
  [docs/decode-research.md](docs/decode-research.md). What *is* readable (RakNet
  handshake, and the cleartext login before encryption turns on) is used above.
- **No downstream jitter buffer.** RakNet is a reliable, ordered, NACK-recovering stream;
  holding datagrams back to even out timing manufactures retransmits and adds latency.
  Jitter is measured and displayed instead — see the research doc §6.

## Architecture

```
Minecraft (Bedrock) --UDP--> [VpnService captures IPv4 traffic of Minecraft only]
                                        |
                                 [IPv4/UDP parsing]
                                        |
                    [per-flow NAT session: one protected UDP socket each]
                       - upstream: straight passthrough, no delay, no rewrite
                       - downstream: inject IPv4/UDP back into the tunnel
                       - read-only RakNet inspection alongside the relay
                                        |
                                 [Real Bedrock server]
```

`VpnService` is the only way to intercept another app's traffic on unrooted Android. The
packet parsing and all inspection logic are plain Kotlin with no Android dependencies, so
they run and are tested on a desktop JVM (see Testing below).

## Build

CI (`.github/workflows/build.yml`) only runs when something that can affect the
build changes. Documentation, README edits and repository housekeeping do not
start a job; source, tests, resources, Gradle files, the wrapper and the workflow
itself do.

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # the inspection test suite
```

GitHub Actions builds both on every push and PR (`.github/workflows/build.yml`) and
uploads the APK as an artifact.

Requirements: JDK 17, Android SDK for API 34. `minSdk` is 26.

### Notes for running it

- The proxy only relays traffic from the package named in
  `MinecraftVpnService.MINECRAFT_PACKAGE` (`com.mojang.minecraftpe`). If it isn't
  installed, the service refuses to start rather than capturing every app's traffic.
- While the VPN is up, hostname resolution inside Minecraft follows the VPN, so the
  builder declares DNS servers (`1.1.1.1`, `8.8.8.8`). IP-address servers don't need it.
- Android 14+ requires the foreground service type declaration; this app declares
  `specialUse` (there is no `vpn` type, and `systemExempted` is for system-level VPNs).
- Allow the notification permission, or the status notification will be hidden (the relay
  still runs).

## Testing

The inspection layer is covered by `InspectionChecks` (107 checks): IPv4/UDP parsing and
the ICMP builder, RakNet offline/connected/datagram/ACK parsing, Bedrock batch reading,
connection statistics maths, and the connection-phase state machine. Every synthetic
packet in the suite is built from the documented wire layout rather than from this
project's own writer code, so the parser is checked against an independent construction
of the format.

```bash
./gradlew testDebugUnitTest
```

It can also be run without Android tooling at all: copy
`app/src/main/java/com/proxy/mcbedrock/net/*.kt` plus
`app/src/test/java/com/proxy/mcbedrock/InspectionChecks.kt` into a directory with a
`main` that calls `InspectionChecks.runAll(verbose = true)` and compile with `kotlinc`.

## Servers and the play test

The target card has **Browse servers**, a convenience list of the featured (partner)
servers and a few well-known community ones, including the address round-trips and
ports handled properly. Nothing is probed to build it. The dialog also says the thing
that matters for the terminated-session mode: partner servers tie sign-in to Xbox
Live, so a relay that logs in as this app may be refused there.

**Share report** / **Copy** produce a session report — text and the same data as JSON —
with RTT, jitter, server-leg RTT measured at the relay's socket, the relay's own
per-packet cost (average and p95), loss, volumes and errors. The report says what it
cannot know rather than guessing, and flags itself when the relay's cost passes 3 ms
per packet. `docs/play-test.md` describes the two-pass test those numbers are for.

## In-game HUD

An optional stats HUD drawn over the game (`SYSTEM_ALERT_WINDOW`), plus a click panel to
configure it:

- modules: **Ping, Jitter, Packet loss, Throughput, Graph, Session state, Target, Server
  info, Login, Handshake** — every one a report on the relay's own measurements;
- drag it anywhere, snap to a corner (or turn snapping off), remembered between launches;
- a round floating button opens the panel: module switches, corner chips, reset, close.

It deliberately cannot show coordinates, inventory, armour, effects or chat (they are inside
encrypted game packets), the game's FPS (Android exposes no API for another app's frame rate),
or keystrokes (that needs input hooks). The panel says so in one line instead of leaving the
absence unexplained.

## Control panel

The app is a small control panel rather than a debug console:

- **Target server** — address and port, recent servers as chips, and **Scan
  network** (LAN discovery) to fill them in from a world that is open to LAN. The
  address is validated and normalised, so a pasted `raknet://host:19133/path`
  lands in the right fields.
- **Relay only this server** — with a target saved, the VPN routes just that
  server's `/32` addresses, so DNS and the rest of the device never touch the
  tunnel. Turn it off (or leave the address blank) and it falls back to relaying
  all UDP from the selected app.
- **Minecraft app** — pick which installed app to relay. Minecraft-like packages
  are listed first, "show all installed apps" is opt-in, and the choice is
  remembered. The list is obtained through a manifest `<queries>` block for
  launcher intents, so the app needs no restricted `QUERY_ALL_PACKAGES`
  permission.
- **Session** — a status pill, the tunnel mode, and the live values (phase, RTT
  min/avg/max, jitter, loss, throughput, MTU/RakNet protocol) with severity
  colours: green/amber/red for ping and loss, phase highlighted, the version
  comparison flagged when client and server disagree.

## What the relay can see (and what it cannot)

Cleartext, and therefore reported by the UI:

- the RakNet handshake: MTU, client RakNet protocol, server advertisement (MOTD, version,
  players), RTT/jitter/loss from ping and acknowledgement traffic;
- the login exchange: the protocol version the client logged in with (mapped to a
  Minecraft version), whether the login carried an Xbox chain, the account name the client
  put in its own token, and what the server's `ServerToClientHandshake` negotiated
  (`ES384`, server key present, salt size).

Not readable, by construction: everything after that handshake. Bedrock encrypts it with a
key derived by ECDH between the client and the server; a relay holds neither private key.
No coordinates, chat, inventory or entity data is decrypted, and nothing is written back —
the relay copies datagrams and only reads a mirror of them. Details, including the verified
byte layouts and the MITM alternative, are in `docs/decode-research.md`; what was learned
from the reference client is in `docs/eclient-notes.md`.

## Protocol notes

- Minecraft Bedrock **26.40 is protocol 2168** (`26.44` also 2168, `26.45` is 2169).
  Preview builds of the same line report 1011.
- RakNet wire details used here (packet ids, the unconnected magic, frame/reliability
  encoding, ACK/NACK records, and the little-endian 24-bit datagram sequence numbers)
  were verified against `Sandertv/go-raknet`, the reference implementation.
- The packet-id table for protocol 2168, dumped from the CloudburstMC codec, is in
  [docs/protocol-2168-packets.txt](docs/protocol-2168-packets.txt).

## Status

Working: relay, stats, notification UI, tests, CI. Not started: any decode-based feature
(blocked on the encryption question above, which is written up rather than guessed at).
