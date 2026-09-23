# Celestia UI

A **resource pack** that puts Celestia's look into the game's own HUD. It is the
supported way to touch the in-game UI: no APK is modified, nothing is re-signed,
and it keeps working after Minecraft updates.

## Install

1. Copy `celestia-ui.mcpack` to the phone.
2. Open it with Minecraft (tap the file), or drop the folder into
   `Android/data/com.mojang.minecraftpe/files/games/com.mojang/resource_packs/`.
3. **Settings → Global Resources → Celestia UI → Activate.** Restart if it does not
   appear immediately.

## What it changes

- a thin accent rule above the hotbar,
- a small "Celestia" badge in the same area, in the client's dark slate + blue,

All of it lives in `ui/hud_screen.json`, inserted into the vanilla `root_panel` via
modifications, so nothing vanilla is replaced or removed. The whole pack is tuned by
the variables at the top of that file — if the badge sits too high or too low on your
screen, change `celestia_badge_offset` and re-import.

## What it deliberately does not do

- **No coordinates.** A server can disable coordinate display on purpose
  (`showcoordinates false`); a UI pack that printed them anyway would be revealing
  something the server chose to hide. Same rule as the rest of this project.
- **No relay stats.** A resource pack has no networking, so it cannot reach the
  relay. Ping/jitter/loss stay in the app's overlay, which measures them itself.
- **No gameplay changes.** Cosmetic only.

## Why not patch the game instead

Because on Android Bedrock there is no supported way to: the game is a compiled,
obfuscated ARM64 binary with no mod loader (the community one was discontinued when
Mojang removed the symbols it needed), a repacked APK has to be re-signed — which
breaks Xbox Live sign-in and therefore multiplayer — and it would have to be redone
every few weeks. A resource pack is the part of "inside the game" that actually
exists as a supported mechanism.
