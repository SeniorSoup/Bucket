package dev.bucket.mixin;

import dev.bucket.BucketConfig;
import dev.bucket.BucketMod;
import dev.bucket.Detector;
import dev.bucket.PlayerState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. When the server itself pushes a player (knockback, explosions, wind charges) the resulting movement is
 *    legitimate, so the movement checks step aside for a short window.
 * 2. Remembers the client brand ("vanilla", "fabric", "LiquidBounce"...) so known cheat clients can be flagged.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

	@Shadow
	@Final
	protected Connection connection;

	@Inject(method = "handleCustomPayload", at = @At("HEAD"))
	private void bucket$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof BrandPayload brand) {
			BucketMod.BRANDS.put(this.connection, brand.brand());
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void bucket$noteServerPush(Packet<?> packet, CallbackInfo ci) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) return;
		BucketConfig c = BucketConfig.get();
		ServerPlayer p = listener.getPlayer();
		long now = System.currentTimeMillis();

		PlayerState own = BucketMod.state(p);
		if (!own.brandResolved) Detector.resolveBrand(p, own, this.connection);

		if (packet instanceof ClientboundSetEntityMotionPacket motion) {
			if (motion.getId() == p.getId()) {
				PlayerState st = BucketMod.state(p);
				st.vertGraceUntilMs = Math.max(st.vertGraceUntilMs, now + c.velocityGraceMs);
			}
		} else if (packet instanceof ClientboundExplodePacket) {
			PlayerState st = BucketMod.state(p);
			st.graceUntilMs = Math.max(st.graceUntilMs, now + c.explosionGraceMs);
		}
	}
}
