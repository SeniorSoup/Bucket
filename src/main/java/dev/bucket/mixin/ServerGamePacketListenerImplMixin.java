package dev.bucket.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bucket.ActionChecks;
import dev.bucket.BucketConfig;
import dev.bucket.BucketMod;
import dev.bucket.Detector;
import dev.bucket.MoveContext;
import dev.bucket.MovementChecks;
import dev.bucket.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every handler is wrapped. Handlers run twice (network thread, then server thread); the first run just
 * forwards so vanilla can reschedule it, and all checks happen on the server-thread run.
 *
 * Every check reports to {@link Detector}. It only says "correct this" for players that were detected as a hacked
 * client (or are on the checklist); for everybody else the action goes through untouched and just feeds their score.
 * Corrections are silent: the packet is dropped and the client is resynced. Nobody is kicked or banned.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

	// ------------------------------------------------------------------ movement
	@WrapMethod(method = "handleMovePlayer")
	private void bucket$move(ServerboundMovePlayerPacket packet, Operation<Void> original) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ServerPlayer p = self.getPlayer();
		BucketConfig c = BucketConfig.get();
		if (!c.enabled || !BucketMod.onServerThread(p)) {
			original.call(packet);
			return;
		}

		PlayerState st = BucketMod.state(p);
		long now = System.currentTimeMillis();

		// The server moved the player (teleport, piston, respawn, our own setback...): re-baseline.
		if (!st.hasLast || sq(p.getX() - st.lastX, p.getY() - st.lastY, p.getZ() - st.lastZ) > 1.0E-6) {
			st.reset(p, now, c.graceMs);
		}

		long elapsed = Math.max(0L, now - st.lastPacketMs);

		// Timer: the client may not send movement faster than 20 packets/s over time.
		if (c.timerEnabled && !p.isSpectator()) {
			double next = st.timerCredit + elapsed - 50.0;
			if (next < -c.timerDebtMs) {
				if (Detector.violation(p, Detector.Cat.TIMER, "movement packets faster than 20/s")) {
					st.timerCredit += elapsed; // dropped packet consumes nothing
					st.lastPacketMs = now;
					return;
				}
				next = -c.timerDebtMs; // observed only: let it through
			}
			st.timerCredit = Math.min(next, c.timerMaxCreditMs);
		}
		st.lastPacketMs = now;

		double preX = p.getX(), preY = p.getY(), preZ = p.getZ();

		MoveContext.CURRENT.set(p);
		try {
			original.call(packet);
		} finally {
			MoveContext.CURRENT.remove();
		}

		double nx = p.getX(), ny = p.getY(), nz = p.getZ();
		if (nx == preX && ny == preY && nz == preZ) return; // rotation-only, or vanilla already rejected it

		MovementChecks.Violation v = MovementChecks.check(p, st, preX, preY, preZ, now, elapsed, c);
		boolean accept = v == MovementChecks.Violation.NONE;
		if (!accept) {
			Detector.Cat cat = v == MovementChecks.Violation.SPEED ? Detector.Cat.SPEED : Detector.Cat.FLY;
			String detail = String.format("horizontal %.2f, vertical %.2f blocks in one packet",
				Math.hypot(nx - preX, nz - preZ), ny - preY);
			if (Detector.violation(p, cat, detail)) {
				self.teleport(preX, preY, preZ, p.getYRot(), p.getXRot());
				return;
			}
			accept = true; // observed only
		}
		if (accept) {
			st.lastX = nx;
			st.lastY = ny;
			st.lastZ = nz;
		}
	}

	// ------------------------------------------------------------------ attacking / entity interaction
	@WrapMethod(method = "handleInteract")
	private void bucket$interact(ServerboundInteractPacket packet, Operation<Void> original) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ServerPlayer p = self.getPlayer();
		BucketConfig c = BucketConfig.get();
		if (!c.enabled || !BucketMod.onServerThread(p)) {
			original.call(packet);
			return;
		}

		Entity target = packet.getTarget((ServerLevel) p.level());
		if (target != null && !p.isSpectator()) {
			Detector.Cat v = ActionChecks.entityViolation(p, target, BucketMod.state(p), tick(p), c);
			if (v != null && Detector.violation(p, v, "entity interaction")) {
				return;
			}
		}
		original.call(packet);
	}

	// ------------------------------------------------------------------ breaking blocks
	@WrapMethod(method = "handlePlayerAction")
	private void bucket$action(ServerboundPlayerActionPacket packet, Operation<Void> original) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ServerPlayer p = self.getPlayer();
		BucketConfig c = BucketConfig.get();
		if (!c.enabled || !BucketMod.onServerThread(p)) {
			original.call(packet);
			return;
		}

		ServerboundPlayerActionPacket.Action a = packet.getAction();
		if ((a == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
			|| a == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) && !p.isSpectator()) {
			BlockPos pos = packet.getPos();
			Detector.Cat v = ActionChecks.breakViolation(p, pos, BucketMod.state(p), tick(p), c);
			if (v != null && Detector.violation(p, v, "block break at " + pos.toShortString())) {
				self.ackBlockChangesUpTo(packet.getSequence());
				self.send(new ClientboundBlockUpdatePacket(pos, p.level().getBlockState(pos)));
				return;
			}
		}
		original.call(packet);
	}

	// ------------------------------------------------------------------ placing / using blocks
	@WrapMethod(method = "handleUseItemOn")
	private void bucket$useOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ServerPlayer p = self.getPlayer();
		BucketConfig c = BucketConfig.get();
		if (!c.enabled || !BucketMod.onServerThread(p)) {
			original.call(packet);
			return;
		}

		BlockHitResult hit = packet.getHitResult();
		if (!p.isSpectator()) {
			Detector.Cat v = ActionChecks.placeViolation(p, hit, BucketMod.state(p), tick(p), c);
			if (v != null && Detector.violation(p, v, "block use/place at " + hit.getBlockPos().toShortString())) {
				self.ackBlockChangesUpTo(packet.getSequence());
				BlockPos clicked = hit.getBlockPos();
				BlockPos placed = clicked.relative(hit.getDirection());
				self.send(new ClientboundBlockUpdatePacket(clicked, p.level().getBlockState(clicked)));
				self.send(new ClientboundBlockUpdatePacket(placed, p.level().getBlockState(placed)));
				p.containerMenu.sendAllDataToRemote();
				return;
			}
		}
		original.call(packet);
	}

	// ------------------------------------------------------------------ inventory
	@WrapMethod(method = "handleContainerClick")
	private void bucket$click(ServerboundContainerClickPacket packet, Operation<Void> original) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ServerPlayer p = self.getPlayer();
		BucketConfig c = BucketConfig.get();
		if (!c.enabled || !BucketMod.onServerThread(p)) {
			original.call(packet);
			return;
		}

		Detector.Cat v = ActionChecks.containerViolation(BucketMod.state(p), tick(p), c);
		if (v != null && Detector.violation(p, v, "too many container clicks in one tick")) {
			p.containerMenu.sendAllDataToRemote();
			return;
		}
		original.call(packet);
	}

	// ------------------------------------------------------------------ helpers
	private static int tick(ServerPlayer p) {
		return p.level().getServer().getTickCount();
	}

	private static double sq(double x, double y, double z) {
		return x * x + y * y + z * z;
	}
}
