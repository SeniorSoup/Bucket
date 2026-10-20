package dev.bucket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Guards every melee hit a checked player deals, whatever packet or mechanic produced it (normal attack, spear jab,
 * lunge, charge). Driven from LivingEntityMixin:
 *  1. reach / line of sight at the moment of the hit (cancels the hit);
 *  2. spear: impossible raw damage is clamped; mace: fall distance the server never saw is scaled away;
 *  3. after armor / protection: a hit can never take more than a fraction of the victim's max health (no one-shots).
 */
public final class DamageGuard {
	public static final ThreadLocal<Attack> CURRENT = new ThreadLocal<>();

	public record Attack(float amount, boolean guard, boolean cancel) {}

	private DamageGuard() {}

	public static Attack inspect(LivingEntity victim, DamageSource source, float amount, BucketConfig c) {
		if (!(source.getEntity() instanceof ServerPlayer a) || a == victim) return new Attack(amount, false, false);
		ItemStack weapon = source.getWeaponItem();

		// ---- 1. reach / line of sight at the moment of the hit ----
		if (c.combatEnabled && c.hitReachCheck && source.getDirectEntity() == a && badHit(a, victim, weapon, c)) {
			return new Attack(amount, false, true);
		}

		// ---- 2. spear / mace ----
		boolean mace = source.is(DamageTypes.MACE_SMASH) || (weapon != null && weapon.is(Items.MACE));
		boolean spear = !mace && weapon != null && weapon.is(ItemTags.SPEARS);
		if (!mace && !spear) return new Attack(amount, false, false);

		float out = amount;
		if (spear) {
			double lim = a.isPassenger() ? c.spearMountedMaxRawDamage : c.spearMaxRawDamage;
			if (amount > lim) {
				Detector.violation(a, Detector.Cat.SPEAR, Detector.Cat.SPEAR.weight,
					String.format("spear hit %.1f > cap %.1f", amount, lim), true);
				out = (float) lim;
			}
		} else {
			out = maceDamage(a, amount, c);
		}
		return new Attack(out, c.blockOneShot && Detector.enforcing(a), false);
	}

	private static boolean badHit(ServerPlayer a, LivingEntity victim, ItemStack weapon, BucketConfig c) {
		boolean sword = weapon != null && weapon.is(ItemTags.SWORDS); // sweep hits reach secondary targets
		double reach = ActionChecks.attackReach(a, weapon, c) + c.entityReachTolerance + (sword ? c.sweepExtraReach : 0.0);
		AABB box = victim.getBoundingBox();
		Vec3 eye = a.getEyePosition();
		double d2 = box.distanceToSqr(eye);
		if (d2 > reach * reach) {
			Detector.violation(a, Detector.Cat.REACH, Detector.Cat.REACH.weight,
				String.format("hit from %.2f blocks, max %.2f", Math.sqrt(d2), reach), true);
			return true;
		}
		if (c.entityLineOfSight && !sword && !ActionChecks.canSeeEntity(a, box)) {
			Detector.violation(a, Detector.Cat.KILLAURA, Detector.Cat.KILLAURA.weight, "hit through a wall", true);
			return true;
		}
		return false;
	}

	/**
	 * Smash damage grows with fall distance. The server's own fall distance can be inflated by a hacked client, so it is
	 * compared with the height the movement checks actually saw the player fall from. Any bonus beyond that is scaled away.
	 */
	private static float maceDamage(ServerPlayer a, float amount, BucketConfig c) {
		PlayerState st = BucketMod.state(a);
		double claimed = a.fallDistance;
		double legit = Double.isNaN(st.peakY) ? claimed : Math.max(0.0, st.peakY - a.getY());
		double allowedFall = legit + c.maceFallSlack;
		double fall = Math.min(claimed, allowedFall);

		if (claimed > allowedFall && claimed > 1.5) {
			Detector.violation(a, Detector.Cat.MACE, Detector.Cat.MACE.weight,
				String.format("fall distance %.1f but only %.1f was tracked", claimed, legit), true);
		}
		if (Detector.enforcing(a)) fall = Math.min(fall, c.maceMaxFallBlocks);

		if (claimed > 1.5 && fall < claimed - 0.01) {
			double base = a.getAttributeValue(Attributes.ATTACK_DAMAGE);
			double bonus = Math.max(0.0, amount - base); // smash bonus + density scale with fall distance
			return (float) (base + bonus * Math.max(0.0, fall) / claimed);
		}
		return amount;
	}

	/** Stage 3: runs on the damage that is about to be applied (after armor and enchantment protection). */
	public static float guardFinal(LivingEntity victim, float after) {
		Attack atk = CURRENT.get();
		BucketConfig c = BucketConfig.get();
		if (atk == null || !atk.guard() || !c.blockOneShot) return after;
		float cap = (float) (victim.getMaxHealth() * c.flaggedMaxHitFraction);
		return Math.min(after, cap);
	}
}
