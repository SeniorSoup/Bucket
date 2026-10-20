package dev.bucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** JSON config at config/bucket.json. Missing keys fall back to the defaults below. */
public final class BucketConfig {
	/** Bump when defaults change in a way old files must not override. The old file is kept as bucket.json.old. */
	private static final int CURRENT_VERSION = 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BucketConfig instance = fresh();
	private static Path file;

	/** Deliberately has NO initializer: files without it load as 0 and get reset to the new defaults. */
	public int configVersion;

	/** Master switch. Toggle in game with /anticheat enable|disable. */
	public boolean enabled = true;
	/**
	 * true (default): ONLY players on the /anticheat checklist are checked and corrected. Everyone else is not touched at all.
	 * false: everybody is observed, and only players detected as a hacked client (or listed) are corrected.
	 */
	public boolean listOnly = true;
	/** Log every correction to the server console (useful while tuning). */
	public boolean logCorrections = false;

	// ---- detection / reporting (used for listed players, and for everybody when listOnly=false) ----
	/** With listOnly=false: true = correct everybody, not just flagged players. */
	public boolean enforceAll = false;
	/** Violation score at which a player is flagged as a hacked client and "HACKCLIENT DETECTED" is reported. */
	public double flagThreshold = 8;
	public double scoreDecayPerSec = 0.25;
	public boolean reportToStaff = true;
	public boolean reportToConsole = true;
	/** Client brand strings (case-insensitive substring) that are reported / flagged. Cheat clients can spoof the brand, so this is only a hint. */
	public List<String> blockedBrands = new ArrayList<>(List.of(
		"liquidbounce", "meteor", "wurst", "impact", "aristois", "rusherhack",
		"bleachhack", "salhack", "thunderhack", "futureclient", "novoline", "inertia"));

	// ---- packet-rate ("timer") ----
	public boolean timerEnabled = true;
	public double timerMaxCreditMs = 1000;
	public double timerDebtMs = 150;

	// ---- ground state ----
	public boolean authoritativeOnGround = true;

	// ---- movement ----
	public boolean movementEnabled = true;
	public double speedMultiplier = 1.15;
	public double speedSlack = 0.06;
	public double bucketTicks = 20;
	public double fluidMultiplier = 2.5;
	public double slipperyMultiplier = 2.4;
	public double usingItemMultiplier = 0.6;
	public double elytraMaxPerTick = 4.0;
	public long graceMs = 1500;
	public long velocityGraceMs = 1200;
	public long explosionGraceMs = 1500;
	/**
	 * Spoofed-packet guard: a movement packet is checked BEFORE the server applies it. One packet may cover at most this
	 * many ticks of the player's speed, and may not rise more than maxRisePerPacket. Stops "move fast, then hit" tricks.
	 */
	public boolean preValidateMovement = true;
	public double maxPacketTicks = 4.0;
	public double maxRisePerPacket = 2.0;

	// ---- flight / vertical ----
	public boolean flightEnabled = true;
	/** Extra height allowed on top of the jump curve. */
	public double airSlack = 0.3;
	/** Extra airborne ticks of leniency on the jump curve. */
	public int airExtraTicks = 2;
	/** Over-the-bound packets in a row before it counts as Fly. One-off noise while jumping is ignored. */
	public int flyStrikes = 3;
	/** Extra step-up height allowed on top of the player's step height. */
	public double stepSlack = 0.2;

	// ---- combat ----
	public boolean combatEnabled = true;
	public double entityReachTolerance = 0.8;
	public boolean entityLineOfSight = true;
	public int maxAttacksPerTick = 2;
	public boolean oneTargetPerTick = true;
	public boolean noInteractWhileUsingItem = true;
	/** Also check reach / line of sight at the moment damage is dealt, so spear jab, lunge and charge hits cannot bypass the packet checks. */
	public boolean hitReachCheck = true;
	/** Spear attack reach (jab). Anything above spearReach + entityReachTolerance is dropped. */
	public double spearReach = 4.5;
	/** Extra reach allowed for sword sweep hits on secondary targets. */
	public double sweepExtraReach = 1.5;

	// ---- damage guard (spear / mace) ----
	public boolean damageEnabled = true;
	public double spearMaxRawDamage = 12;
	public double spearMountedMaxRawDamage = 20;
	public double maceFallSlack = 2.0;
	public double maceMaxFallBlocks = 24;
	public boolean blockOneShot = true;
	public double flaggedMaxHitFraction = 0.7;

	// ---- world ----
	public boolean worldEnabled = true;
	public double blockReachTolerance = 0.8;
	public boolean blockLineOfSight = true;
	public int maxBreaksPerTick = 1;
	public int maxPlacesPerTick = 2;
	public double placeMaxAngleDeg = 80;
	public boolean blockAirPlace = true;

	// ---- inventory ----
	public boolean inventoryEnabled = true;
	public int maxContainerClicksPerTick = 8;

	private static BucketConfig fresh() {
		BucketConfig c = new BucketConfig();
		c.configVersion = CURRENT_VERSION;
		return c;
	}

	public static BucketConfig get() {
		return instance;
	}

	public static void load(Path dir) {
		file = dir.resolve("bucket.json");
		try {
			if (Files.exists(file)) {
				BucketConfig loaded;
				try (Reader r = Files.newBufferedReader(file)) {
					loaded = GSON.fromJson(r, BucketConfig.class);
				}
				if (loaded != null && loaded.configVersion >= CURRENT_VERSION) {
					instance = loaded;
				} else {
					Files.copy(file, dir.resolve("bucket.json.old"), StandardCopyOption.REPLACE_EXISTING);
					BucketMod.LOGGER.info("Old bucket.json replaced by the new defaults (backup: bucket.json.old)");
					instance = fresh();
				}
			}
			save();
		} catch (IOException | RuntimeException e) {
			BucketMod.LOGGER.error("Could not read/write {}; using defaults", file, e);
			instance = fresh();
		}
	}

	public static synchronized void save() {
		if (file == null) return;
		try (Writer w = Files.newBufferedWriter(file)) {
			GSON.toJson(instance, w);
		} catch (IOException e) {
			BucketMod.LOGGER.error("Could not write {}", file, e);
		}
	}
}
