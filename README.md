# Bucket: silent anti-cheat for Fabric 26.2

Server-side only. Clients need nothing installed. Nobody is kicked, banned, damaged or alerted *by the game*; a
cheat just stops working. Staff are told when a hacked client is detected.

## How it decides who to correct

1. Every check runs for every player, but for a normal player it only **observes**: the action goes through and
   adds to a violation score (which cools down over time).
2. When the score reaches `flagThreshold` (default 8), the player is **flagged as a hacked client**:
   - `HACKCLIENT DETECTED <name> (client: <brand>) Speed x9, Fly x2` is sent to online staff (op level 2+) and the console
   - they are saved to `config/bucket-players.json` and stay flagged across rejoins/restarts until `/anticheat rm`
3. From then on **only flagged players and checklisted players get corrected** (packet drops, setbacks, resyncs, damage caps).
4. Shortcuts that flag instantly: a known cheat-client brand (`blockedBrands`), or physically impossible spear/mace damage.

Set `"enforceAll": true` to get the old behaviour (correct everybody).

## Commands (op level 2)

| Command | What it does |
|---|---|
| `/anticheat <player>` | add an online player to the checklist (same as `add`) |
| `/anticheat add <player>` | add to the checklist: every check is always enforced on them |
| `/anticheat rm <name>` | remove from the checklist **and** clear a hack-client flag (works for offline names) |
| `/anticheat list clients` | detected hack clients: online/offline, client brand, when, which cheats |
| `/anticheat list players` | the manual checklist |
| `/anticheat list <player>` | stats: status, brand, score, corrections applied, violations per cheat, last violation |

## Spear and mace guard

Only enforced against flagged / checklisted attackers, except for values that are impossible in vanilla.

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
- **New:** permission checks: `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` and `ServerPlayer.createCommandSourceStack()`. If the permission API differs, change `Detector.isStaff` and the `.requires(...)` in `BucketCommand`.
- **New:** `EntityArgument.getPlayer`, `PlayerList.getPlayerByName/getPlayer(UUID)`, `CommandSourceStack.sendSuccess/sendFailure`
- MixinExtras imports: if `com.llamalad7.mixinextras...` doesn't resolve, add
  `implementation "io.github.llamalad7:mixinextras-fabric:0.5.0"` to `build.gradle`.
- Versions in `gradle.properties` (Loom, loader, Fabric API) came from a 26.2 project; bump if Gradle can't resolve them.
- If the mixin config rejects `JAVA_25`, use `JAVA_21`.
- `defaultRequire: 1` means a wrong method name in a mixin stops the server at startup with a clear error instead of silently disabling that check.
