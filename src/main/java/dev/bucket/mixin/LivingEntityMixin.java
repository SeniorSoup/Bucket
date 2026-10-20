package dev.bucket.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bucket.BucketConfig;
import dev.bucket.DamageGuard;
import dev.bucket.Detector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Melee damage guard, independent of which packet or mechanic caused the hit.
 *  - hurtServer wrapper: reach / line of sight at hit time (cancels the hit), spear + mace damage clamps;
 *  - getDamageAfterMagicAbsorb: runs on the damage after armor and protection enchantments, where a checked
 *    attacker's hit is capped so it can never one-shot anyone, whatever armor they wear.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	@WrapMethod(method = "hurtServer")
	private boolean bucket$hurt(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
		BucketConfig c = BucketConfig.get();
		if (!c.damageEnabled || !(source.getEntity() instanceof ServerPlayer a) || !Detector.active(a)) {
			return original.call(level, source, amount);
		}
		DamageGuard.Attack attack = DamageGuard.inspect((LivingEntity) (Object) this, source, amount, c);
		if (attack.cancel()) return false; // impossible hit: nothing happens, no knockback either

		DamageGuard.Attack previous = DamageGuard.CURRENT.get();
		DamageGuard.CURRENT.set(attack);
		try {
			return original.call(level, source, attack.amount());
		} finally {
			if (previous == null) DamageGuard.CURRENT.remove();
			else DamageGuard.CURRENT.set(previous);
		}
	}

	@ModifyReturnValue(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"))
	private float bucket$oneShotGuard(float after) {
		return DamageGuard.guardFinal((LivingEntity) (Object) this, after);
	}
}
