package com.wtfi.client.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wtfi.Wtfi;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written knowledge for things the game's data can't express (an Elytra sits in an item
 * frame, a water bucket comes from using a bucket on water...). Any mod or resource pack can add
 * to it:
 * <ul>
 *   <li>lang keys {@code wtfi.note.<namespace>.<path>.obtain} and {@code ...use}</li>
 *   <li>{@code assets/<namespace>/wtfi_flags/*.json} with arrays "renewable", "non_renewable",
 *       "creative_only" and "obtainable" of item ids</li>
 * </ul>
 */
public final class ItemNotes {
	private final Set<Identifier> renewable = new HashSet<>();
	private final Set<Identifier> nonRenewable = new HashSet<>();
	private final Set<Identifier> creativeOnly = new HashSet<>();
	private final Set<Identifier> obtainable = new HashSet<>();
	private final java.util.List<java.util.regex.Pattern> renewablePatterns = new java.util.ArrayList<>();
	private final java.util.List<java.util.regex.Pattern> obtainablePatterns = new java.util.ArrayList<>();

	public static ItemNotes load(Minecraft minecraft) {
		ItemNotes notes = new ItemNotes();
		var resources = minecraft.getResourceManager().listResources("wtfi_flags", path -> path.getPath().endsWith(".json"));
		for (var entry : resources.entrySet()) {
			Resource resource = entry.getValue();
			try (Reader reader = resource.openAsReader()) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				read(json, "renewable", notes.renewable);
				read(json, "non_renewable", notes.nonRenewable);
				read(json, "creative_only", notes.creativeOnly);
				read(json, "obtainable", notes.obtainable);
				readPatterns(json, "renewable_patterns", notes.renewablePatterns);
				readPatterns(json, "obtainable_patterns", notes.obtainablePatterns);
			} catch (Exception e) {
				Wtfi.LOGGER.warn("Bad WTFI flags file {}", entry.getKey(), e);
			}
		}
		return notes;
	}

	private static void read(JsonObject json, String key, Set<Identifier> out) {
		JsonElement element = json.get(key);
		if (!(element instanceof JsonArray array)) return;
		for (JsonElement id : array) {
			Identifier parsed = Identifier.tryParse(id.getAsString());
			if (parsed != null) out.add(parsed);
		}
	}

	private static void readPatterns(JsonObject json, String key, java.util.List<java.util.regex.Pattern> out) {
		JsonElement element = json.get(key);
		if (!(element instanceof JsonArray array)) return;
		for (JsonElement pattern : array) out.add(java.util.regex.Pattern.compile(pattern.getAsString()));
	}

	private static boolean anyMatch(java.util.List<java.util.regex.Pattern> patterns, Identifier item) {
		String id = item.toString();
		for (var pattern : patterns) if (pattern.matcher(id).matches()) return true;
		return false;
	}

	public static @Nullable Component obtainNote(Identifier item) {
		return note(item, "obtain");
	}

	public static @Nullable Component useNote(Identifier item) {
		return note(item, "use");
	}

	private static @Nullable Component note(Identifier item, String kind) {
		String key = "wtfi.note." + item.getNamespace() + "." + item.getPath().replace('/', '.') + "." + kind;
		return Language.getInstance().has(key) ? Component.translatable(key) : null;
	}

	public boolean flaggedRenewable(Identifier item) {
		return this.renewable.contains(item) || anyMatch(this.renewablePatterns, item);
	}

	public boolean flaggedNonRenewable(Identifier item) {
		return this.nonRenewable.contains(item);
	}

	public boolean flaggedCreativeOnly(Identifier item) {
		return this.creativeOnly.contains(item);
	}

	/** Obtainable through a mechanic WTFI can't see in the data (an obtain note also counts). */
	public boolean flaggedObtainable(Identifier item) {
		return this.obtainable.contains(item) || anyMatch(this.obtainablePatterns, item) || obtainNote(item) != null;
	}
}
