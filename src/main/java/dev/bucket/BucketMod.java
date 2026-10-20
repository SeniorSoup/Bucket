package dev.bucket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class BucketMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("bucket");
	private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

	/** Client brand announced during login, keyed by connection (filled by ServerCommonPacketListenerImplMixin). */
	public static final Map<Connection, String> BRANDS = Collections.synchronizedMap(new WeakHashMap<>());

	@Override
	public void onInitialize() {
		java.nio.file.Path dir = FabricLoader.getInstance().getConfigDir();
		BucketConfig.load(dir);
		Watchlist.load(dir);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			BucketCommand.register(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			PlayerState st = state(p);
			if (Watchlist.isDetected(p.getUUID())) st.flagged = true; // stays flagged across restarts until /anticheat rm
			Detector.announceKnown(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			STATES.remove(handler.getPlayer().getUUID()));

		LOGGER.info("Bucket loaded: checks observe everyone, corrections only hit detected hack clients and checklisted players.");
	}

	public static PlayerState state(ServerPlayer p) {
		return STATES.computeIfAbsent(p.getUUID(), id -> new PlayerState());
	}

	public static PlayerState stateIfPresent(UUID id) {
		return STATES.get(id);
	}

	/** Handlers run twice (network thread, then server thread). World access is only safe on the second run. */
	public static boolean onServerThread(ServerPlayer p) {
		MinecraftServer s = p.level().getServer();
		return s != null && s.isSameThread();
	}

	public static void logCorrection(ServerPlayer p, String what) {
		if (BucketConfig.get().logCorrections) {
			LOGGER.info("[corrected] {}: {}", p.getName().getString(), what);
		}
	}
}
