# Who else is doing this

Researched before building further, because the answer changes what is worth
building. Short version: the *approach* is proven but its practitioners keep dying,
and the surviving mass-market product took a different route entirely. Nobody is
doing the combination this project is aiming at.

## Same approach as here: terminate the session, on Android, without touching the game

| Project | Status | What it is | Notes for us |
| --- | --- | --- | --- |
| **MuCuteClient / MuCuteRelay** (OpenMITM) | **Archived May 2025**, last commit tagged `1.21.80` | Android client + separate Java relay JAR. "MITM without modifying the game's memory or requiring root", cross-platform via Android MITM control, packet interception **and modification** | The closest thing to this project's architecture: relay JAR + Android UI. First party relationship: GPLv3, so it is **read for reference only — no code is copied into this MIT-licensed repo**. Also a cheat client (packet modification), which this project deliberately is not |
| **WClient** (RetrivedMods) | Repo kept as "legacy archive"; development moved private | Android-first utility client, "does not modify game memory", MITM packet interception, MCPE 1.26 | Same family. Stopped being public |
| **Eclient** | Active (Sep 2026) | This project's designated reference | Already studied; see `eclient-notes.md` |
| **ProxyPass** (CloudburstMC, PowerNukkit, GearsMC) | Alive, small | MITM *tool* for protocol developers, not a client. **Requires `online-mode=false` on the server** | Confirms the login-rewrite approach and its main constraint |
| **bedprox** (haveachin) | WIP stub | Reverse proxy in Go on gophertunnel | Reference only |

## The route that actually reaches a mass audience: launch the game through our app

| Project | Scale | What it is | Why it matters |
| --- | --- | --- | --- |
| **Atlas Client** | **500,000+ users**, free on Google Play (`net.atlasclient.atlaspe`), iOS sideloaded | 60+ **non-cheat quality-of-life** mods: FPS unlocker, RenderDragon shaders, waypoints, minimap, view model, zoom, version switcher. Tracks Bedrock releases, currently 26.44 | This is the closest thing to "the client the user wants", and it proves the market for a *fair* client exists on mobile. But its route is launching/hooking the game, not proxying it — the Play Store app cannot ship Minecraft, so it works through a game shell, which is the path ruled out here |
| **Apollon Client** | Distributed by APK-mirror sites | "Client APK", **655 MB** — the size is the tell that the game itself is bundled | The piracy-adjacent route. Exactly what this project does not do |
| **Flarial** | Windows + an Android APK, 140+ modules, Lua scripting | Bedrock utility client | Mobile support exists but is not open |
| **Latite**, **Horion** | Open source / closed | Windows-only DLL clients (Latite is the open-source one, with a JS/TS plugin system) | The Windows equivalent of the ecosystem. x86 DLL injection is not available to an Android app |

Also worth knowing: GitHub is full of zero-star repositories named "Borion Client",
"Flarial Client 2026" and similar, offering a "free client" download. That pattern —
generic name, no stars, an installer — is a malware distribution pattern, not a
client. Nothing in this project's workflow requires downloading a random client APK,
and a play tester should not either.

## What this means for Celestia

1. **The MITM approach works — it has been built on Android at least three times.**
   Nothing about our plan is speculative at the protocol level.
2. **But every MITM client above stopped.** MuCute was archived and WClient went
   private; both were cheat clients carrying ongoing packet-modification
   maintenance, and both died within roughly a year. Two lessons, taken seriously:
   keep the transparent relay working as the primary mode (it survives auth and
   protocol changes untouched), and keep this project's packet handling read-only so
   it never inherits a reason to chase every protocol update.
3. **Nobody is doing network-level quality of life.** Atlas and friends are
   render-level and cosmetic: FPS, shaders, minimaps, waypoints. Not one of them
   measures or improves the connection — no RTT/jitter/loss telemetry, no relay
   latency accounting, no chunk-load-path work. That is precisely what this project
   does, and it is the part that cannot be replicated by a resource pack or a UI mod.
4. **A fair client is commercially viable** (500k users for a non-cheat one), which
   answers the "is this a dead end" question with data rather than opinion.

## If the goal is quality-of-life mods today

Atlas Client is free, on the Play Store, non-cheat, and tracks 26.44. It does the
visual side well. It cannot do what this project does (nothing in it touches the
network), and it works the way we decided not to. Both can be true; a play tester
wanting both would run Atlas for the HUD and Celestia for the connection.
