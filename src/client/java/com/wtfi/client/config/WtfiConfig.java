package com.wtfi.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wtfi.Wtfi;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Player settings, stored as JSON in config/wtfi.json. Fields are public so Gson can fill them;
 * call {@link #save()} after changing anything.
 */
public final class WtfiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("wtfi.json");
	private static WtfiConfig instance;

	/** Library panel shown next to containers. */
	public boolean panelVisible = true;
	/** Per-screen toggle, keyed by menu type id (or "inventory"/"creative"). Missing = enabled. */
	public Map<String, Boolean> screens = new LinkedHashMap<>();
	/** Clicking an item gives it instead of opening its page. */
	public boolean cheatMode = false;
	public String sort = "CREATIVE";
	public boolean sortDescending = false;
	/** Selected filter categories (names of {@link com.wtfi.client.index.Category}). */
	public Set<String> filters = new LinkedHashSet<>();
	/** Restrict the library to one mod namespace, or empty for all. */
	public String modFilter = "";
	public int maxColumns = 12;
	public boolean showModName = true;
	public boolean storageMemory = true;
	public boolean highlightNewItems = true;
	public boolean huntHud = true;
	public boolean checklistHud = true;
	/** 0 = top left, 1 = top right. */
	public int hudCorner = 0;

	public static WtfiConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static WtfiConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				WtfiConfig config = GSON.fromJson(reader, WtfiConfig.class);
				if (config != null) {
					config.fillDefaults();
					return config;
				}
			} catch (Exception e) {
				Wtfi.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}
		WtfiConfig config = new WtfiConfig();
		config.save();
		return config;
	}

	private void fillDefaults() {
		if (this.screens == null) this.screens = new LinkedHashMap<>();
		if (this.filters == null) this.filters = new LinkedHashSet<>();
		if (this.sort == null) this.sort = "CREATIVE";
		if (this.modFilter == null) this.modFilter = "";
		this.maxColumns = Math.max(3, Math.min(24, this.maxColumns));
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Wtfi.LOGGER.warn("Could not save {}", PATH, e);
		}
	}

	public boolean isScreenEnabled(String screenKey) {
		return this.screens.getOrDefault(screenKey, true);
	}

	public void setScreenEnabled(String screenKey, boolean enabled) {
		this.screens.put(screenKey, enabled);
		this.save();
	}
}
