package com.wtfi.client.memory;

import com.wtfi.Wtfi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;

/**
 * Remembers every item id the player has seen in the library, across all worlds. Items added by
 * a newly installed mod or a game update show up as "new" until hovered or opened.
 */
public final class SeenItems {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("wtfi").resolve("seen_items.txt");
	private final Set<Identifier> seen = new HashSet<>();
	private final Set<Item> fresh = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private boolean dirty;
	private int version;

	public static SeenItems load() {
		SeenItems items = new SeenItems();
		boolean firstRun = !Files.exists(PATH);
		if (!firstRun) {
			try {
				for (String line : Files.readAllLines(PATH)) {
					Identifier id = Identifier.tryParse(line.trim());
					if (id != null) items.seen.add(id);
				}
			} catch (IOException e) {
				Wtfi.LOGGER.warn("Could not read {}", PATH, e);
			}
		}
		for (Item item : BuiltInRegistries.ITEM) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (firstRun) {
				// Nothing is "new" the first time WTFI runs.
				items.seen.add(id);
				items.dirty = true;
			} else if (!items.seen.contains(id)) {
				items.fresh.add(item);
			}
		}
		return items;
	}

	public boolean isNew(Item item) {
		return this.fresh.contains(item);
	}

	public boolean anyNew() {
		return !this.fresh.isEmpty();
	}

	public void markSeen(Item item) {
		if (this.fresh.remove(item)) {
			this.seen.add(BuiltInRegistries.ITEM.getKey(item));
			this.dirty = true;
			this.version++;
		}
	}

	public void markAllSeen() {
		for (Item item : List.copyOf(this.fresh)) this.markSeen(item);
	}

	public int version() {
		return this.version;
	}

	public void save() {
		if (!this.dirty) return;
		this.dirty = false;
		List<String> lines = new ArrayList<>(this.seen.size());
		for (Identifier id : this.seen) lines.add(id.toString());
		lines.sort(String::compareTo);
		Util.ioPool().execute(() -> {
			try {
				Files.createDirectories(PATH.getParent());
				Files.write(PATH, lines);
			} catch (IOException e) {
				Wtfi.LOGGER.warn("Could not save {}", PATH, e);
			}
		});
	}
}
