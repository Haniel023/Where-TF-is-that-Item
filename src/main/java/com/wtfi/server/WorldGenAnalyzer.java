package com.wtfi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wtfi.data.WtfiDataset.GenInfo;
import com.wtfi.data.WtfiDataset.SpawnInfo;
import com.wtfi.data.WtfiDataset.StructureInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

/**
 * Works out which blocks world generation places where: features per biome (ores, trees,
 * plants...) with their height ranges, terrain blocks from surface rules, structure biomes and
 * natural mob spawns.
 */
final class WorldGenAnalyzer {
	/** Subtrees that name blocks a feature checks or replaces, not blocks it places. */
	private static final Set<String> SKIP_KEYS = Set.of(
		"target", "predicate", "if_true", "placement", "below_trunk_provider", "dirt_provider", "can_place_on", "can_replace",
		"replaceable", "valid_base_block", "allowed_placement", "position_predicate", "target_predicate", "cannot_replace",
		"replaceable_blocks", "can_grow_through", "muddy_roots_in", "roots_provider_predicate", "condition", "if_condition"
	);
	private static final int MAX_DEPTH = 5;

	private final MinecraftServer server;
	private final RegistryAccess registries;
	final List<GenInfo> gen = new ArrayList<>();
	final List<StructureInfo> structures = new ArrayList<>();
	final List<SpawnInfo> spawns = new ArrayList<>();

	WorldGenAnalyzer(MinecraftServer server) {
		this.server = server;
		this.registries = server.registryAccess();
	}

