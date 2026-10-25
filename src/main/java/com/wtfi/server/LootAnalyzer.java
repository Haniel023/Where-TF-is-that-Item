package com.wtfi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.wtfi.data.WtfiDataset.LootDrop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

/**
 * Walks every loot table (in its JSON form) and lists what it can drop, with an approximate
 * chance per use and short condition labels ("silk_touch", "player_kill"...). Conditions that
 * depend on the situation are reported, not evaluated, so chances are best-case approximations.
 */
final class LootAnalyzer {
	private static final int MAX_DEPTH = 6;
	private final HolderLookup.Provider registries;
	private final Map<Identifier, JsonObject> tables = new LinkedHashMap<>();

	LootAnalyzer(HolderLookup.Provider registries) {
		this.registries = registries;
		HolderLookup.RegistryLookup<LootTable> lookup = registries.lookupOrThrow(Registries.LOOT_TABLE);
		lookup.listElements().forEach(holder -> {
			JsonObject json = Json.object(Json.encode(LootTable.DIRECT_CODEC, holder.value(), registries));
			if (json != null) {
				this.tables.put(holder.key().identifier(), json);
			}
		});
	}

	List<LootDrop> analyze() {
		List<LootDrop> out = new ArrayList<>();
		for (var entry : this.tables.entrySet()) {
			Identifier id = entry.getKey();
			Map<String, Acc> drops = new LinkedHashMap<>();
			try {
				this.walkTable(entry.getValue(), 1f, List.of(), List.of(), 0, drops);
			} catch (RuntimeException e) {
				continue;
			}
			String kind = kindOf(id);
			for (Acc acc : drops.values()) {
				out.add(new LootDrop(id, kind, acc.stack, List.copyOf(acc.enchantments), acc.chance, acc.min, acc.max, List.copyOf(acc.labels)));
			}
		}
		return out;
	}

	static String kindOf(Identifier table) {
		String path = table.getPath();
		String first = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
		return switch (first) {
			case "blocks" -> "block";
			case "entities" -> "entity";
			case "chests", "dispensers", "pots", "barrels" -> "chest";
			case "archaeology" -> "archaeology";
			case "spawners" -> "trial_spawner";
			case "equipment" -> "equipment";
			case "shearing" -> "shearing";
			case "brush" -> "brush";
			case "harvest" -> "harvest";
			case "carve" -> "carve";
			case "till" -> "till";
			case "charged_creeper" -> "charged_creeper";
			case "gameplay" -> {
				if (path.startsWith("gameplay/fishing")) yield "fishing";
				if (path.startsWith("gameplay/piglin_bartering")) yield "barter";
				if (path.startsWith("gameplay/hero_of_the_village")) yield "hero_gift";
				if (path.contains("gift")) yield "gift";
				if (path.contains("sniffer")) yield "sniffer";
				yield "gameplay";
			}
			default -> "other";
		};
	}

	private void walkTable(JsonObject table, float multiplier, List<String> labels, List<JsonElement> inherited, int depth, Map<String, Acc> out) {
		if (depth > MAX_DEPTH) return;
		List<JsonElement> tableMods = concat(inherited, Json.list(Json.first(table, "modifier", "functions")));
		for (JsonElement poolElement : Json.list(table.get("pools"))) {
			JsonObject pool = Json.object(poolElement);
			if (pool == null) continue;
			float rolls = Math.max(0, Json.range(pool.get("rolls"), 1)[2]);
			Cond poolCond = this.condition(Json.first(pool, "condition", "conditions"));
			List<JsonElement> poolMods = concat(tableMods, Json.list(Json.first(pool, "modifier", "functions")));

			List<Flat> flat = new ArrayList<>();
			float totalWeight = this.flatten(Json.list(pool.get("entries")), List.of(), flat);
			if (totalWeight <= 0) continue;
			for (Flat f : flat) {
				float perRoll = f.weight / totalWeight * f.cond.factor;
				float atLeastOnce = 1f - (float)Math.pow(1f - Math.min(1f, perRoll), Math.max(1f, rolls));
				if (rolls < 1f) atLeastOnce *= rolls;
				float chance = multiplier * poolCond.factor * atLeastOnce;
				List<String> allLabels = merge(labels, poolCond.labels, f.cond.labels);
				this.emit(f.entry, chance, allLabels, poolMods, depth, out);
			}
		}
	}

