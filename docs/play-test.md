# Play test

What to do, what to write down, and what the numbers mean. Written before the test,
not after, so the test cannot be shaped to fit the conclusion.

## Before starting

1. Install the current debug APK.
2. Choose the app to relay (Minecraft) and a target: **Browse servers → NetherGames**
   is the suggested one for a European connection. A US-hosted server measures the
   ocean, not the relay.
3. Leave **Keep Wi-Fi out of power save** on, and **Relay only this server** on.
4. Turn the HUD on if you want to watch it live (it is optional and toggleable in the
   click panel).

## Run the test in two passes

The point is a **comparison**, so both passes must be as similar as possible: same
server, same time of day, same place in the world, same render distance.

- **Pass A — without the relay.** Play for ~5 minutes with the relay off and watch the
  game's own numbers where it shows them.
- **Pass B — with the relay.** Play ~5 minutes in the same way, then tap
  **Share report** and keep the text.

Both passes, note by hand: things that cannot be measured from outside the game —
chunk pop-in, hit registration, whether the HUD's ping matches what the game shows.

## What the report gives and what it does not

It reports: RTT last/avg/min/max, jitter, **server-leg RTT measured at the relay's own
socket**, **per-packet relay cost (average and p95)**, loss up/down, MTU, RakNet
protocol, phase, datagram and byte volumes, errors, and session length.

It cannot report frame rate or chunk load times: nothing outside the game can see
them, and no claim will be made about them from these numbers.

## How to read it

- **Relay cost** is the only number here that is this app's fault. Under ~1 ms per
  packet average is a rounding error against a 50 ms round trip. Over 3 ms and the
  report says so itself.
- **Server-leg RTT vs the in-game ping**: the difference is roughly what the phone's
  radio and the client's own scheduling add. If the relay's RTT is *worse* than the
  game's own ping, the relay is in the wrong place.
- **Jitter and loss** are the ones that ruin a fight. Compare the two passes: if pass
  B is worse, the relay is the suspect; if both are bad, the link is.
- **p95 relay cost** matters more than the average: a rare 12 ms stall inside the
  relay is felt as a stall, while the average stays innocent.

## What would make this a failure

Worth writing down in advance: a failure is relay cost above ~3 ms average, or the
client-leg RTT rising by more than 5 ms versus the game's own measurement, or added
loss in pass B. Anything inside those bounds, and the relay is not the problem being
tested.
