# Packet decoding research (Bedrock 26.40 / protocol 2168)

Everything below was checked against real artifacts, not from memory. Where a claim
comes from a source, the source is named; where it was executed locally, it says so.

**Short version:** the protocol library works, the exact codec for 26.40 exists, and
read-only decoding of a packet from a raw buffer is verified working. But Bedrock
encrypts game traffic with a key that is agreed between the client and the server, so
a relay that only *copies* packets can never read coordinates, chat or inventory. That
is a property of the protocol, not a limitation of our code. Details and options below.

---

## 1. Which protocol version is Minecraft 26.40?

**2168.** Verified two ways:

- The protocol library itself: loading `Bedrock_v2168.CODEC` prints
  `protocolVersion = 2168`, `minecraftVersion = 1.26.40`, `raknetVersion = 11`.
  (Executed locally against the jar, see §2.)
- Public tracking: Minecraft Wiki lists 26.40 as protocol 2168 (released 2026-08-04),
  26.44 also 2168, 26.45 as 2169. Bedrock Edition Preview builds of the 26.40 line
  report 1011, so preview and release are *not* interchangeable.

The client's RakNet layer is a separate version (11) from the game protocol (2168) —
they are negotiated independently and both are visible to us (see §5).

## 2. The dependency: coordinates, repo and version

CloudburstMC's protocol library is **not on Maven Central** (all `repo1.maven.org`
paths 404). It is published as snapshots to OpenCollab:

```kotlin
repositories {
    maven("https://repo.opencollab.dev/maven-snapshots")
}

dependencies {
    // group: org.cloudburstmc.protocol
    implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta13-SNAPSHOT")
    implementation("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta13-SNAPSHOT")
    implementation("org.cloudburstmc.protocol:common:3.0.0.Beta13-SNAPSHOT")
}
```

- Latest snapshot at the time of writing: `3.0.0.Beta13-SNAPSHOT`,
  timestamped build `3.0.0.Beta13-20260921.100211-29` (2026-09-21).
- CloudburstMC/ProxyPass, the reference MITM implementation, pins
  `3.0.0.Beta13-20260916.122344-26` — a slightly older timestamp of the same Beta13 line.
- **Pin the timestamped version, not `-SNAPSHOT`,** for reproducible builds; the plain
  `-SNAPSHOT` moves whenever they publish. (Note the odd version scheme: alongside
  `Beta13` there is a `Beta111` — a string-sort trap if anything ever sorts these.)

Codecs shipped in that `bedrock-codec` jar: `v827, v844, v859, v860, v898, v924, v944,
v975, v1001, v2168, v2169, v2193`. So:

```kotlin
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168
val codec = Bedrock_v2168.CODEC   // protocol 2168 == Minecraft 1.26.40
```

(`Bedrock_v2168` extends `Bedrock_v1001`, which extends its way back to `Bedrock_v291`;
each version only overrides what changed, which is why a v1001-era relay still mostly
works against a v2168 client.)

### Runtime classpath actually required (verified by running it)

To instantiate `Bedrock_v2168.CODEC` on a plain JVM the following were needed:

| Artifact | Notes |
| --- | --- |
| `org.cloudburstmc.protocol:bedrock-codec` (+ `common`) | the codec itself |
| `io.netty:netty-buffer`, `io.netty:netty-common` 4.1.101.Final | `ByteBuf` is the decode currency |
| `org.cloudburstmc.fastutil.commons:long-common` 8.5.15 | |
| `org.cloudburstmc.fastutil.maps:long-object-maps`, `int-object-maps`, `object-int-maps` 8.5.15 | |
| `org.cloudburstmc.fastutil:core` 8.5.15 | does **not** contain `it.unimi.dsi.fastutil.objects.ObjectCollection` |
| `it.unimi.dsi:fastutil` 8.5.15 | needed in addition to the split Cloudburst jars, or `TypeMap` fails to load |
| `org.bitbucket.b_c:jose4j` 0.9.6 | JWT parsing/validation |
| `org.cloudburstmc:nbt` 3.0.5.Final | NBT (item/block palette data) |
| `com.fasterxml.jackson.core:jackson-annotations` 2.18.0 | runtime |
| `org.cloudburstmc.math:immutable` 2.0-SNAPSHOT | only in `maven-snapshots`, timestamped `2.0-20220327.162752-1` |
| `com.nukkitx:natives` 1.0.3 | only in `maven-releases`, not Central |
| `org.slf4j:slf4j-api` (+ a binding) | pulled in by serializers; **without it the codec class fails to initialise** |

That is the realistic Android cost too: ~3 MB of extra classes plus slf4j and fastutil.
`netty-buffer` alone is safe on Android; the `netty-transport-raknet` transport (needed
only if you *terminate* RakNet, see §7) is the heavier dependency.

## 3. Read-only decoding: verified working, and the exact API contract

Executed locally: encode a packet into a plain `ByteBuf`, decode it straight back, with
no session, no channel, no Netty transport, no Android:

