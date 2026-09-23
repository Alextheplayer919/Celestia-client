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
