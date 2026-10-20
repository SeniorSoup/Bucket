package dev.bucket;

import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

/** Per-player bookkeeping. Touched from the server thread (and the packet-send hook, for a few volatile fields). */
public final class PlayerState {
	// movement baseline
	public boolean hasLast;
	public double lastX, lastY, lastZ;
	public boolean lastGround = true;

	/** Highest y reached since the player last touched the ground. Used to tell real falls from spoofed fall distance (mace). */
	public double peakY = Double.NaN;

	// packet-rate
	public long lastPacketMs;
	public double timerCredit;

	// horizontal speed bucket (NaN = refill on next use)
	public double tokens = Double.NaN;

	// airborne session (for the vertical bound)
	public boolean sessionValid;
	public double takeoffY;
	public int airPackets;

	// exemptions
	public volatile long graceUntilMs;     // everything skipped (teleport, explosion, bounce...)
	public volatile long vertGraceUntilMs; // only vertical skipped (knockback)

	// per-tick counters
	public int tick = -1;
	public int attacksThisTick;
	public int attackTargetId = -1;
	public int placesThisTick;
	public int clicksThisTick;
	public final Set<Long> breaksThisTick = new HashSet<>();

	// ---- detection / reporting ----
	/** True once this player has been identified as running a hacked client. Only flagged players get corrected. */
	public volatile boolean flagged;
	public volatile long flaggedAtMs;
	public volatile String brand;
	public volatile boolean brandResolved;
	public int brandTries;
	public double score;
	public long scoreMs;
	public int corrections;
	public volatile String lastDetail = "";
	public volatile long lastViolationMs;
	public final EnumMap<Detector.Cat, Integer> counts = new EnumMap<>(Detector.Cat.class);

	public void rollTick(int t) {
		if (t != tick) {
			tick = t;
			attacksThisTick = 0;
			attackTargetId = -1;
			placesThisTick = 0;
			clicksThisTick = 0;
			breaksThisTick.clear();
		}
	}

	public synchronized void clearDetection() {
		flagged = false;
		flaggedAtMs = 0;
		score = 0;
		scoreMs = 0;
		corrections = 0;
		counts.clear();
		lastDetail = "";
		lastViolationMs = 0;
	}

	/** Re-baseline on the player's current server-side position and give a short grace period. */
	public void reset(ServerPlayer p, long now, long graceMs) {
		hasLast = true;
		lastX = p.getX();
		lastY = p.getY();
		lastZ = p.getZ();
		lastGround = p.onGround();
		peakY = p.getY();
		tokens = Double.NaN;
		sessionValid = false;
		airPackets = 0;
		timerCredit = 0;
		lastPacketMs = now;
		graceUntilMs = Math.max(graceUntilMs, now + graceMs);
	}
}
