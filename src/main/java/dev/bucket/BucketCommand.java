package dev.bucket;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /anticheat &lt;player&gt;            add to the checklist (same as add)
 * /anticheat add &lt;player&gt;
 * /anticheat rm &lt;name&gt;            remove from the checklist and clear a hack-client flag
 * /anticheat list clients          detected hack clients
 * /anticheat list players          the checklist
 * /anticheat list &lt;player&gt;        stats for one player
 */
public final class BucketCommand {
	private static final SuggestionProvider<CommandSourceStack> KNOWN =
		(ctx, b) -> SharedSuggestionProvider.suggest(knownNames(ctx.getSource().getServer()), b);

	private BucketCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("anticheat")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.argument("player", EntityArgument.player())
				.executes(ctx -> add(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))
			.then(Commands.literal("add")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> add(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
			.then(Commands.literal("rm")
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests(KNOWN)
					.executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
			.then(Commands.literal("enable").executes(ctx -> setEnabled(ctx.getSource(), true)))
			.then(Commands.literal("disable").executes(ctx -> setEnabled(ctx.getSource(), false)))
			.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
			.then(Commands.literal("list")
				.then(Commands.literal("clients").executes(ctx -> listClients(ctx.getSource())))
				.then(Commands.literal("players").executes(ctx -> listPlayers(ctx.getSource())))
				.then(Commands.argument("player", StringArgumentType.word())
					.suggests(KNOWN)
					.executes(ctx -> stats(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))));
	}

	// ------------------------------------------------------------ add / rm
	private static int add(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
		String name = target.getName().getString();
		if (!Watchlist.addChecklist(target.getUUID(), name)) {
			src.sendFailure(Component.literal(name + " is already on the anticheat checklist."));
			return 0;
		}
		BucketMod.state(target).listed = true;
		send(src, name + " added to the anticheat checklist. Every check is now enforced on them.", true);
		return 1;
	}

	private static int remove(CommandSourceStack src, String name) {
		List<UUID> removed = Watchlist.removeByName(name);
		MinecraftServer server = src.getServer();

		// also clear a runtime flag for anyone online with that name
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			BucketMod.state(online).clearDetection();
			if (!removed.contains(online.getUUID())) removed.add(online.getUUID());
		}
		for (UUID id : removed) {
			PlayerState st = BucketMod.stateIfPresent(id);
			if (st != null) {
				st.clearDetection();
				st.listed = false;
			}
		}
		if (online != null) BucketMod.state(online).listed = false;

		if (removed.isEmpty()) {
			src.sendFailure(Component.literal(name + " is not on the checklist or the detected list."));
			return 0;
		}
		send(src, name + " removed from the anticheat lists and their flag was cleared.", true);
		return 1;
	}

	// ------------------------------------------------------------ enable / disable / status
	private static int setEnabled(CommandSourceStack src, boolean on) {
		BucketConfig c = BucketConfig.get();
		if (c.enabled == on) {
			src.sendFailure(Component.literal("Bucket is already " + (on ? "enabled." : "disabled.")));
			return 0;
		}
		c.enabled = on;
		BucketConfig.save();
		send(src, "Bucket " + (on ? "enabled" : "disabled") + (on ? "." : ". No checks run and nobody is corrected until you /anticheat enable."), true);
		return 1;
	}

	private static int status(CommandSourceStack src) {
		BucketConfig c = BucketConfig.get();
		send(src, "Bucket is " + (c.enabled ? "ENABLED" : "DISABLED")
			+ " | scope: " + (c.listOnly ? "checklist only" : "everyone observed, flagged + listed corrected")
			+ " | checklist: " + Watchlist.checklist().size()
			+ " | detected hack clients: " + Watchlist.detected().size(), false);
		return 1;
	}

	// ------------------------------------------------------------ list
	private static int listClients(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		Map<UUID, Watchlist.Entry> detected = Watchlist.detected();

		// players flagged this session but (for some reason) not saved yet
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			PlayerState st = BucketMod.stateIfPresent(p.getUUID());
			if (st != null && st.flagged && !detected.containsKey(p.getUUID())) {
				detected.put(p.getUUID(), new Watchlist.Entry(p.getName().getString(), st.brand, st.flaggedAtMs,
					Detector.reasons(Detector.snapshot(st))));
			}
		}
		if (detected.isEmpty()) {
			send(src, "No hack clients detected.", false);
			return 0;
		}
		List<String> lines = new ArrayList<>();
		lines.add("Detected hack clients (" + detected.size() + "):");
		for (Map.Entry<UUID, Watchlist.Entry> en : detected.entrySet()) {
			Watchlist.Entry e = en.getValue();
			boolean online = server.getPlayerList().getPlayer(en.getKey()) != null;
			lines.add(" - " + e.name + (online ? " [online]" : " [offline]")
				+ "  client: " + (e.brand == null ? "unknown" : e.brand)
				+ "  detected " + ago(e.at) + "  " + e.reasons);
		}
		send(src, String.join("\n", lines), false);
		return detected.size();
	}

