package dev.bucket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Validation of movement packets. Nothing here punishes: a violation just means the caller teleports the
 * player back to the last position that was consistent with vanilla rules.
 */
public final class MovementChecks {
	private static final double GROUND_PROBE = 0.06;

	public enum Violation { NONE, SPEED, VERTICAL }

	private MovementChecks() {}

	/** Server-side ground test for a hypothetical position. Replaces the client's onGround claim. */
	public static boolean groundAt(ServerPlayer p, double x, double y, double z) {
		Level level = p.level();
		AABB box = p.getBoundingBox().move(x - p.getX(), y - p.getY(), z - p.getZ());
		return !level.noCollision(p, box.expandTowards(0.0, -GROUND_PROBE, 0.0));
	}

	private static boolean fullyExempt(ServerPlayer p, PlayerState st, long now) {
		return p.isCreative() || p.isSpectator() || p.getAbilities().mayfly || p.getAbilities().flying
			|| p.isPassenger() || now < st.graceUntilMs || p.isAutoSpinAttack();
	}

	/**
	 * Runs BEFORE the server applies the packet. Rejects packets that are impossible on their own: moving more than a few
	 * ticks' worth of speed at once, or rising several blocks in one packet. Because the server never applies them, a
	 * spoofed "move fast, then hit" packet cannot leak into velocity-based damage.
	 */
	public static Violation impossible(ServerPlayer p, PlayerState st, double px, double py, double pz,
									   long now, long elapsedMs, BucketConfig c) {
		if (fullyExempt(p, st, now)) return Violation.NONE;
		double dx = px - p.getX(), dy = py - p.getY(), dz = pz - p.getZ();

		if (c.movementEnabled) {
			double lim = limitPerTick(p, c);
			double hd = Math.hypot(dx, dz);
			double cap = lim * c.bucketTicks;
			double tokens = Double.isNaN(st.tokens) ? cap : Math.min(cap, st.tokens + lim * elapsedMs / 50.0);
			boolean pushed = now < st.vertGraceUntilMs; // knockback / lunge just moved the player
			if (hd > tokens || (!pushed && hd > lim * c.maxPacketTicks)) {
				st.moveDebug = String.format("packet moves %.2f blocks, per-packet max %.2f, budget %.2f",
					hd, lim * c.maxPacketTicks, tokens);
				return Violation.SPEED;
			}
		}
		if (c.flightEnabled && dy > c.maxRisePerPacket && !p.isFallFlying()
			&& now >= st.vertGraceUntilMs && !touchesSpecial(p)) {
			st.moveDebug = String.format("packet rises %.2f blocks (max %.2f)", dy, c.maxRisePerPacket);
			return Violation.VERTICAL;
		}
		return Violation.NONE;
	}

	public static Violation check(ServerPlayer p, PlayerState st, double preX, double preY, double preZ,
								  long now, long elapsedMs, BucketConfig c) {
		double nx = p.getX(), ny = p.getY(), nz = p.getZ();
		double dx = nx - preX, dy = ny - preY, dz = nz - preZ;
		boolean ground = p.onGround();
		boolean wasGround = st.lastGround;

		// ---- full exemptions: keep the baseline fresh, check nothing ----
		if (p.isAutoSpinAttack()) { // riptide
			st.graceUntilMs = now + c.graceMs;
		}
		if (fullyExempt(p, st, now)) {
			passthrough(st, ground, ny);
			return Violation.NONE;
		}

		// ---- horizontal speed: token bucket refilled in real time ----
		double lim = limitPerTick(p, c);
		double cap = lim * c.bucketTicks;
		double tokens = Double.isNaN(st.tokens) ? cap : Math.min(cap, st.tokens + lim * elapsedMs / 50.0);
		double hd = Math.hypot(dx, dz);
		if (c.movementEnabled && hd > tokens) {
			st.moveDebug = String.format("horizontal %.2f blocks, budget %.2f", hd, tokens);
			return Violation.SPEED;
		}

		// ---- vertical: no rising beyond what a jump + gravity allows, no hovering ----
		boolean fallFlying = p.isFallFlying();
		boolean special = touchesSpecial(p);
		boolean vertGrace = now < st.vertGraceUntilMs;
		boolean valid = st.sessionValid;
		double take = st.takeoffY;
		int air = st.airPackets;

		if (fallFlying || special || vertGrace) {
			valid = false;
			air = 0;
			st.flyStrikes = 0;
		} else if (ground && wasGround) {
			double step = p.getAttributeValue(Attributes.STEP_HEIGHT);
			if (c.flightEnabled && dy > step + c.stepSlack) {
				st.moveDebug = String.format("stepped up %.2f blocks (max %.2f)", dy, step + c.stepSlack);
				return Violation.VERTICAL;
			}
			valid = false;
			air = 0;
			st.flyStrikes = 0;
		} else {
			if (wasGround || !valid) { // takeoff, or a fresh session after an exemption
				take = preY;
				air = 0;
				valid = true;
			}
			air++;
			if (c.flightEnabled) {
				double bound = airBound(p, air, c);
				double rise = ny - take;
				if (rise > bound) {
					// a single noisy packet while jumping is ignored; sustained excess is Fly
					if (++st.flyStrikes >= c.flyStrikes) {
						st.flyStrikes = 0;
						st.moveDebug = String.format("rise %.2f > allowed %.2f after %d air packets (ground=%b, wasGround=%b)",
							rise, bound, air, ground, wasGround);
						return Violation.VERTICAL;
					}
				} else {
					st.flyStrikes = 0;
				}
			}
			if (ground) {
				valid = false;
				air = 0;
			}
		}

		// ---- commit ----
		st.tokens = c.movementEnabled ? tokens - hd : tokens;
		st.sessionValid = valid;
		st.takeoffY = take;
		st.airPackets = air;
		st.lastGround = ground;
		st.peakY = (ground || Double.isNaN(st.peakY)) ? ny : Math.max(st.peakY, ny);

		if (ground && isBouncy(p, nx, ny, nz)) {
			st.graceUntilMs = now + 2500; // slime / bed bounces launch the player on their own
		}
		return Violation.NONE;
	}

