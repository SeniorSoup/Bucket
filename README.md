# Bucket: silent anti-cheat for Fabric 26.2

Server-side only. Clients need nothing installed. Nobody is kicked, banned, damaged or alerted *by the game*; a
cheat just stops working. Staff are told when a hacked client is detected.

## Who gets checked

By default (`"listOnly": true`) Bucket **only checks players on the `/anticheat` checklist**. Everybody else is never
touched, so legit players cannot be false-flagged and cost nothing. Listed players are always corrected, whatever the
client claims to be, so a client that spoofs its brand as `vanilla` is handled exactly like any other.

- Spot a cheater, `/anticheat add <player>`, and every check is enforced on them immediately.
- Cheat-client brands (`blockedBrands`) are only a hint: staff get a message suggesting `/anticheat add <name>`.
- When a listed player trips enough checks, staff and the console get `HACKCLIENT DETECTED <name> (client: <brand>) Speed x9, Reach x4`.
- Set `"listOnly": false` to observe everyone instead: violations only add to a score, and only players who reach
  `flagThreshold` (or are listed) are corrected.
- `/anticheat disable` switches every check off at runtime, `/anticheat enable` turns them back on. The setting is saved.

## Commands (op level 2)

| Command | What it does |
|---|---|
| `/anticheat enable` / `disable` | master switch, saved to config. `/anticheat status` shows the current state |
| `/anticheat <player>` | add an online player to the checklist (same as `add`) |
| `/anticheat add <player>` | add to the checklist: every check is always enforced on them |
| `/anticheat rm <name>` | remove from the checklist **and** clear a hack-client flag (works for offline names) |
| `/anticheat list clients` | detected hack clients: online/offline, client brand, when, which cheats |
| `/anticheat list players` | the manual checklist |
| `/anticheat list <player>` | stats: status, brand, score, corrections applied, violations per cheat, last violation |

## Spoofed packets, spear velocity and reach

- **Packets are checked before the server applies them.** A movement packet that covers more than a few ticks of speed
  (`maxPacketTicks`) or rises more than `maxRisePerPacket` is rejected and the player is set back. A fake "moved very
  fast" packet therefore never reaches velocity-based spear damage, and on a correction the player's motion is zeroed.
- **Hits are checked when damage is dealt, not only when the attack packet arrives.** Reach and line of sight are
  validated in the damage code, so spear jab, lunge and charge hits cannot bypass the packet checks. Spears use their
  own `spearReach`; sword sweeps get `sweepExtraReach` on secondary targets. A failed hit does nothing at all (no damage, no knockback).
- **Fly no longer fires on single noisy packets.** It takes `flyStrikes` (3) over-the-limit packets in a row, and the
  jump curve has `airSlack` and `airExtraTicks` of leniency. `/anticheat list <player>` shows the exact numbers of the
  last violation (for example `rise 1.62 > allowed 1.55 after 5 air packets`), so false positives can be tuned precisely.

## Spear and mace guard

Applies to checked players; values that are impossible in vanilla are clamped even before a flag.

- **Spear:** raw damage above `spearMaxRawDamage` (`spearMountedMaxRawDamage` when riding) is clamped.
- **Mace:** smash damage scales with fall distance. The movement checks track the real height the player fell from;
  if the server's fall distance is higher than that (`maceFallSlack`), the extra bonus (including Density) is scaled
  away. For flagged players fall is additionally capped at `maceMaxFallBlocks`.
- **No one-shots:** after armor, Protection and Blast/Projectile enchants are applied, a flagged player's spear or mace
  hit can never take more than `flaggedMaxHitFraction` (default 70%) of the victim's max health. Full netherite
  Protection V or no armor at all, a full-health player cannot be killed in one hit by them.

## What it neutralizes

| Cheat (LiquidBounce / Meteor) | How |
|---|---|
| Speed, BHop, Strafe boosts, NoSlow | Server-side movement budget from real attributes (speed effects, sprint, ice, water, item use) |
| Fly, Glide, Hover, HighJump, AirJump, Jesus, Spider, Step | Rising is bounded by jump strength + gravity, counted in movement packets so lag can't trip it |
| Timer, Blink, lag-switch | Movement packets limited to 20/s in real time, with a capped credit bank |
| NoFall, ground spoof, packet Criticals | The server decides `onGround` by collision, so fall damage and crits use the real value |
| Reach, KillAura through walls, Multi-aura | Eye-to-hitbox distance cap, line-of-sight raycast, one target per tick, attack rate cap, no attacking while using an item |
| Nuker, FastBreak (multi-block), Xray-nuker | Reach cap, line-of-sight to any part of the block, one block per tick |
| Scaffold, Tower, AirPlace, ChestAura | Reach cap, line-of-sight, no clicking air, look-direction cone, placements per tick |
| ChestStealer, InvManager, AutoArmor at zero delay | Container clicks per tick capped |
| Spear / mace one-shot, fall-distance spoof | Damage guard above |

