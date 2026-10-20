package dev.bucket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Spear and mace damage guard. Two stages, both driven from LivingEntityMixin:
 *  1. before the hit: clamp raw damage that is impossible (spear over its cap, mace fall distance the server never saw);
 *  2. after armor / protection / absorption: for flagged players, a single hit can never take more than a fraction of
 *     the victim's max health, so a one-shot is impossible no matter how good the victim's armor is.
 */
public final class DamageGuard {
	public static final ThreadLocal<Attack> CURRENT = new ThreadLocal<>();

	public record Attack(float amount, boolean guard) {}

	private DamageGuard() {}

	public static Attack inspect(LivingEntity victim, DamageSource source, float amount, BucketConfig c) {
		if (!(source.getEntity() instanceof ServerPlayer a) || a == victim) return new Attack(amount, false);

		ItemStack weapon = source.getWeaponItem();
		boolean mace = source.is(DamageTypes.MACE_SMASH) || (weapon != null && weapon.is(Items.MACE));
		boolean spear = !mace && weapon != null && weapon.is(ItemTags.SPEARS);
		if (!mace && !spear) return new Attack(amount, false);

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
		return new Attack(out, c.blockOneShot && Detector.enforcing(a));
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

	/** Stage 2: runs on the damage that is about to be applied (after armor and enchantment protection). */
	public static float guardFinal(LivingEntity victim, float after) {
		Attack atk = CURRENT.get();
		BucketConfig c = BucketConfig.get();
		if (atk == null || !atk.guard() || !c.blockOneShot) return after;

		float cap = (float) (victim.getMaxHealth() * c.flaggedMaxHitFraction);
		if (after > cap) {
			if (victim instanceof ServerPlayer) {
				BucketMod.LOGGER.debug("[bucket] one-shot guard: {} -> {}", after, cap);
			}
			return cap;
		}
		return after;
	}
}
