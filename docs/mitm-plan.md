# Terminated-session relay (MITM) — plan and status

This is the mode where the relay stops copying datagrams and instead **terminates**
the connection on both sides, which is the only way any app can read a Bedrock
session: the session key is derived by ECDH between the two endpoints, so a relay
that merely forwards bytes holds no key (see §4 of `decode-research.md`).

Decision and context: the user explicitly chose this over the passive relay, and
plays on community servers (not official/Realms), which is the setting where a
re-signed login is accepted at all.

## The shape

```
   game ──RakNet──► relay (server leg as "server")   relay (client leg as "client") ──RakNet──► server
                     key: generated per session        key: the signed-in account's
                     decrypts what the game sends      re-signed login, decrypts what the server sends
```

Two independent key exchanges, one on each leg. Nothing about the game's own
crypto is broken: the relay replaces the session and re-encrypts what it forwards.

## Costs, stated plainly

- **The server authenticates this app, not the game.** The relay logs in with the
  account signed in inside the app. Community servers generally accept this; Xbox
  auth-enforcing servers do not.
- **The account's private key lives on this device.** It is generated here, stored
  here, and never transmitted anywhere except as signatures. There is no server
  component of this project for it to leak to.
- **This is the boundary crossing.** Up to this point everything was read-only and
  the login was untouched. Any server-side effect of a rewritten login (session
  bookkeeping, IP mismatch checks, anti-proxy plugins) is now in play.
- **Still not a cheat client.** Decrypted data is displayed, never used to decide
  what to send. No automation, no packet rewriting towards the game, no
  server-hidden data. If that changes it will be an explicit, separate,
  opt-in decision — not a side effect of this work.

## Increments

### 1. Core: login rewriting + key exchanges — DONE
`relay/LoginRewrite.kt`, `relay/MitmHandshake.kt`, `relay/AccountIdentity.kt`.
Verified in `MitmChecks` (52 checks) against the real libraries: both exchanges
agree, encrypt/decrypt round trips, the re-signed login verifies with the account
key and survives a codec round trip. Bugs found and fixed in the process: inverted
`createCipher` flags, standard-vs-URL base64 salt, jose4j `NumericDate`.

### 2. Sign-in: Microsoft device-code flow → Bedrock chain
Next. Device-code OAuth (user opens microsoft.com/link, enters a code — no password
handling in this app), then Xbox Live → XSTS → the multiplayer authentication
service, which returns a chain binding the public half of a keypair generated on
this device. Store chain + private key locally (Keystore-encrypted), show the
account name in the UI.
*Needs a researched client id and exact endpoint contract before writing code —
no guessing here.*

Acceptance: sign-in produces a chain whose `identityPublicKey` matches our stored
public key; sign-out wipes both.

### 3. RakNet server on the device
Netty (`org.cloudburstmc.netty:netty-transport-raknet`, the library the reference
client uses) listening locally; game connects either by adding the address by hand
or through LAN advertisement (`UnconnectedPong` / `BedrockPong`).
Acceptance: the game completes a RakNet handshake and reaches the login stage.

### 4. Session bridge
Bind the game's `ServerSession` to a `ClientSession` to the real server; forward
unhandled packets unchanged (never re-encode what we do not understand); handle
`Login`, both handshakes, `PlayStatus`, `Disconnect`.
Acceptance: a player joins a real server through the relay and can play, with the
relay decoding what it sees.

### 5. Decoded data into the UI
Feed the HUD from real packets (coordinates, inventory, effects — all client-visible
information), replacing the "cannot show" note where it no longer applies.

### 6. Play test
Session report export so the test returns numbers: relay-added RTT, jitter before
and after, loss, phases, and (for the MITM mode) a count of packets decoded per
direction.

## Open questions to resolve before each increment

- Device-code flow: which client id/scopes are legitimate to use, and what the
  multiplayer auth service expects exactly (headers, body, error cases).
- Whether the game will accept a `ServerToClientHandshake` signed by a key it has
  never seen, and what it does if not (measured against a real client, on device).
- How the server reacts to a login whose chain is valid but whose connection comes
  from a phone rather than a LAN — i.e. any IP/anti-proxy checks.
- Multi-account handling and revocation: what "sign out" must erase to be honest.
