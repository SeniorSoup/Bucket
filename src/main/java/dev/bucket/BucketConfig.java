package dev.bucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JSON config at config/bucket.json. Missing keys fall back to the defaults below. */
public final class BucketConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BucketConfig instance = new BucketConfig();

	public boolean enabled = true;
	/** Log every correction to the server console (useful while tuning). */
	public boolean logCorrections = false;

	// ---- who gets corrected ----
	/**
	 * false (default): checks run for everyone but only OBSERVE; corrections are applied only to players that were
	 * detected as a hacked client or were added with /anticheat add. true: correct everybody (old behaviour).
	 */
	public boolean enforceAll = false;
	/** Violation score at which a player is flagged as a hacked client. Lower = more sensitive. */
	public double flagThreshold = 8;
	/** How fast the violation score cools down, per second. */
	public double scoreDecayPerSec = 0.25;
	/** Send "HACKCLIENT DETECTED" to online staff (permission level 2+) and the console. */
	public boolean reportToStaff = true;
	public boolean reportToConsole = true;
	/** Client brand strings (case-insensitive substring) that flag a player immediately. Many clients spoof this, so it's only a shortcut. */
	public List<String> blockedBrands = new ArrayList<>(List.of(
		"liquidbounce", "meteor", "wurst", "impact", "aristois", "rusherhack",
		"bleachhack", "salhack", "thunderhack", "futureclient", "novoline", "inertia"));

	// ---- damage guard (spear / mace) ----
	public boolean damageEnabled = true;
	/** Max raw (pre-armor) damage one spear hit may deal. Anything above is impossible and is clamped. */
	public double spearMaxRawDamage = 14;
	/** Same, while the attacker is riding (mounted charge). */
	public double spearMountedMaxRawDamage = 20;
	/** Mace: fall distance the server tracked + this slack is the most that counts towards smash damage. */
	public double maceFallSlack = 2.0;
	/** Mace: for flagged players, never count more than this many blocks of fall. */
	public double maceMaxFallBlocks = 24;
	/** Flagged players: one spear/mace hit may never take more than this fraction of the victim's max health (after armor and enchants). */
	public boolean blockOneShot = true;
	public double flaggedMaxHitFraction = 0.7;

	// ---- packet-rate ("timer") ----
	public boolean timerEnabled = true;
	/** Max banked time. Bigger = more tolerant to lag spikes, but also to blink/lag-switch. */
	public double timerMaxCreditMs = 1000;
	/** How far "ahead of real time" the client may run before packets are dropped. */
	public double timerDebtMs = 150;

	// ---- ground state ----
	/** Server decides onGround from collisions instead of trusting the client (kills NoFall, packet Criticals, ground spoof). */
	public boolean authoritativeOnGround = true;

	// ---- movement ----
	public boolean movementEnabled = true;
	public double speedMultiplier = 1.15;
	public double speedSlack = 0.06;
	/** Bucket size in ticks of allowed movement. Also absorbs server lag spikes, so don't go much lower. */
	public double bucketTicks = 20;
	public double fluidMultiplier = 2.5;
	public double slipperyMultiplier = 2.4;
	public double usingItemMultiplier = 0.6;
	public double elytraMaxPerTick = 4.0;
	public long graceMs = 1500;
	public long velocityGraceMs = 1200;
	public long explosionGraceMs = 1500;

	// ---- flight / vertical ----
	public boolean flightEnabled = true;
	public double airSlack = 0.15;

	// ---- combat ----
	public boolean combatEnabled = true;
	public double entityReachTolerance = 0.8;
	public boolean entityLineOfSight = true;
	public int maxAttacksPerTick = 2;
	public boolean oneTargetPerTick = true;
	public boolean noInteractWhileUsingItem = true;

	// ---- world ----
	public boolean worldEnabled = true;
	public double blockReachTolerance = 0.8;
	public boolean blockLineOfSight = true;
	public int maxBreaksPerTick = 1;
	public int maxPlacesPerTick = 2;
	/** 0 disables. Max angle between look direction and the clicked point. */
	public double placeMaxAngleDeg = 80;
	public boolean blockAirPlace = true;

	// ---- inventory ----
	public boolean inventoryEnabled = true;
	public int maxContainerClicksPerTick = 8;

	public static BucketConfig get() {
		return instance;
	}

	public static void load(Path dir) {
		Path file = dir.resolve("bucket.json");
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					BucketConfig loaded = GSON.fromJson(r, BucketConfig.class);
					if (loaded != null) instance = loaded;
				}
			}
			// Always rewrite so new keys appear after updates.
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException | RuntimeException e) {
			BucketMod.LOGGER.error("Could not read/write {}; using defaults", file, e);
			instance = new BucketConfig();
		}
	}
}