	private static void passthrough(PlayerState st, boolean ground, double y) {
		st.peakY = (ground || Double.isNaN(st.peakY)) ? y : Math.max(st.peakY, y);
		st.tokens = Double.NaN;
		st.sessionValid = false;
		st.airPackets = 0;
		st.flyStrikes = 0;
		st.lastGround = ground;
	}

	/** Max horizontal blocks per tick the player may legitimately cover, from server-side attributes. */
	private static double limitPerTick(ServerPlayer p, BucketConfig c) {
		if (p.isFallFlying()) return c.elytraMaxPerTick;
		double lim = p.getAttributeValue(Attributes.MOVEMENT_SPEED) * 2.158 * c.speedMultiplier;
		if (p.isUsingItem()) lim *= c.usingItemMultiplier;
		lim += c.speedSlack;
		if (p.isInWater() || p.isInLava()) lim *= c.fluidMultiplier;
		BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
		if (p.level().getBlockState(below).getBlock().getFriction() > 0.7f) lim *= c.slipperyMultiplier;
		return lim;
	}

	/**
	 * Highest y (relative to takeoff) reachable after {@code packets} airborne ticks of a maximal jump.
	 * The bound is the best of a small window of ticks around the current one, so a tick or two of timing
	 * noise cannot trip it.
	 */
	private static double airBound(ServerPlayer p, int packets, BucketConfig c) {
		double g = p.getAttributeValue(Attributes.GRAVITY);
		if (p.hasEffect(MobEffects.SLOW_FALLING)) g = Math.min(g, 0.01);
		double v = p.getAttributeValue(Attributes.JUMP_STRENGTH) + 0.0625;
		MobEffectInstance boost = p.getEffect(MobEffects.JUMP_BOOST);
		if (boost != null) v += 0.1 * (boost.getAmplifier() + 1);

		int n = Math.min(packets, 400);
		int top = n + Math.max(0, c.airExtraTicks);
		double[] h = new double[top + 1];
		double sum = 0;
		for (int i = 1; i <= top; i++) {
			sum += v;
			h[i] = sum;
			v = (v - g) * 0.98;
		}
		double best = Double.NEGATIVE_INFINITY;
		for (int i = Math.max(1, n - 2); i <= top; i++) best = Math.max(best, h[i]);
		return best + c.airSlack;
	}

	/** Situations where vanilla vertical physics do not apply (climbing, fluids, webs, levitation...). */
	private static boolean touchesSpecial(ServerPlayer p) {
		if (p.isInWater() || p.isInLava() || p.onClimbable() || p.hasEffect(MobEffects.LEVITATION)) return true;
		AABB b = p.getBoundingBox().inflate(0.1, 0.0, 0.1);
		BlockPos lo = BlockPos.containing(b.minX, b.minY, b.minZ);
		BlockPos hi = BlockPos.containing(b.maxX, b.maxY, b.maxZ);
		Level level = p.level();
		for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
			BlockState s = level.getBlockState(pos);
			if (s.isAir()) continue;
			if (s.is(Blocks.COBWEB) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.BUBBLE_COLUMN) || s.is(Blocks.HONEY_BLOCK) || s.is(Blocks.SCAFFOLDING)
				|| s.is(BlockTags.CLIMBABLE)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBouncy(ServerPlayer p, double x, double y, double z) {
		BlockState s = p.level().getBlockState(BlockPos.containing(x, y - 0.2, z));
		return s.is(Blocks.SLIME_BLOCK) || s.is(BlockTags.BEDS);
	}
}
