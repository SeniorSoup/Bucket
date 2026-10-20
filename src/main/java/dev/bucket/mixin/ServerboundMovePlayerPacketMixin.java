package dev.bucket.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bucket.MoveContext;
import dev.bucket.MovementChecks;
import dev.bucket.BucketConfig;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces the client's onGround claim with a collision test at the packet's position, only while the
 * server is handling that player's movement packet. Vanilla then derives fall damage, crits and fall
 * distance from the real value, which defeats NoFall, packet-Criticals and ground-spoof flight.
 */
@Mixin(ServerboundMovePlayerPacket.class)
public abstract class ServerboundMovePlayerPacketMixin {

	@ModifyReturnValue(method = "isOnGround", at = @At("RETURN"))
	private boolean bucket$serverGround(boolean claimed) {
		ServerPlayer p = MoveContext.CURRENT.get();
		BucketConfig c = BucketConfig.get();
		if (p == null || !c.enabled || !c.authoritativeOnGround) return claimed;
		if (p.isSpectator() || p.isPassenger() || p.isFallFlying() || p.getAbilities().flying) return claimed;

		ServerboundMovePlayerPacket self = (ServerboundMovePlayerPacket) (Object) this;
		return MovementChecks.groundAt(p, self.getX(p.getX()), self.getY(p.getY()), self.getZ(p.getZ()));
	}
}
