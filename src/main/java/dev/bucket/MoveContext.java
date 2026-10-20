package dev.bucket;

import net.minecraft.server.level.ServerPlayer;

/** Lets the packet mixin know which player's movement packet is being handled right now. */
public final class MoveContext {
	public static final ThreadLocal<ServerPlayer> CURRENT = new ThreadLocal<>();

	private MoveContext() {}
}