The movement checks step aside after teleports, knockback, explosions/wind charges, riptide, elytra, slime
and bed bounces, fluids, ladders, webs, bubble columns, honey and scaffolding.

## What it cannot do (server can't see or stop these)

- **Velocity / anti-knockback**: the client simply ignores the push; the server never learns that.
- **ESP, Tracers, Xray (texture), Fullbright, Freecam view, NameTags**: purely client rendering.
- **AutoClicker / rotation-only KillAura** staying inside legit reach and view: indistinguishable from a good player.
- **Client brand is not proof**: most cheat clients can spoof `vanilla`/`fabric`, so `blockedBrands` is only a shortcut.
  Real detection is the behaviour score.
- **Speed at about 1.15x** vanilla is allowed on purpose (false-positive margin). Lower `speedMultiplier` and `speedSlack` to tighten.
- A cheater is not corrected until they are flagged, which normally takes well under a second of cheating.

## Upgrading from the previous version

`config/bucket.json` is versioned. An older file is saved as `bucket.json.old` and replaced by the new defaults on
first start (the old defaults were too strict for jumping). Re-apply your own tweaks afterwards. Your checklist in
`config/bucket-players.json` is kept.

## Build

Needs JDK 25 and Gradle 9.3+.

```
gradle wrapper --gradle-version 9.5.1   # once, if there is no wrapper yet
./gradlew build
```

The jar is `build/libs/bucket-1.0.0.jar`. Put it in the server's `mods/` folder next to Fabric API.
Config: `config/bucket.json` (restart to reload). Lists: `config/bucket-players.json`.

**This was written against the 26.x Mojang-mapped API without being able to compile against 26.2, so the first
build may need small name fixes.** See below.

## Tuning

Set `"logCorrections": true` in `config/bucket.json`, play normally, and check `/anticheat list <you>`. If a legit
player gets flagged, loosen the matching value or raise `flagThreshold`:

- `bucketTicks` / `timerMaxCreditMs`: servers with TPS dips
- `entityReachTolerance` / `blockReachTolerance`: high-ping players
- `placeMaxAngleDeg`: fast flick-bridgers
- `maxContainerClicksPerTick`: shift-drag inventory sorting
- `spearMaxRawDamage`, `maceFallSlack`, `maceMaxFallBlocks`: if your server changes spear/mace balance

## Things to verify on first compile

All are small renames if they differ in 26.2:

- `ServerGamePacketListenerImpl.teleport(double, double, double, float, float)` (the setback)
- `ServerboundInteractPacket.getTarget(ServerLevel)`
- `ServerboundPlayerActionPacket.getAction()/getPos()/getSequence()`, `ServerboundUseItemOnPacket.getSequence()`
- `ClientboundSetEntityMotionPacket.getId()`
- `ServerboundMovePlayerPacket.getX/getY/getZ(double)` and `isOnGround()`
- **New:** `LivingEntity.hurtServer(ServerLevel, DamageSource, float)` and `LivingEntity.getDamageAfterMagicAbsorb(DamageSource, float)` (the damage guard hooks)
- **New:** `DamageSource.getWeaponItem()`, `DamageTypes.MACE_SMASH`, `ItemTags.SPEARS`, `Entity.fallDistance`
- **New:** `BrandPayload.brand()`, `ServerboundCustomPayloadPacket.payload()`, field `connection` in `ServerCommonPacketListenerImpl`, and its `handleCustomPayload` method
- **New:** `ServerGamePacketListenerImpl.awaitingPositionFromClient` (shadowed field), `ItemTags.SWORDS`, `Entity.setDeltaMovement`
- **New:** permission checks: `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` and `ServerPlayer.createCommandSourceStack()`. If the permission API differs, change `Detector.isStaff` and the `.requires(...)` in `BucketCommand`.
- **New:** `EntityArgument.getPlayer`, `PlayerList.getPlayerByName/getPlayer(UUID)`, `CommandSourceStack.sendSuccess/sendFailure`
- MixinExtras imports: if `com.llamalad7.mixinextras...` doesn't resolve, add
  `implementation "io.github.llamalad7:mixinextras-fabric:0.5.0"` to `build.gradle`.
- Versions in `gradle.properties` (Loom, loader, Fabric API) came from a 26.2 project; bump if Gradle can't resolve them.
- If the mixin config rejects `JAVA_25`, use `JAVA_21`.
- `defaultRequire: 1` means a wrong method name in a mixin stops the server at startup with a clear error instead of silently disabling that check.
