# What Eclient does, and what this relay takes from it

Notes from reading [`Alextheplayer919/Eclient`](https://github.com/Alextheplayer919/Eclient)
(branch `main`, as of 2026-09-20, internal package `com.nexoraclient`, namespace
`com.rubidiumclient`). It was read purely as an **architecture and connection-handling
reference**. No feature, UI, auth flow or packet behaviour from it is copied here — it
targets anarchy servers and does things this project has explicitly ruled out (see the
README's "deliberately does not do" section).

## 1. Short version

Eclient's relay is a **terminating man-in-the-middle**, not a packet copy:

| | Eclient (`core/relay/`) | Celestia (this repo) |
|---|---|---|
| Shape | Netty RakNet **server** on `0.0.0.0:19150` + a second RakNet **client** leg to the real server | `VpnService` that copies UDP datagrams between the app and the real server |
| How the game finds it | `RAK_ADVERTISEMENT` / `BedrockPong` + `LanBroadcaster`, so it shows up in the game's LAN world list | it does not; the player connects to a real server address and the VPN transparently relays it |
| Sessions | two, bridged: client-leg `ServerSession` ↔ server-leg `ClientSession` (`RubidiumRelaySession`) | one, relayed byte-for-byte (`net/UdpNatSession.kt`) |
| Login | rewritten: the auth chain is replaced with the account's saved chain, the client JWT is **re-signed** (`resignClientJwtEnabled = true`) | passed through untouched |
| Encryption | possible to decrypt: on `ServerToClientHandshake` it derives the key with `EncryptionUtils.getSecretKey(ownPrivateKey, serverPublicKey, salt)` → `enableEncryption(secretKey)` | not possible: a passive relay holds neither private key (see §3) |
| Session state | `ConnectionManager`: StateFlow `IDLE → CONNECTING → HANDSHAKING → PLAYING → DISCONNECTED` + ping flow, listeners registered by priority | `ConnectionPhase` in `net/BedrockFlowInspector.kt`, surfaced through `StatsRegistry` |
| Codec choice | `CodecRegistry` (every version Cloudburst ships) + `AutoCodecListener` picks the codec from the peer's protocol version and patches the codec helper's definitions | `net/ProtocolVersions.kt`, table generated from the same codec classes |

It can decrypt because it **owns the login**: it re-signs the client JWT with a key it
holds, so the ECDH exchange in `ServerToClientHandshake` is one it can complete itself.
Our relay never sees a private key at any point, so that door is closed to it by
construction — and that is exactly why it works on servers that require Xbox
authentication, where a re-signing proxy generally does not.

## 2. What is taken from it

Everything below is read-only and does not change a single byte the client sends.

### 2.1 Version awareness from the observed protocol number

Eclient never hard-codes a version: `CodecRegistry` holds a protocol → codec-class table
and `AutoCodecListener` selects the codec from the peer's announced protocol version.

Adopted as `net/ProtocolVersions.kt`: a protocol → Minecraft version table **generated from
the codec classes** (`Bedrock_vNNN.CODEC.protocolVersion` / `.minecraftVersion`) rather
than typed in from memory. 64 entries, 291 (`1.2.0`) through 2193 (`1.26.50`), with the
project's target 2168 = `1.26.40` in it. It powers:

- a readable version in the UI instead of a bare integer,
- the client-vs-server comparison line (`ProtocolVersions.compare`), which is the fastest
  way to explain an "outdated client" style disconnect,
- and the future codec selection, when decoding lands, so that 26.40 is not hard-coded
  either.

### 2.2 A real connection state machine, fed by what is actually on the wire

Eclient's `ConnectionManager` is the piece worth copying conceptually: a single
observable state, plus ping, instead of scattered booleans. Ours already had
`ConnectionPhase`; it is now driven by *observed facts* rather than by guesses:

```
IDLE → SERVER_PING → RAKNET_HANDSHAKE → GAME_LOGIN → ENCRYPTED → PLAY / DISCONNECTED
```

with, at the last two steps, the facts from the cleartext login exchange:

- `clientLoginProtocol` — the version the client stated (`Login`, packet `0x01`),
- `loginHasChain` / `loginDisplayName` — whether the login carried an Xbox chain and which
  account name the client put in its own token,
- `handshakeAlgorithm` / `handshakeServerKeyPresent` / `handshakeSaltBytes` — what the
  server's `ServerToClientHandshake` (`0x03`) negotiated.

Before this change the UI could say "ENCRYPTED"; now it can say *"ES384, server key
present, 16B salt — game data is opaque past this point"*, which is the honest and useful
version of the same statement.

### 2.3 Read JWTs, never trust them

`JwtScan` decodes the base64url header/payload of the tokens that travel in the clear
during login. Deliberately: **no signature verification, no key material, no influence on
the traffic** — it is a display path only. Eclient takes the opposite approach (it verifies
and *replaces* chain and JWT, because it has to prove identity to the server); the reading
half of that code is all we can use, and all we want.

This is also what makes the login layout worth pinning down; it is documented byte-for-byte
in `docs/decode-research.md` ("Login packet layout"), including the `int32 BE` protocol
version and the `int32 LE` length prefixes that a wrong guess would silently misparse.

### 2.4 Lifecycle discipline

Two things Eclient gets right that are easy to get wrong in a service:

- `stop()` disconnects sessions and calls `shutdownGracefully(0, 1s)` **without blocking the
  main thread**, so the UI cannot freeze on teardown (`RubidiumRelay.stop`,
  `RubidiumRelaySession`).
- Session setup is cancellable and bounded (connect 10 s, session 20 s timeouts; a
  pre-connect queue that is flushed on connect rather than dropped).

This repo's equivalent is the earlier `onDestroy` fix (cancel the service job, never join
it on the main thread) and the bounded per-flow receive loops in `UdpNatSession`. There is
no pre-connect queue to port: a `DatagramSocket` can send to a remote address immediately,
so a copy relay has no "connecting" window to buffer across — the phase machine's
`RAKNET_HANDSHAKE` state covers the same ground observably.

### 2.5 Vendoring vs pinning the protocol code (not adopted, but understood)

Eclient vendors CloudburstMC/Protocol + NBT source into `relay/Protocol` and `relay/NBT`
with a manual GitHub workflow, explicitly to avoid depending on Maven `-SNAPSHOT`
artifacts drifting. This project keeps the Maven route but **pins a timestamped snapshot**
(`3.0.0.Beta13-20260921.100211-29`), which has the same goal — reproducible builds — with
less machinery and no build-time network clone. If snapshot drift ever bites, vendoring is
the fallback, and their workflow is the reference for doing it.

## 3. What is deliberately not taken

- **LAN advertisement + RakNet server termination.** Advertising a `BedrockPong` means the
  game joins *us*, which means being the server: two RakNet sessions, a login we must
  answer, and therefore MITM. The convenience (the world appears in the LAN list with no
  typing) costs the property that makes this proxy safe and broadly compatible: the
  client's real login reaches the real server unchanged.
- **Auth-chain replacement and JWT re-signing.** Requires holding the account's private key
  and changes what the server sees. Out of scope by the repo's own boundary ("no changes to
  what or when the client sends").
- **Packet rewriting / `UnknownPacket` passthrough.** Eclient (and ProxyPass) re-encode
  packets through a codec and forward the ones they do not handle. A re-encode of every
  packet is a class of bug we can avoid entirely: our relay copies bytes and only reads a
  mirror of them.
- **Feature surface** (modules, automation, anarchy-server tooling): out of scope by
  instruction.
- **Reading game data post-handshake.** Not a choice so much as a consequence: see §4.

## 4. Consequence for the decode roadmap

The two roads are now clearly separated:

| | Copy relay (this repo) | Terminating MITM (Eclient / ProxyPass) |
|---|---|---|
| Coordinates, chat, damage, inventory | **not readable** (AES key is ECDH-only between client and server) | readable |
| Works with Xbox-authenticated accounts | yes | only if the server does not validate the re-signed login |
| Changes what the server sees | nothing | the whole login |
| Needs account key material on-device | no | yes |

So for this project, "decode" means the *pre-handshake* window: RakNet details, the login
exchange, the version/identity facts above, plus timing/shape statistics for the encrypted
part (batch counts, sizes, RTT, jitter, loss — all of which the relay already reports).
Anything beyond that is a MITM decision, documented in `docs/decode-research.md` §7, and
should be an explicit, separate, opt-in mode rather than something the relay quietly
becomes.