```
encoded body bytes = 4
packet id = 0x2
decoded = PlayStatusPacket
status  = LOGIN_SUCCESS
decoded2 = TextPacket -> hello from a raw ByteBuf
```

The contract for the decode layer (this is the part that is easy to get wrong):

- `codec.createHelper()` → a `BedrockCodecHelper` (no session needed).
- `codec.tryEncode(helper, byteBuf, packet)` writes the packet **body only** — the
  packet id is *not* included, that is the frame layer's job.
- `codec.tryDecode(helper, byteBuf, packetId)` needs the id passed **in explicitly**, and
  the buffer positioned at the body. Pass a wrong id and you get an `UnknownPacket`
  rather than an exception.
- `codec.getPacketDefinition(id)` → definition (id, factory, serializer, recipient);
  `codec.getPacketDefinition(SomePacket::class.java).getId()` gives the id back.
- Frame layout inside a batch is `[varint length][varint packet id][body]`, which is what
  `net/RakNet.kt` (`BedrockBatchReader`) already parses.

Full packet-id table for 2168 as used by this codec: [protocol-2168-packets.txt](protocol-2168-packets.txt)
(187 registered ids, dumped from the codec). The ids we rely on for connection-phase
detection — `0x01 Login`, `0x02 PlayStatus`, `0x03 ServerToClientHandshake`,
`0x04 ClientToServerHandshake`, `0x05 Disconnect`, `0x0B StartGame`,
`0x8F NetworkSettings` — were all confirmed against that dump.

## 4. Why an encrypted session cannot be sniffed (the important bit)

Bedrock's login handshake, in order:

1. Client → server: **Login**. Cleartext. Contains the JWT chain, and the chain carries
   the client's public key.
2. Server → client: **ServerToClientHandshake**. Cleartext. Contains the server's
   ephemeral public key and a salt.
3. Both sides compute the shared secret by ECDH between the client's private key and the
   server's public key (`EncryptionUtils.getSecretKey(PrivateKey, PublicKey, salt)`).
4. Client → server: **ClientToServerHandshake**. Already encrypted with that secret.
5. Everything after (NetworkSettings, StartGame, and all gameplay) is encrypted with
   AES (`EncryptionUtils.createCipher(...)`) and prefixed with an 8-byte checksum header,
   with batches compressed on top.

A relay that copies packets sees steps 1 and 2 — both public keys — and nothing else
useful. ECDH requires a **private** key: with neither the client's nor the server's, the
shared secret is not computable. So for a session like this, an on-device passthrough
proxy can count bytes and decode the handshake, and cannot decode:

- coordinates, chat, damage, inventory, entity data (all post-handshake), and
- even the resource-pack/login responses, once encryption has started.

This is not a tooling gap; it is the same reason a packet capture of a Bedrock session
is unreadable without terminating the session.

### The only alternative: terminate the session (what ProxyPass does)

CloudburstMC/ProxyPass (AGPL-3.0, Java 17, last commit 2026-09-16) is a real MITM: it
runs a RakNet *server* for the client and a RakNet *client* for the real server, decrypts
both legs, and forwards anything it does not handle as a raw `UnknownPacket`
(`ProxyServerSession.onPacket` → `wrapper.getPacketBuffer().retainedSlice().skipBytes(headerLength)`).
Because it holds both private keys it can decrypt everything.

Two consequences that matter for this repo:

- It has to forge/re-route the client's identity: the README requires the target server to
  run with `online-mode=false`, precisely because it can no longer present the client's
  real, Mojang-signed chain to the server. On a normal server, that login would be rejected.
- It therefore **changes what the server receives**, which is exactly the line this repo's
  README draws ("no rewriting"), and on a third-party server it is a good way to get banned.

## 5. What is observable without decrypting anything (implemented in this repo)

Implemented in `net/RakNet.kt`, `net/ConnectionStats.kt`, `net/BedrockFlowInspector.kt`,
all read-only and all covered by `InspectionChecks` (107 checks):

- **Server metadata** from the unconnected pong (0x1C): MOTD, game version, protocol
  version, player count/max. The pong payload is the semicolon-delimited advertisement.
- **RakNet negotiation**: MTU (inferred from the padded `OpenConnectionRequest1` length,
  `mtu = payloadLength + 28`), and the client's RakNet protocol version (byte 17 of that
  same packet).
- **RTT** from `ConnectedPing`/`ConnectedPong`: the pong echoes the sender's timestamp.
  Only the *client's* ping is usable for a local RTT, because its clock is this device's
  clock; a server ping uses the server's clock and must not be mixed in.
- **Packet loss** the way RakNet measures it: a NACK lists datagram sequence numbers the
  receiver never got, an ACK lists the ones it did, so *missing / (missing + acked)* is the
  peer's own view of loss per direction. Loss on the client→server path shows up as NACKs
  arriving *downstream*, and vice versa.
- **Jitter**: smoothed RTT variation (RFC 3550-style EWMA of consecutive RTT deltas) plus
  an independent measure of how evenly datagrams arrive at this device.
