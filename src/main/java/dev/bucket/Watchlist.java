package dev.bucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent lists, saved to config/bucket-players.json: the manual checklist and auto-detected hack clients. */
public final class Watchlist {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Entry {
		public String name;
		public String brand;
		public long at;
		public String reasons;

		public Entry() {}

		public Entry(String name, String brand, long at, String reasons) {
			this.name = name;
			this.brand = brand;
			this.at = at;
			this.reasons = reasons;
		}
	}

	private static final class Data {
		Map<String, Entry> checklist = new LinkedHashMap<>();
		Map<String, Entry> detected = new LinkedHashMap<>();
	}

	private static Data data = new Data();
	private static Path file;

	private Watchlist() {}

	public static synchronized void load(Path dir) {
		file = dir.resolve("bucket-players.json");
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					Data loaded = GSON.fromJson(r, Data.class);
					if (loaded != null) {
						if (loaded.checklist == null) loaded.checklist = new LinkedHashMap<>();
						if (loaded.detected == null) loaded.detected = new LinkedHashMap<>();
						data = loaded;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			BucketMod.LOGGER.error("Could not read {}; starting with empty lists", file, e);
			data = new Data();
		}
	}

	private static void save() {
		if (file == null) return;
		try (Writer w = Files.newBufferedWriter(file)) {
			GSON.toJson(data, w);
		} catch (IOException e) {
			BucketMod.LOGGER.error("Could not write {}", file, e);
		}
	}

	public static synchronized boolean isChecklisted(UUID id) {
		return data.checklist.containsKey(id.toString());
	}

	public static synchronized boolean isDetected(UUID id) {
		return data.detected.containsKey(id.toString());
	}

	/** @return false if the player was already on the checklist */
	public static synchronized boolean addChecklist(UUID id, String name) {
		if (data.checklist.containsKey(id.toString())) return false;
		data.checklist.put(id.toString(), new Entry(name, null, System.currentTimeMillis(), "added manually"));
		save();
		return true;
	}

	public static synchronized void addDetected(UUID id, Entry e) {
		data.detected.put(id.toString(), e);
		save();
	}

	/** Removes the player from both lists. @return the UUIDs that were removed */
	public static synchronized List<UUID> removeByName(String name) {
		List<UUID> removed = new ArrayList<>();
		for (Map<String, Entry> m : List.of(data.checklist, data.detected)) {
			m.entrySet().removeIf(en -> {
				if (en.getValue().name != null && en.getValue().name.equalsIgnoreCase(name)) {
					UUID id = UUID.fromString(en.getKey());
					if (!removed.contains(id)) removed.add(id);
					return true;
				}
				return false;
			});
		}
		if (!removed.isEmpty()) save();
		return removed;
	}

	public static synchronized UUID uuidByName(String name) {
		for (Map<String, Entry> m : List.of(data.checklist, data.detected)) {
			for (Map.Entry<String, Entry> en : m.entrySet()) {
				if (en.getValue().name != null && en.getValue().name.equalsIgnoreCase(name)) return UUID.fromString(en.getKey());
			}
		}
		return null;
	}

	public static synchronized Entry checklistEntry(UUID id) {
		return data.checklist.get(id.toString());
	}

	public static synchronized Entry detectedEntry(UUID id) {
		return data.detected.get(id.toString());
	}

	public static synchronized Map<UUID, Entry> checklist() {
		return copy(data.checklist);
	}

	public static synchronized Map<UUID, Entry> detected() {
		return copy(data.detected);
	}

	private static Map<UUID, Entry> copy(Map<String, Entry> src) {
		Map<UUID, Entry> out = new LinkedHashMap<>();
		src.forEach((k, v) -> out.put(UUID.fromString(k), v));
		return out;
	}
}
