package dev.bucket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Stateless-ish validators for interactions. Returning a category means: this looks like that cheat. Whether to drop the packet is up to {@link Detector}. */
public final class ActionChecks {
	private ActionChecks() {}

	// ------------------------------------------------------------ entities (KillAura, Reach, Multi-aura)
	/** @return null if allowed, otherwise the category of cheat this looks like */
	public static Detector.Cat entityViolation(ServerPlayer p, Entity target, PlayerState st, int serverTick, BucketConfig c) {
		if (!c.combatEnabled) return null;
		st.rollTick(serverTick);

		Vec3 eye = p.getEyePosition();
		AABB box = target.getBoundingBox();
		double reach = p.entityInteractionRange() + c.entityReachTolerance;
		if (box.distanceToSqr(eye) > reach * reach) return Detector.Cat.REACH;

		if (c.entityLineOfSight && !entityVisible(p, eye, box)) return Detector.Cat.KILLAURA;
		if (c.noInteractWhileUsingItem && p.isUsingItem()) return Detector.Cat.KILLAURA;

		if (c.oneTargetPerTick && st.attackTargetId != -1 && st.attackTargetId != target.getId()) return Detector.Cat.KILLAURA;
		if (++st.attacksThisTick > c.maxAttacksPerTick) return Detector.Cat.KILLAURA;
		st.attackTargetId = target.getId();
		return null;
	}

	private static boolean entityVisible(ServerPlayer p, Vec3 eye, AABB box) {
		Vec3 center = box.getCenter();
		Vec3 closest = new Vec3(
			Math.max(box.minX, Math.min(eye.x, box.maxX)),
			Math.max(box.minY, Math.min(eye.y, box.maxY)),
			Math.max(box.minZ, Math.min(eye.z, box.maxZ)));
		Vec3 head = new Vec3(center.x, box.minY + (box.maxY - box.minY) * 0.9, center.z);
		return clear(p, eye, towards(closest, eye, 0.03)) || clear(p, eye, center) || clear(p, eye, head);
	}

	// ------------------------------------------------------------ blocks (Nuker, Scaffold, AirPlace, ChestAura)
	public static Detector.Cat breakViolation(ServerPlayer p, BlockPos pos, PlayerState st, int serverTick, BucketConfig c) {
		if (!c.worldEnabled) return null;
		st.rollTick(serverTick);

		Vec3 eye = p.getEyePosition();
		double reach = p.blockInteractionRange() + c.blockReachTolerance;
		if (new AABB(pos).distanceToSqr(eye) > reach * reach) return Detector.Cat.NUKER;

		if (c.blockLineOfSight && !blockVisible(p, eye, pos)) return Detector.Cat.NUKER;

		st.breaksThisTick.add(pos.asLong());
		return st.breaksThisTick.size() <= c.maxBreaksPerTick ? null : Detector.Cat.NUKER;
	}

	public static Detector.Cat placeViolation(ServerPlayer p, BlockHitResult hit, PlayerState st, int serverTick, BucketConfig c) {
		if (!c.worldEnabled) return null;
		st.rollTick(serverTick);

		Level level = p.level();
		BlockPos pos = hit.getBlockPos();
		Vec3 loc = hit.getLocation();
		Vec3 eye = p.getEyePosition();

		if (c.blockAirPlace && level.getBlockState(pos).isAir()) return Detector.Cat.SCAFFOLD;

		double reach = p.blockInteractionRange() + c.blockReachTolerance;
		if (eye.distanceToSqr(loc) > reach * reach) return Detector.Cat.REACH;

		if (c.blockLineOfSight) {
			BlockHitResult r = level.clip(new ClipContext(eye, towards(loc, eye, 0.02),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
			if (r.getType() == HitResult.Type.BLOCK && !r.getBlockPos().equals(pos)) return Detector.Cat.SCAFFOLD;
		}

		if (c.placeMaxAngleDeg > 0) {
			Vec3 to = loc.subtract(eye);
			double len = to.length();
			if (len > 0.5) {
				double cos = p.getLookAngle().dot(to) / len;
				if (cos < Math.cos(Math.toRadians(c.placeMaxAngleDeg))) return Detector.Cat.SCAFFOLD;
			}
		}

		return ++st.placesThisTick <= c.maxPlacesPerTick ? null : Detector.Cat.SCAFFOLD;
	}

	// ------------------------------------------------------------ inventory (ChestStealer, InvManager, AutoArmor)
	public static Detector.Cat containerViolation(PlayerState st, int serverTick, BucketConfig c) {
		if (!c.inventoryEnabled) return null;
		st.rollTick(serverTick);
		return ++st.clicksThisTick <= c.maxContainerClicksPerTick ? null : Detector.Cat.INVENTORY;
	}

	// ------------------------------------------------------------ helpers
	private static boolean blockVisible(ServerPlayer p, Vec3 eye, BlockPos pos) {
		Vec3 c = Vec3.atCenter(pos);
		double f = 0.49, k = 0.45;
		Vec3[] samples = {
			c,
			c.add(f, 0, 0), c.add(-f, 0, 0), c.add(0, f, 0), c.add(0, -f, 0), c.add(0, 0, f), c.add(0, 0, -f),
			c.add(k, k, k), c.add(k, k, -k), c.add(k, -k, k), c.add(k, -k, -k),
			c.add(-k, k, k), c.add(-k, k, -k), c.add(-k, -k, k), c.add(-k, -k, -k)
		};
		for (Vec3 s : samples) {
			BlockHitResult r = p.level().clip(new ClipContext(eye, s,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
			if (r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(pos)) return true;
		}
		return false;
	}

	/** True if nothing solid (by outline, like the client's targeting) lies between eye and point. */
	private static boolean clear(ServerPlayer p, Vec3 eye, Vec3 point) {
		BlockHitResult r = p.level().clip(new ClipContext(eye, point,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return r.getType() == HitResult.Type.MISS;
	}

	/** Moves {@code point} a small distance towards {@code target} so rays don't end inside touching blocks. */
	private static Vec3 towards(Vec3 point, Vec3 target, double dist) {
		Vec3 d = target.subtract(point);
		double len = d.length();
		return len < 1.0E-6 ? point : point.add(d.scale(dist / len));
	}
}
