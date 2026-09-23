# Bedrock Local Proxy (scaffold)

A local, on-device UDP proxy for Minecraft Bedrock Edition on Android, built with
`VpnService`. Sits between your Minecraft client and whatever server you connect to,
so you can:

- Smooth out packet jitter (buffer + even out arrival timing before it reaches Minecraft)
- Log/display your own connection stats (ping, packet loss) from real packet data
- Decode Bedrock protocol packets (via CloudburstMC/Protocol) for read-only overlay
  features: your own coordinates, chat log, combat/damage log, inventory tracking

## Explicitly out of scope

This scaffold does **not** include, and is not intended to grow into:
- Anything that changes when/what your client sends to the server (no input timing
  changes, no auto-anything, no movement/combat packet rewriting)
- Anything that reveals data the server didn't mean to expose to you (no ESP/ok
  entity-through-walls type features)

Those cross from "connection tooling" into cheat-client territory, which is a
different project with real consequences (bans, ToS violations) — keep this repo
to the read-only / passthrough-smoothing feature set.

## Architecture

```
Minecraft (Bedrock) --UDP:19132--> [VpnService captures all app UDP traffic]
                                          |
                                   [Local relay loop]
                                    - jitter buffer
                                    - stats collector
                                    - CloudburstMC/Protocol decode (read-only)
                                          |
                                   [Forward to real server]
                                          |
                             Real Bedrock Server <-------> back through same path
```

`VpnService` is the only way to intercept another app's traffic on unrooted Android
without a system-level MITM — it makes your device *think* all traffic goes through
a local "VPN" interface, which is actually your own relay loop. No root needed.

## Status

This is a **starting skeleton**, not a working proxy yet. It compiles and installs,
establishes the VPN interface, and has stub points marked `TODO` for:

1. Parsing captured IP/UDP packets out of the VPN interface's byte stream
2. Identifying Minecraft's RakNet handshake to isolate the right UDP flow
3. Relaying packets to the real server and back
4. Wiring in CloudburstMC/Protocol for decoding
5. A simple overlay/notification UI for stats

## Building

GitHub Actions can't be added by Claude's connector yet (needs a separate
Workflows permission grant) — see the note in the repo root. For now, build
locally:

```
./gradlew assembleDebug
```
APK lands in `app/build/outputs/apk/debug/`.