	/** Expands composite entries; returns the total weight the pool picks from. */
	private float flatten(List<JsonElement> entries, List<String> labels, List<Flat> out) {
		float total = 0;
		for (JsonElement element : entries) {
			JsonObject entry = Json.object(element);
			if (entry == null) continue;
			String type = Json.type(entry);
			Cond cond = this.condition(Json.first(entry, "condition", "conditions"));
			List<String> childLabels = merge(labels, cond.labels);
			switch (type) {
				case "alternatives" -> {
					// Only the first child whose conditions pass is used: count its weight once.
					List<Flat> children = new ArrayList<>();
					this.flatten(Json.list(entry.get("children")), childLabels, children);
					float max = 0;
					for (Flat child : children) max = Math.max(max, child.weight);
					total += max;
					out.addAll(children);
				}
				case "group", "sequence" -> total += this.flatten(Json.list(entry.get("children")), childLabels, out);
				default -> {
					float weight = Json.number(entry.get("weight"), 1);
					total += weight;
					if (!type.equals("empty")) {
						out.add(new Flat(entry, weight, new Cond(cond.factor, childLabels)));
					}
				}
			}
		}
		return total;
	}

	private void emit(JsonObject entry, float chance, List<String> labels, List<JsonElement> inheritedMods, int depth, Map<String, Acc> out) {
		String type = Json.type(entry);
		List<JsonElement> mods = concat(inheritedMods, Json.list(Json.first(entry, "modifier", "functions")));
		switch (type) {
			case "item" -> {
				Item item = this.item(Json.string(entry, "name"));
				if (item != null) this.addItem(item, chance, labels, mods, out);
			}
			case "tag" -> {
				// 26.x writes the tag under "items" ("#minecraft:..."); older data used "name".
				String name = Json.string(entry, "items");
				if (name == null) name = Json.string(entry, "name");
				if (name == null) return;
				TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(name.startsWith("#") ? name.substring(1) : name));
				List<Holder<Item>> items = new ArrayList<>();
				BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(items::add);
				boolean expand = entry.has("expand") && entry.get("expand").getAsBoolean();
				for (Holder<Item> holder : items) {
					this.addItem(holder.value(), expand ? chance / items.size() : chance, labels, mods, out);
				}
			}
			case "loot_table" -> {
				JsonElement value = Json.first(entry, "value", "name");
				JsonObject nested = null;
				if (value != null && value.isJsonPrimitive()) {
					nested = this.tables.get(Identifier.parse(value.getAsString()));
				} else if (value != null && value.isJsonObject()) {
					nested = value.getAsJsonObject();
				}
				if (nested != null) this.walkTable(nested, chance, labels, mods, depth + 1, out);
			}
			default -> {
			}
		}
	}

	private void addItem(Item item, float chance, List<String> labels, List<JsonElement> mods, Map<String, Acc> out) {
		ItemStack stack = new ItemStack(item);
		float[] count = {1, 1};
		Set<String> extraLabels = new LinkedHashSet<>();
		List<Identifier> enchantments = new ArrayList<>();
		for (JsonElement modElement : mods) {
			JsonObject mod = Json.object(modElement);
			if (mod == null) continue;
			Cond modCond = this.condition(Json.first(mod, "condition", "conditions"));
			switch (Json.type(mod)) {
				case "set_count" -> {
					float[] r = Json.range(mod.get("count"), 1);
					boolean add = mod.has("add") && mod.get("add").getAsBoolean();
					if (modCond.factor < 1f || !modCond.labels.isEmpty()) {
						// Conditional counts (e.g. sea pickles by age): widen the range rather than replace it.
						count[0] = Math.min(count[0], add ? count[0] + r[0] : r[0]);
						count[1] = Math.max(count[1], add ? count[1] + r[1] : r[1]);
					} else if (add) {
						count[0] += r[0];
						count[1] += r[1];
					} else {
						count[0] = r[0];
						count[1] = r[1];
					}
				}
				case "limit_count" -> {
					JsonObject limit = Json.object(mod.get("limit"));
					if (limit != null) {
						count[0] = Math.max(count[0], Json.range(limit.get("min"), count[0])[0]);
						count[1] = Math.min(count[1], Json.range(limit.get("max"), count[1])[1]);
					}
				}
				case "apply_bonus" -> extraLabels.add(bonusLabel(Json.string(mod, "enchantment"), "fortune"));
				case "enchanted_count_increase", "looting_enchant" -> extraLabels.add(bonusLabel(Json.string(mod, "enchantment"), "looting"));
				case "enchant_randomly", "enchant_with_levels" -> {
					List<Identifier> options = this.enchantmentOptions(mod.get("options"));
					if (item == Items.BOOK) {
						stack = new ItemStack(Items.ENCHANTED_BOOK);
						enchantments.addAll(options);
					} else {
						extraLabels.add("random_enchant");
					}
				}
				case "set_enchantments" -> {
					JsonObject map = Json.object(mod.get("enchantments"));
					if (map != null) {
						for (String key : map.keySet()) enchantments.add(Identifier.parse(key));
						if (item == Items.BOOK) stack = new ItemStack(Items.ENCHANTED_BOOK);
					}
				}
				case "set_potion" -> {
					String potion = Json.string(mod, "id");
					if (potion != null) {
						Holder<Potion> holder = BuiltInRegistries.POTION.get(Identifier.parse(potion)).map(h -> (Holder<Potion>)h).orElse(null);
						if (holder != null) stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder));
					}
				}
				case "set_components" -> {
					JsonElement components = mod.get("components");
					if (components != null) {
						DataComponentPatch.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, this.registries), components)
							.result()
							.ifPresent(stack::applyComponents);
					}
				}
				case "exploration_map" -> {
					String destination = Json.string(mod, "destination");
					extraLabels.add(destination != null ? "map:" + destination.replace("#", "") : "map");
				}
				case "furnace_smelt" -> extraLabels.add("smelted_when_burning");
				case "set_random_potion" -> extraLabels.add("random_potion");
				case "set_random_dyes" -> extraLabels.add("random_dye");
				case "set_instrument" -> extraLabels.add("random_instrument");
				default -> {
				}
			}
		}

		List<String> allLabels = merge(labels, new ArrayList<>(extraLabels));
		String key = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getComponentsPatch() + "|" + enchantments;
		Acc acc = out.get(key);
		int min = Math.max(0, Math.round(count[0]));
		int max = Math.max(min, Math.round(count[1]));
		if (acc == null) {
			out.put(key, new Acc(stack, enchantments, Math.min(1f, chance), min, max, new LinkedHashSet<>(allLabels)));
		} else {
			acc.chance = 1f - (1f - acc.chance) * (1f - Math.min(1f, chance));
			acc.min = Math.min(acc.min, min);
			acc.max = Math.max(acc.max, max);
			acc.labels.addAll(allLabels);
		}
	}

	private static String bonusLabel(@Nullable String enchantment, String fallback) {
		if (enchantment == null) return fallback + "_bonus";
		String path = Identifier.parse(enchantment).getPath();
		return path + "_bonus";
	}

	private List<Identifier> enchantmentOptions(@Nullable JsonElement options) {
		HolderLookup.RegistryLookup<Enchantment> lookup = this.registries.lookupOrThrow(Registries.ENCHANTMENT);
		List<Identifier> out = new ArrayList<>();
		if (options == null) {
			lookup.listElements().forEach(h -> out.add(h.key().identifier()));
			return out;
		}
		for (JsonElement option : Json.list(options)) {
			if (!option.isJsonPrimitive()) continue;
			String value = option.getAsString();
			if (value.startsWith("#")) {
				TagKey<Enchantment> tag = TagKey.create(Registries.ENCHANTMENT, Identifier.parse(value.substring(1)));
				lookup.get(tag).ifPresent(set -> set.stream().forEach(h -> h.unwrapKey().ifPresent(k -> out.add(k.identifier()))));
			} else {
				out.add(Identifier.parse(value));
			}
		}
		return out;
	}

	private @Nullable Item item(@Nullable String id) {
		if (id == null) return null;
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return null;
		Item item = BuiltInRegistries.ITEM.getValue(parsed);
		return item == Items.AIR ? null : item;
	}

	/** Turns a condition tree into a chance factor and readable labels. */
	private Cond condition(@Nullable JsonElement element) {
		float factor = 1f;
		Set<String> labels = new LinkedHashSet<>();
		for (JsonElement e : Json.list(element)) {
			Cond c = this.single(e);
			factor *= c.factor;
			labels.addAll(c.labels);
		}
		return new Cond(factor, List.copyOf(labels));
	}

	private Cond single(JsonElement element) {
		if (element.isJsonPrimitive()) {
			// A reference to a registered predicate, e.g. minecraft:tool/can_silk_touch.
			String path = Identifier.parse(element.getAsString()).getPath();
			if (path.contains("silk_touch")) return new Cond(1f, List.of("silk_touch"));
			if (path.contains("shears")) return new Cond(1f, List.of("shears"));
			return new Cond(1f, List.of("cond:" + path));
		}
		JsonObject o = Json.object(element);
		if (o == null) return Cond.NONE;
		String text = o.toString();
		return switch (Json.type(o)) {
			case "random_chance" -> new Cond(Math.min(1f, Json.range(o.get("chance"), 1)[2]), List.of());
			case "random_chance_with_enchanted_bonus" -> new Cond(Math.min(1f, Json.range(o.get("unenchanted_chance"), 1)[2]), List.of("looting_bonus"));
			case "killed_by_player" -> new Cond(1f, List.of("player_kill"));
			case "match_tool" -> {
				if (text.contains("silk_touch")) yield new Cond(1f, List.of("silk_touch"));
				if (text.contains("shears")) yield new Cond(1f, List.of("shears"));
				yield new Cond(1f, List.of("specific_tool"));
			}
			case "table_bonus" -> {
				List<JsonElement> chances = Json.list(o.get("chances"));
				float base = chances.isEmpty() ? 1f : Json.number(chances.getFirst(), 1f);
				yield new Cond(base, List.of(bonusLabel(Json.string(o, "enchantment"), "fortune")));
			}
			case "entity_properties" -> {
				if (text.contains("\"attacker\"") && text.contains("skeleton")) yield new Cond(1f, List.of("killed_by_skeleton"));
				if (text.contains("is_on_fire")) yield new Cond(1f, List.of("on_fire"));
				if (text.contains("is_baby")) yield new Cond(1f, List.of("baby"));
				if (text.contains("vehicle")) yield new Cond(1f, List.of("riding"));
				if (text.contains("in_open_water")) yield new Cond(1f, List.of("open_water"));
				yield new Cond(1f, List.of("entity_condition"));
			}
			case "damage_source_properties" -> new Cond(1f, List.of(text.contains("is_explosion") || text.contains("explosion") ? "explosion_kill" : "damage_type"));
			case "match_block", "block_state_property" -> new Cond(1f, List.of(text.contains("age") ? "fully_grown" : "block_state"));
			case "location_check" -> new Cond(1f, List.of(text.contains("biome") ? "biome" : "location"));
			case "weather_check" -> new Cond(1f, List.of("weather"));
			case "time_check" -> new Cond(1f, List.of("time"));
			case "survives_explosion", "value_check", "enchantment_active_check" -> Cond.NONE;
			case "inverted" -> {
				Cond inner = this.condition(o.get("term"));
				yield new Cond(1f, inner.labels.stream().map(l -> "not:" + l).toList());
			}
			case "all_of", "any_of", "alternative" -> {
				Cond inner = this.condition(o.get("terms"));
				yield Json.type(o).equals("all_of") ? inner : new Cond(Math.min(1f, inner.factor), inner.labels);
			}
			case "reference" -> {
				String name = Json.string(o, "name");
				yield name != null ? this.single(new com.google.gson.JsonPrimitive(name)) : Cond.NONE;
			}
			default -> new Cond(1f, List.of("conditional"));
		};
	}

	private static List<JsonElement> concat(List<JsonElement> a, List<JsonElement> b) {
		if (a.isEmpty()) return b;
		if (b.isEmpty()) return a;
		List<JsonElement> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}

	@SafeVarargs
	private static List<String> merge(List<String>... lists) {
		Set<String> out = new LinkedHashSet<>();
		for (List<String> list : lists) out.addAll(list);
		return List.copyOf(out);
	}

	private record Flat(JsonObject entry, float weight, Cond cond) {
	}

	private record Cond(float factor, List<String> labels) {
		static final Cond NONE = new Cond(1f, List.of());
	}

	private static final class Acc {
		final ItemStack stack;
		final List<Identifier> enchantments;
		float chance;
		int min;
		int max;
		final Set<String> labels;

		Acc(ItemStack stack, List<Identifier> enchantments, float chance, int min, int max, Set<String> labels) {
			this.stack = stack;
			this.enchantments = enchantments;
			this.chance = chance;
			this.min = min;
			this.max = max;
			this.labels = labels;
		}
	}
}