- **Connection phase**: because the login is cleartext (§4), we can see Login and
  ServerToClientHandshake go by and report "handshake → login → encrypted → play".
- **Traffic shape**: batch counts/sizes, datagram sequence gaps, reordering/duplicates.

## 6. On "smoothing jitter" (deliberately not implemented)

The README lists buffering/evening out arrival timing as a feature. Having looked at the
wire format, an *upstream* delay is both out of scope and harmful, and even a *downstream*
reorder buffer fights RakNet: datagrams carry sequence numbers, the receiver NACKs
anything it sees as missing, and the sender retransmits. Holding datagrams back to even
out timing therefore manufactures losses and retransmits, and adds latency to a reliable,
ordered stream that is already being reassembled by RakNet's own layer. What is useful
(and implemented) is *measuring* jitter and showing it, so a bad link is visible instead
of guessed at.

## 7. If decode features are still wanted

Two honest routes:

1. **Stats-first (what this repo does now).** RakNet-level everything from §5. No
   dependencies, no rewriting, no risk to the account.
2. **MITM mode (bigger, and outside the current scope).** Terminate both RakNet legs with
   `netty-transport-raknet` + `Bedrock_v2168.CODEC`, decrypt both directions with
   `EncryptionUtils`, and forward unhandled packets as `UnknownPacket` like ProxyPass.
   This gives full packet visibility, but it rewrites the login the server sees, only
   works against servers that do not validate Xbox auth, and is the kind of change that
   needs to be opt-in, clearly labelled and separate from the relay. It should also be a
   conscious decision about ToS/ban risk, not a side effect of a feature request.

Nothing in this document changes what the current build does: it relays UDP packets
unchanged and reports statistics about them.

## 8. Appendix: wire layouts verified against the codec itself (2026-09-23)

The layouts below were not taken from documentation or memory. They were measured by
encoding packets with the pinned codec (`Bedrock_v2168.CODEC`) in a plain JVM program and
hexdumping the result, then reading the same bytes back. That is what `net/BedrockFlowInspector.kt`
now parses, and what `InspectionChecks` builds independently of the parser.

### 8.1 Login (`0x01`, client → server) — measured

```
offset  bytes                       meaning
0       int32 big-endian            protocol version (2168 -> 00 00 08 78)
4       unsigned varint             size of the remainder of the payload
..      int32 little-endian         length of the client-data JSON
..      bytes                       client-data JSON:
                                      {"Token":"","AuthenticationType":0,
                                       "Certificate":"{\"chain\":[\"<jwt>\", ...]}"}
..      int32 little-endian         length of the client JWT
..      bytes                       client JWT (JWS, ES384, header key "x5u")
```

Measured example (protocol 2168, one chain JWT, 34-byte chain token):

```
   0  00 00 08 78 b0 01 69 00 00 00 7b 22 54 6f 6b 65   ...x..i...{"Toke
  16  6e 22 3a 22 22 2c 22 41 75 74 68 65 6e 74 69 63   n":"","Authentic
  ...
 115  3f 00 00 00 65 79 4a 68 49 6a 6f 79 66 51 2e ...  ?...eyJhIjoyfQ...
```

Notes that matter:
- the protocol version is **big-endian**, the two length prefixes are **little-endian**
  (netty `writeInt` vs `writeIntLE`); the varint between them is skipped, its value was
  observed to equal the number of bytes that follow it;
- the chain is nested as an **escaped JSON string** inside `Certificate`, so JWTs inside it
  are not byte-aligned with the outer fields — hence the parser validates lengths and falls
  back to scanning for base64url runs if they do not add up;
- `AuthenticationType` distinguishes a real Xbox chain from a self-signed/guest login.

### 8.2 ServerToClientHandshake (`0x03`, server → client) — measured

```
[unsigned varint length][ JWT ]
```

Measured: a 61-byte JWT encoded as `3d 65 79 4a ...` (`0x3d` = 61). The JWT is what the
*server* puts there: header carries `alg` (`ES384`) and `x5u` (the server's public key),
payload carries `salt`. Reading it tells us the session is switching to
`AES` + ECDH-derived keys — and that nothing after it is readable by a passive relay
(§4). It does **not** let us derive the key: that needs one of the two private keys.

### 8.3 Reproducing the codec table (and this appendix)

`javap`/hexdump harnesses used (JVM 11, classpath = the pinned jars from §2 plus their
transitives):

- `Dump4.java` — walks the codec jar for `vNNN/Bedrock_vNNN.class`, reflects `CODEC`, and
  prints `protocolVersion -> minecraftVersion`. That output is the table in
  `net/ProtocolVersions.kt` verbatim (64 rows, 291 → 2193).
- `Dump5..Dump8.java` — encode a synthetic `LoginPacket` / `ServerToClientHandshakePacket`
  and hexdump the body; `Dump8` is the byte-preserving dump that produced the table above.

Rounding: the round-trip check in §3 still holds — `tryEncode` writes packet **bodies only**
and `tryDecode` requires the packet id to be passed explicitly.
