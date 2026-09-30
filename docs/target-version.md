# Target version

Pinned: **Minecraft Bedrock 1.21.111** ("The Copper Age", released 2025-09-30) —
protocol **844**, server build 1.21.111.1.

Why pinned at all: the terminated (MITM) session has to speak exactly one protocol
revision to decode and re-encode packets, so the project needs a single answer to
"which one". 1.21.111 is that answer while this pin is in force.

What the pin does and does not mean:

- The **transparent relay (0.2/0.3) is version-agnostic.** It copies datagrams and
  reads RakNet-level framing only, so it relays whatever revision the game and the
  server negotiate. Nothing in it changes because of this pin.
- The **terminated session decodes Bedrock packets**, so it is written against 844.
  Packets introduced after 844 are not decoded; unknown ids are surfaced as such
  rather than guessed at.
- Code stays **protocol-aware**: nothing may assume 844 when reading what the two
  peers actually agreed on. The negotiated version is still reported in the session
  stats and flagged when client and server disagree.

## Debug symbols for this version: none published

Checked 2026-09-30, directly against Mojang's download:

- The official `bedrock-server-1.21.111.1.zip` (75,576,611 B, sha256
  `659349ccf409bbc1a6b9e336d78e328466fcca266cc01e4038c382d14300c111`) contains
  **no** `bedrock_server_symbols.debug`.
- The shipped `bedrock_server` binary is **stripped**: no `.symtab`, no `.debug_*`
  sections, `nm` reports no symbols. Symbols cannot be recovered from it.
- A community mirror of the official zips (`Chew/BedrockDedicatedServer`, Git LFS)
  does carry a 115 MB `bedrock_server_symbols.debug`, but its contents are the
  **March 2020** release — the README there also notes one release shipped a ~1 GB
  debug file and then symbols stopped appearing.

So there is nothing published to corroborate packet names for 844. Protocol detail
continues to come from the community codec (CloudburstMC), as recorded in
`docs/decode-research.md`. `docs/protocol-2168-packets.txt` remains a reference for
the *newer* revision and must not be treated as this target's table.
