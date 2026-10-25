package com.wtfi.client.ui.page;

import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.data.WtfiDataset.LootDrop;
import com.wtfi.data.WtfiDataset.StructureInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/** Names and icons for where things come from: loot tables, mobs, structures, traders. */
public final class Sources {
	private static final Map<String, Item> PROFESSION_ICONS = Map.ofEntries(
		Map.entry("armorer", Items.BLAST_FURNACE),
		Map.entry("butcher", Items.SMOKER),
		Map.entry("cartographer", Items.CARTOGRAPHY_TABLE),
		Map.entry("cleric", Items.BREWING_STAND),
		Map.entry("farmer", Items.COMPOSTER),
		Map.entry("fisherman", Items.BARREL),
		Map.entry("fletcher", Items.FLETCHING_TABLE),
		Map.entry("leatherworker", Items.CAULDRON),
		Map.entry("librarian", Items.LECTERN),
		Map.entry("mason", Items.STONECUTTER),
		Map.entry("shepherd", Items.LOOM),
		Map.entry("toolsmith", Items.SMITHING_TABLE),
		Map.entry("weaponsmith", Items.GRINDSTONE),
		Map.entry("wandering_trader", Items.LEAD)
	);

	/** Vanilla chest tables whose structure id differs from the table name. */
	private static final Map<String, List<String>> TABLE_STRUCTURES = Map.ofEntries(
		Map.entry("jungle_temple", List.of("jungle_pyramid")),
		Map.entry("jungle_temple_dispenser", List.of("jungle_pyramid")),
		Map.entry("igloo_chest", List.of("igloo")),
		Map.entry("woodland_mansion", List.of("mansion")),
		Map.entry("abandoned_mineshaft", List.of("mineshaft", "mineshaft_mesa")),
		Map.entry("nether_bridge", List.of("fortress")),
		Map.entry("end_city_treasure", List.of("end_city")),
		Map.entry("ancient_city_ice_box", List.of("ancient_city")),
		Map.entry("ruined_portal", List.of("ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp", "ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_nether"))
	);

	private Sources() {
	}

	public static ItemStack lootIcon(LootDrop drop) {
		Identifier table = drop.table();
		return switch (drop.kind()) {
			case "block" -> {
				Block block = BuiltInRegistries.BLOCK.getValue(blockOf(table));
				Item item = block.asItem();
				yield item == Items.AIR ? new ItemStack(Items.STONE) : new ItemStack(item);
			}
			case "entity", "charged_creeper" -> entityIcon(Knowledge.entityOf(table.getPath().startsWith("charged_creeper/") ? retarget(table, "entities/") : table));
			case "chest" -> new ItemStack(table.getPath().contains("dispenser") ? Items.DISPENSER : table.getPath().startsWith("pots/") ? Items.DECORATED_POT : Items.CHEST);
			case "fishing" -> new ItemStack(Items.FISHING_ROD);
			case "barter" -> new ItemStack(Items.GOLD_INGOT);
			case "archaeology" -> new ItemStack(Items.BRUSH);
			case "trial_spawner" -> new ItemStack(table.getPath().contains("ominous") ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY);
			case "hero_gift" -> new ItemStack(Items.EMERALD);
			case "gift" -> new ItemStack(Items.STRING);
			case "sniffer" -> new ItemStack(Items.SNIFFER_EGG);
			case "shearing" -> new ItemStack(Items.SHEARS);
			case "brush" -> new ItemStack(Items.BRUSH);
			case "carve" -> new ItemStack(Items.SHEARS);
			case "till" -> new ItemStack(Items.IRON_HOE);
			case "harvest" -> new ItemStack(Items.SWEET_BERRIES);
			case "equipment" -> new ItemStack(Items.IRON_SWORD);
			default -> new ItemStack(Items.PAPER);
		};
	}

	private static Identifier retarget(Identifier table, String prefix) {
		String path = table.getPath();
		return Identifier.fromNamespaceAndPath(table.getNamespace(), prefix + path.substring(path.indexOf('/') + 1));
	}

	public static Identifier blockOf(Identifier table) {
		String path = table.getPath();
		return Identifier.fromNamespaceAndPath(table.getNamespace(), path.startsWith("blocks/") ? path.substring(7) : path);
	}