	void analyze() {
		Map<Identifier, Identifier> biomeDimension = new HashMap<>();
		Map<Holder<PlacedFeature>, Map<Identifier, Set<Identifier>>> featureBiomes = new LinkedHashMap<>();
		Map<String, Set<Identifier>> mobBiomes = new LinkedHashMap<>();
		Map<Identifier, int[]> dimensionHeights = new HashMap<>();

		for (ServerLevel level : this.server.getAllLevels()) {
			Identifier dimension = level.dimension().identifier();
			dimensionHeights.put(dimension, new int[] {level.getMinY(), level.getHeight()});
			ChunkGenerator generator = level.getChunkSource().getGenerator();
			for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
				Identifier biomeId = biome.unwrapKey().map(ResourceKey::identifier).orElse(null);
				if (biomeId == null) continue;
				biomeDimension.putIfAbsent(biomeId, dimension);
				for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
					for (Holder<PlacedFeature> feature : step) {
						featureBiomes.computeIfAbsent(feature, k -> new LinkedHashMap<>())
							.computeIfAbsent(dimension, k -> new LinkedHashSet<>())
							.add(biomeId);
					}
				}
				MobSpawnSettings mobs = biome.value().getAttributes()
					.applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, EnvironmentAttributes.NATURAL_MOB_SPAWNS.defaultValue());
				for (MobCategory category : MobCategory.values()) {
					for (Weighted<MobSpawnSettings.SpawnerData> spawn : mobs.getMobsToSpawn(category).unwrap()) {
						Identifier entity = BuiltInRegistries.ENTITY_TYPE.getKey(spawn.value().type());
						mobBiomes.computeIfAbsent(entity + "|" + category.getName(), k -> new LinkedHashSet<>()).add(biomeId);
					}
				}
			}
			this.terrain(generator, dimension, biomeDimension);
		}

		featureBiomes.forEach((feature, byDimension) -> byDimension.forEach((dimension, biomes) -> {
			int[] heights = dimensionHeights.getOrDefault(dimension, new int[] {-64, 384});
			this.placedFeature(feature, dimension, List.copyOf(biomes), heights);
		}));

		mobBiomes.forEach((key, biomes) -> {
			String[] parts = key.split("\\|");
			this.spawns.add(new SpawnInfo(Identifier.parse(parts[0]), parts[1], List.copyOf(biomes)));
		});

		this.registries.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(structure -> {
			Structure value = structure.value();
			List<Identifier> biomes = new ArrayList<>();
			Identifier dimension = null;
			for (Holder<Biome> biome : value.biomes()) {
				Identifier id = biome.unwrapKey().map(ResourceKey::identifier).orElse(null);
				if (id == null) continue;
				biomes.add(id);
				if (dimension == null) dimension = biomeDimension.get(id);
			}
			if (dimension == null) dimension = Identifier.withDefaultNamespace("overworld");
			this.structures.add(new StructureInfo(structure.key().identifier(), dimension, biomes));
		});
	}

	private void placedFeature(Holder<PlacedFeature> holder, Identifier dimension, List<Identifier> biomes, int[] heights) {
		Identifier id = holder.unwrapKey().map(ResourceKey::identifier).orElse(Identifier.withDefaultNamespace("inline"));
		JsonObject placed = Json.object(Json.encode(PlacedFeature.DIRECT_CODEC, holder.value(), this.registries));
		if (placed == null) return;

		int minY = Integer.MIN_VALUE;
		int maxY = Integer.MAX_VALUE;
		int peakY = Integer.MIN_VALUE;
		String distribution = "";
		float perChunk = 1f;
		boolean knownCount = true;
		for (JsonElement element : Json.list(placed.get("placement"))) {
			JsonObject modifier = Json.object(element);
			if (modifier == null) continue;
			switch (Json.type(modifier)) {
				case "height_range" -> {
					JsonObject height = Json.object(modifier.get("height"));
					if (height == null) break;
					String type = Json.type(height);
					minY = anchor(Json.object(height.get("min_inclusive")), heights, heights[0]);
					maxY = anchor(Json.object(height.get("max_inclusive")), heights, heights[0] + heights[1] - 1);
					distribution = type.isEmpty() ? "uniform" : type;
					if (type.equals("trapezoid")) {
						peakY = (minY + maxY) / 2;
					} else if (type.contains("biased_to_bottom")) {
						peakY = minY;
					}
				}
				case "heightmap", "surface_relative_threshold_filter", "surface_water_depth_filter" -> {
					if (distribution.isEmpty()) distribution = "surface";
				}
				case "count" -> perChunk *= Json.range(modifier.get("count"), 1)[2];
				case "rarity_filter" -> {
					float chance = Json.number(modifier.get("chance"), 1);
					if (chance > 0) perChunk /= chance;
				}
				case "noise_based_count", "noise_threshold_count", "count_on_every_layer" -> knownCount = false;
				default -> {
				}
			}
		}

		// Trapezoids often reach below the world; keep the peak but show only real heights.
		if (minY != Integer.MIN_VALUE) minY = Math.max(minY, heights[0]);
		if (maxY != Integer.MAX_VALUE) maxY = Math.min(maxY, heights[0] + heights[1] - 1);
		if (peakY != Integer.MIN_VALUE) peakY = Math.max(minY, Math.min(maxY, peakY));

		Set<Identifier> blocks = new LinkedHashSet<>();
		String featureType = this.collectBlocks(holder.value().feature(), blocks, 0);
		for (Identifier block : blocks) {
			this.gen.add(new GenInfo(block, id, featureType, dimension, biomes, minY, maxY, peakY, distribution, knownCount ? perChunk : -1f));
		}
	}

	/** Blocks a feature places, following nested feature references. Returns the feature type. */
	private String collectBlocks(Holder<Feature> feature, Set<Identifier> out, int depth) {
		JsonObject json = Json.object(Json.encode(Feature.DIRECT_CODEC, feature.value(), this.registries));
		if (json == null) return "unknown";
		this.scan(json, out, depth);
		return Json.type(json);
	}

	/** Features and tree decorators that place blocks without naming them in their config. */
	private static final java.util.Map<String, List<String>> IMPLIED_BLOCKS = java.util.Map.of(
		"beehive", List.of("bee_nest"),
		"leave_vine", List.of("vine"),
		"trunk_vine", List.of("vine"),
		"cocoa", List.of("cocoa"),
		"creaking_heart", List.of("creaking_heart"),
		"pale_moss", List.of("pale_moss_carpet"),
		"chorus_plant", List.of("chorus_plant", "chorus_flower")
	);

	private static void decorators(JsonElement json, Set<Identifier> out) {
		if (json == null || json.isJsonPrimitive() || json.isJsonNull()) return;
		if (json.isJsonArray()) {
			for (JsonElement e : json.getAsJsonArray()) decorators(e, out);
			return;
		}
		JsonObject object = json.getAsJsonObject();
		for (String block : IMPLIED_BLOCKS.getOrDefault(Json.type(object), List.of())) {
			Identifier id = Identifier.withDefaultNamespace(block);
			if (BuiltInRegistries.BLOCK.containsKey(id)) out.add(id);
		}
		for (var entry : object.entrySet()) decorators(entry.getValue(), out);
	}

	private void scan(JsonElement json, Set<Identifier> out, int depth) {
		if (depth > MAX_DEPTH) return;
		decorators(json, out);
		Json.strings(json, SKIP_KEYS, value -> {
			if (value.startsWith("#")) return;
			Identifier id = Identifier.tryParse(value);
			if (id == null) return;
			if (BuiltInRegistries.BLOCK.containsKey(id)) {
				if (BuiltInRegistries.BLOCK.getValue(id) != Blocks.AIR && BuiltInRegistries.BLOCK.getValue(id) != Blocks.CAVE_AIR) out.add(id);
				return;
			}
			this.follow(id, out, depth + 1);
		});
	}

	/** A string that names another placed feature, feature or material rule: analyse that too. */
	private void follow(Identifier id, Set<Identifier> out, int depth) {
		var placed = this.registries.lookupOrThrow(Registries.PLACED_FEATURE).get(ResourceKey.create(Registries.PLACED_FEATURE, id));
		if (placed.isPresent()) {
			this.collectBlocks(placed.get().value().feature(), out, depth);
			return;
		}
		var feature = this.registries.lookupOrThrow(Registries.FEATURE).get(ResourceKey.create(Registries.FEATURE, id));
		if (feature.isPresent()) {
			this.collectBlocks(feature.get(), out, depth);
			return;
		}
		var rule = this.registries.lookupOrThrow(Registries.MATERIAL_RULE).get(ResourceKey.create(Registries.MATERIAL_RULE, id));
		if (rule.isPresent()) {
			JsonElement json = Json.encode(MaterialRule.DIRECT_CODEC, rule.get().value(), this.registries);
			if (json != null) this.scan(json, out, depth);
			return;
		}
		var provider = this.registries.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(ResourceKey.create(Registries.BLOCK_STATE_PROVIDER, id));
		if (provider.isPresent()) {
			JsonElement json = Json.encode(BlockStateProvider.DIRECT_CODEC, provider.get().value(), this.registries);
			if (json != null) this.scan(json, out, depth);
		}
	}

	/** Terrain blocks (stone, grass, sand, netherrack...) from the dimension's noise settings. */
	private void terrain(ChunkGenerator generator, Identifier dimension, Map<Identifier, Identifier> biomeDimension) {
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return;
		Holder<NoiseGeneratorSettings> settings = noise.generatorSettings();
		JsonElement json = Json.encode(NoiseGeneratorSettings.DIRECT_CODEC, settings.value(), this.registries);
		if (json == null) return;
		Set<Identifier> blocks = new LinkedHashSet<>();
		JsonObject object = Json.object(json);
		if (object != null) {
			// The noise router only names density functions; skip it to keep this fast.
			JsonObject copy = object.deepCopy();
			copy.remove("noise_router");
			copy.remove("spawn_target");
			this.scan(copy, blocks, 0);
		}
		List<Identifier> biomes = biomeDimension.entrySet().stream().filter(e -> e.getValue().equals(dimension)).map(Map.Entry::getKey).toList();
		Identifier featureId = settings.unwrapKey().map(ResourceKey::identifier).orElse(dimension);
		for (Identifier block : blocks) {
			this.gen.add(new GenInfo(block, featureId, "terrain", dimension, biomes, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, "terrain", -1f));
		}
	}

	private static int anchor(@Nullable JsonObject anchor, int[] heights, int fallback) {
		if (anchor == null) return fallback;
		if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
		if (anchor.has("above_bottom")) return heights[0] + anchor.get("above_bottom").getAsInt();
		if (anchor.has("below_top")) return heights[0] + heights[1] - 1 - anchor.get("below_top").getAsInt();
		return fallback;
	}
}
