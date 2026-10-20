package dev.bucket;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central place every check reports to. A violation never punishes by itself: it adds to the player's score,
 * and the caller is told whether it should actually correct the player (only flagged / checklisted players).
 */
public final class Detector {

	public enum Cat {
		SPEED("Speed", 1), FLY("Fly", 2), TIMER("Timer", 1),
		REACH("Reach", 3), KILLAURA("KillAura", 2), NUKER("Nuker", 2), SCAFFOLD("Scaffold", 2),
		INVENTORY("Inventory", 1), SPEAR("Spear", 4), MACE("Mace", 4), CLIENT_BRAND("ClientBrand", 100);

		public final String label;
		public final double weight;

		Cat(String label, double weight) {
			this.label = label;
			this.weight = weight;
		}
	}

	private Detector() {}

	/**
	 * Is this player checked at all? With listOnly (default) that is exactly the /anticheat checklist, so everybody
	 * else is never touched. The client brand is irrelevant here, so a spoofed client is handled like any other.
	 */
	public static boolean active(ServerPlayer p) {
		BucketConfig c = BucketConfig.get();
		if (!c.enabled) return false;
		return !c.listOnly || BucketMod.state(p).listed;
	}

	/** Should corrections be applied to this player right now? */
	public static boolean enforcing(ServerPlayer p) {
		BucketConfig c = BucketConfig.get();
		PlayerState st = BucketMod.state(p);
		if (st.listed) return true;
		if (c.listOnly) return false;
		return c.enforceAll || st.flagged;
	}

	public static boolean violation(ServerPlayer p, Cat cat, String detail) {
		return violation(p, cat, cat.weight, detail, false);
	}

	/**
	 * @param severe the value is outright impossible in vanilla, so the caller corrects it even if the player is not flagged yet
	 * @return true if the caller must correct (drop / set back / clamp) this action
	 */
	public static boolean violation(ServerPlayer p, Cat cat, double weight, String detail, boolean severe) {
		BucketConfig c = BucketConfig.get();
		PlayerState st = BucketMod.state(p);
		long now = System.currentTimeMillis();
		boolean newlyFlagged = false;

		synchronized (st) {
			if (st.scoreMs != 0) {
				st.score = Math.max(0, st.score - (now - st.scoreMs) / 1000.0 * c.scoreDecayPerSec);
			}
			st.scoreMs = now;
			st.score += weight;
			st.counts.merge(cat, 1, Integer::sum);
			st.lastDetail = cat.label + ": " + detail;
			st.lastViolationMs = now;
			if (!st.flagged && st.score >= c.flagThreshold) {
				st.flagged = true;
				st.flaggedAtMs = now;
				newlyFlagged = true;
			}
		}
		if (newlyFlagged) onFlagged(p, st);

		boolean enforce = severe || enforcing(p);
		if (enforce) {
			st.corrections++;
			BucketMod.logCorrection(p, cat.label + " corrected (" + detail + ")");
		}
		return enforce;
	}

	// ------------------------------------------------------------ reporting
	private static void onFlagged(ServerPlayer p, PlayerState st) {
		BucketConfig c = BucketConfig.get();
		String name = p.getName().getString();
		String brand = st.brand == null ? "unknown" : st.brand;
		String reasons = reasons(snapshot(st));

		Watchlist.addDetected(p.getUUID(), new Watchlist.Entry(name, st.brand, System.currentTimeMillis(), reasons));

		if (c.reportToConsole) {
			BucketMod.LOGGER.warn("HACKCLIENT DETECTED: {} (client: {}) [{}]", name, brand, reasons);
		}
		if (c.reportToStaff) {
			MutableComponent msg = prefix()
				.append(Component.literal("HACKCLIENT DETECTED ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
				.append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(" (client: " + brand + ") " + reasons).withStyle(ChatFormatting.GRAY));
			broadcastStaff(p.level().getServer(), msg);
		}
	}

	/** Called when a previously detected / checklisted player joins. */
	public static void announceKnown(ServerPlayer p) {
		BucketConfig c = BucketConfig.get();
		boolean detected = Watchlist.isDetected(p.getUUID());
		boolean listed = Watchlist.isChecklisted(p.getUUID());
		if (!detected && !listed) return;
		if (c.reportToStaff) {
			MutableComponent msg = prefix()
				.append(Component.literal(detected ? "Known hack client joined: " : "Checklisted player joined: ").withStyle(ChatFormatting.RED))
				.append(Component.literal(p.getName().getString()).withStyle(ChatFormatting.YELLOW));
			broadcastStaff(p.level().getServer(), msg);
		}
	}

	private static MutableComponent prefix() {
		return Component.literal("[Bucket] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	public static boolean isStaff(ServerPlayer p) {
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(p.createCommandSourceStack());
	}

	private static void broadcastStaff(MinecraftServer server, Component msg) {
		if (server == null) return;
		// may be called from the network thread (client brand), so hop to the server thread
		server.execute(() -> {
			for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
				if (isStaff(sp)) sp.sendSystemMessage(msg);
			}
		});
	}

	// ------------------------------------------------------------ client brand
	/** Looks up the brand the client announced and flags known cheat clients. Retries on a few sends until it has arrived. */
	public static void resolveBrand(ServerPlayer p, PlayerState st, Connection conn) {
		String b = BucketMod.BRANDS.get(conn);
		if (b == null) {
			if (++st.brandTries > 600) st.brandResolved = true;
			return;
		}
		st.brand = b;
		st.brandResolved = true;

		BucketConfig c = BucketConfig.get();
		if (!c.enabled) return;
		String low = b.toLowerCase(Locale.ROOT);
		for (String s : c.blockedBrands) {
			if (s != null && !s.isBlank() && low.contains(s.toLowerCase(Locale.ROOT))) {
				if (active(p)) {
					violation(p, Cat.CLIENT_BRAND, Cat.CLIENT_BRAND.weight, "client brand \"" + b + "\"", false);
				} else if (c.reportToStaff) {
					String name = p.getName().getString();
					broadcastStaff(p.level().getServer(), prefix()
						.append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
						.append(Component.literal(" joined with a known cheat-client brand \"" + b + "\". Not checked: /anticheat add " + name)
							.withStyle(ChatFormatting.GRAY)));
				}
				break;
			}
		}
	}

	// ------------------------------------------------------------ helpers for commands
	public static Map<Cat, Integer> snapshot(PlayerState st) {
		synchronized (st) {
			return new EnumMap<>(st.counts);
		}
	}

	public static String reasons(Map<Cat, Integer> counts) {
		if (counts.isEmpty()) return "no violations recorded";
		return counts.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
			.limit(6)
			.map(e -> e.getKey().label + " x" + e.getValue())
			.collect(Collectors.joining(", "));
	}
}