	public static ItemStack entityIcon(Identifier entity) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entity);
		return SpawnEggItem.byId(type).map(h -> new ItemStack(h.value())).orElse(new ItemStack(Items.NAME_TAG));
	}

	public static Component entityName(Identifier entity) {
		return BuiltInRegistries.ENTITY_TYPE.getValue(entity).getDescription();
	}

	/** Display name for a loot table: the block/mob it belongs to, or a readable table name. */
	public static Component lootName(LootDrop drop) {
		Identifier table = drop.table();
		switch (drop.kind()) {
			case "block" -> {
				Block block = BuiltInRegistries.BLOCK.getValue(blockOf(table));
				return block.getName();
			}
			case "entity" -> {
				Identifier entity = Knowledge.entityOf(table);
				String path = table.getPath();
				MutableComponent name = entityName(entity).copy();
				String rest = path.substring(path.indexOf('/') + 1);
				if (rest.contains("/")) name.append(Component.literal(" (" + pretty(rest.substring(rest.indexOf('/') + 1)) + ")"));
				return name;
			}
			default -> {
			}
		}
		String key = "wtfi.loot." + table.getNamespace() + "." + table.getPath().replace('/', '.');
		if (Language.getInstance().has(key)) return Component.translatable(key);
		String path = table.getPath();
		if (path.contains("trial_chamber")) {
			// chests/trial_chambers/reward_rare, spawners/trial_chamber/key, pots/trial_chambers/corridor...
			String kindKey = path.startsWith("spawners/") ? "wtfi.loot.trial_spawner" : path.startsWith("pots/") ? "wtfi.loot.trial_pot"
				: path.contains("reward") ? "wtfi.loot.trial_vault" : "wtfi.loot.trial_chest";
			String rest = path.substring(path.lastIndexOf('/') + 1).replace("reward_", "").replace("reward", "");
			return Component.translatable(kindKey).append(rest.isEmpty() ? "" : " (" + pretty(rest) + ")");
		}
		Component kind = Component.translatable("wtfi.loot_kind." + drop.kind());
		String rest = path.contains("/") ? path.substring(path.indexOf('/') + 1) : path;
		return Component.empty().append(kind).append(": " + pretty(rest));
	}

	/** "≈23% · 1–3 · needs Silk Touch" */
	public static List<Component> lootDetails(LootDrop drop) {
		List<Component> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		if (drop.chance() >= 0) line.append(percent(drop.chance()));
		if (drop.max() > 1 || drop.min() != 1) {
			if (!line.isEmpty()) line.append(" · ");
			line.append(drop.min() == drop.max() ? "×" + drop.max() : drop.min() + "–" + drop.max());
		}
		MutableComponent first = Component.literal(line.toString());
		if (!drop.conditions().isEmpty()) {
			if (!line.isEmpty()) first.append(" · ");
			first.append(conditions(drop.conditions()));
		}
		out.add(first);
		return out;
	}

	public static MutableComponent conditions(List<String> labels) {
		MutableComponent out = Component.empty();
		boolean firstLabel = true;
		for (String label : labels) {
			if (!firstLabel) out.append(", ");
			firstLabel = false;
			out.append(conditionLabel(label));
		}
		return out;
	}

	public static Component conditionLabel(String label) {
		if (label.startsWith("not:")) return Component.translatable("wtfi.cond.not", conditionLabel(label.substring(4)));
		if (label.startsWith("map:")) return Component.translatable("wtfi.cond.map", pretty(Identifier.parse(label.substring(4)).getPath()));
		if (label.startsWith("cond:")) return Component.literal(pretty(label.substring(5)));
		String key = "wtfi.cond." + label;
		if (Language.getInstance().has(key)) return Component.translatable(key);
		if (label.endsWith("_bonus")) {
			String enchant = label.substring(0, label.length() - 6);
			return Component.translatable("wtfi.cond.bonus", pretty(enchant));
		}
		return Component.literal(pretty(label));
	}

	public static String percent(float chance) {
		float pct = chance * 100f;
		if (pct >= 99.95f) return "100%";
		if (pct >= 10f) return String.format(Locale.ROOT, "≈%.0f%%", pct);
		if (pct >= 1f) return String.format(Locale.ROOT, "≈%.1f%%", pct);
		return String.format(Locale.ROOT, "≈%.2f%%", pct);
	}

	/** Structures a chest/archaeology/trial loot table belongs to (best effort). */
	public static List<StructureInfo> structuresFor(Knowledge knowledge, Identifier table) {
		String path = table.getPath();
		String rest = path.contains("/") ? path.substring(path.indexOf('/') + 1) : path;
		List<String> candidates = new ArrayList<>();
		if (path.startsWith("spawners/") || path.contains("trial_chamber")) {
			candidates.add("trial_chambers");
		} else if (rest.startsWith("village/")) {
			candidates.addAll(List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"));
		} else if (rest.startsWith("shipwreck")) {
			candidates.addAll(List.of("shipwreck", "shipwreck_beached"));
		} else if (rest.startsWith("underwater_ruin") || rest.startsWith("ocean_ruin")) {
			candidates.addAll(List.of("ocean_ruin_cold", "ocean_ruin_warm"));
		} else if (rest.startsWith("stronghold")) {
			candidates.add("stronghold");
		} else if (rest.startsWith("bastion")) {
			candidates.add("bastion_remnant");
		} else if (rest.startsWith("trail_ruins")) {
			candidates.add("trail_ruins");
		} else if (rest.startsWith("ancient_city")) {
			candidates.add("ancient_city");
		} else {
			String name = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
			candidates.addAll(TABLE_STRUCTURES.getOrDefault(name, List.of(name)));
		}
		List<StructureInfo> out = new ArrayList<>();
		for (String candidate : candidates) {
			StructureInfo info = knowledge.structure(Identifier.fromNamespaceAndPath(table.getNamespace(), candidate));
			if (info == null && !table.getNamespace().equals("minecraft")) {
				info = knowledge.structure(Identifier.withDefaultNamespace(candidate));
			}
			if (info != null && !out.contains(info)) out.add(info);
		}
		return out;
	}

	public static Component structureName(Identifier id) {
		String key = "wtfi.structure." + id.getNamespace() + "." + id.getPath();
		if (Language.getInstance().has(key)) return Component.translatable(key);
		return Component.literal(pretty(id.getPath()));
	}

	public static ItemStack professionIcon(Identifier trader) {
		return new ItemStack(PROFESSION_ICONS.getOrDefault(trader.getPath(), Items.EMERALD));
	}

	public static Component traderName(Identifier trader, int level) {
		Component name;
		if (trader.getPath().equals("wandering_trader")) {
			name = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("wandering_trader")).getDescription();
		} else {
			var profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(trader);
			name = profession != null && BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).equals(trader) ? profession.name() : Component.literal(pretty(trader.getPath()));
		}
		if (level <= 0) return name;
		return Component.empty().append(name).append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.translatable("merchant.level." + level));
	}

	/** "Biome A, Biome B and 12 more" or "every Overworld biome". */
	public static Component biomeSummary(Knowledge knowledge, List<Identifier> biomes, @Nullable Identifier dimension, int limit) {
		if (biomes.isEmpty()) return Component.translatable("wtfi.where.no_biomes");
		if (dimension != null) {
			int total = knowledge.biomeCount(dimension);
			if (total > 3 && biomes.size() >= total) return Component.translatable("wtfi.where.all_biomes", dimensionName(dimension));
		}
		MutableComponent out = Component.empty();
		for (int i = 0; i < Math.min(limit, biomes.size()); i++) {
			if (i > 0) out.append(", ");
			out.append(biomeName(biomes.get(i)));
		}
		if (biomes.size() > limit) out.append(Component.translatable("wtfi.where.and_more", biomes.size() - limit));
		return out;
	}

	public static Component biomeName(Identifier biome) {
		return Component.translatable("biome." + biome.getNamespace() + "." + biome.getPath());
	}

	public static Component dimensionName(Identifier dimension) {
		String key = "wtfi.dimension." + dimension.getNamespace() + "." + dimension.getPath();
		if (Language.getInstance().has(key)) return Component.translatable(key);
		return Component.literal(pretty(dimension.getPath()));
	}

	/** "north east, 12 down" from one position to another. */
	public static Component direction(net.minecraft.core.BlockPos from, net.minecraft.core.BlockPos to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double angle = Math.toDegrees(Math.atan2(dz, dx));
		String[] names = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};
		int index = (int)Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
		int dy = to.getY() - from.getY();
		MutableComponent dir = Component.translatable("wtfi.dir." + names[index]);
		if (Math.abs(dy) >= 4) dir.append(", ").append(Component.translatable(dy > 0 ? "wtfi.dir.up" : "wtfi.dir.down", Math.abs(dy)));
		return dir;
	}

	public static String pretty(String path) {
		String last = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
		StringBuilder out = new StringBuilder();
		for (String word : last.split("_")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}
}