	private static int listPlayers(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		Map<UUID, Watchlist.Entry> list = Watchlist.checklist();
		if (list.isEmpty()) {
			send(src, "The anticheat checklist is empty. Use /anticheat add <player>.", false);
			return 0;
		}
		List<String> lines = new ArrayList<>();
		lines.add("Anticheat checklist (" + list.size() + "):");
		for (Map.Entry<UUID, Watchlist.Entry> en : list.entrySet()) {
			boolean online = server.getPlayerList().getPlayer(en.getKey()) != null;
			lines.add(" - " + en.getValue().name + (online ? " [online]" : " [offline]") + "  added " + ago(en.getValue().at));
		}
		send(src, String.join("\n", lines), false);
		return list.size();
	}

	private static int stats(CommandSourceStack src, String name) {
		MinecraftServer server = src.getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		UUID id = online != null ? online.getUUID() : Watchlist.uuidByName(name);
		if (id == null) {
			src.sendFailure(Component.literal("No online player or listed entry named " + name + "."));
			return 0;
		}
		BucketConfig c = BucketConfig.get();
		PlayerState st = BucketMod.stateIfPresent(id);
		Watchlist.Entry det = Watchlist.detectedEntry(id);
		boolean listed = Watchlist.isChecklisted(id);
		boolean flagged = (st != null && st.flagged) || det != null;
		String shown = online != null ? online.getName().getString() : (det != null ? det.name : Watchlist.checklistEntry(id).name);

		List<String> lines = new ArrayList<>();
		lines.add("Bucket stats for " + shown + (online != null ? " [online]" : " [offline]"));
		lines.add(" Status: " + (flagged ? "HACK CLIENT DETECTED" : "clean") + (listed ? " | on checklist" : "")
			+ (c.enforceAll ? " | enforcing everyone" : ""));
		String brand = st != null && st.brand != null ? st.brand : (det != null && det.brand != null ? det.brand : "unknown");
		lines.add(" Client brand: " + brand + " (not used for decisions; spoofed clients are checked the same)");
		lines.add(" Checks: " + (!c.enabled ? "OFF (Bucket disabled)" : (listed || !c.listOnly ? "running on this player" : "not running (not on the checklist)")));
		if (st != null) {
			double score;
			synchronized (st) {
				score = st.score;
			}
			lines.add(String.format(" Score: %.1f / %.1f   corrections applied: %d", score, c.flagThreshold, st.corrections));
			lines.add(" Violations: " + Detector.reasons(Detector.snapshot(st)));
			if (st.lastViolationMs != 0) lines.add(" Last: " + st.lastDetail + " (" + ago(st.lastViolationMs) + ")");
		} else if (det != null) {
			lines.add(" Violations (saved): " + det.reasons);
		}
		send(src, String.join("\n", lines), false);
		return 1;
	}

	// ------------------------------------------------------------ helpers
	private static Set<String> knownNames(MinecraftServer server) {
		Set<String> names = new LinkedHashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) names.add(p.getName().getString());
		for (Watchlist.Entry e : Watchlist.checklist().values()) if (e.name != null) names.add(e.name);
		for (Watchlist.Entry e : Watchlist.detected().values()) if (e.name != null) names.add(e.name);
		return names;
	}

	private static void send(CommandSourceStack src, String msg, boolean broadcastToOps) {
		src.sendSuccess(() -> Component.literal(msg), broadcastToOps);
	}

	private static String ago(long ms) {
		long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
		if (s < 60) return s + "s ago";
		if (s < 3600) return (s / 60) + "m ago";
		if (s < 86400) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}
}
