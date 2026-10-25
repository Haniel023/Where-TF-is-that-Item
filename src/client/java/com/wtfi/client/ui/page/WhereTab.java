package com.wtfi.client.ui.page;

import static com.wtfi.client.ui.page.ItemPageScreen.t;

import com.wtfi.client.cheat.CheatGiver;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.memory.StorageHighlight;
import com.wtfi.client.memory.StorageMemory;
import com.wtfi.data.WtfiDataset.GenInfo;
import com.wtfi.data.WtfiDataset.LootDrop;
import com.wtfi.data.WtfiDataset.SpawnInfo;
import com.wtfi.data.WtfiDataset.StructureInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * "Where tf is it?": natural generation by biome and height (checked against where you stand),
 * structures and mobs that carry it, and the containers in your own storage that hold it.
 */
final class WhereTab {
	private WhereTab() {
	}

	/** Where the player is right now. */
	record Here(@Nullable Identifier dimension, @Nullable Identifier biome, int y, @Nullable BlockPos pos) {
		static Here of(Minecraft minecraft) {
			LocalPlayer player = minecraft.player;
			if (player == null || minecraft.level == null) return new Here(null, null, 0, null);
			BlockPos pos = player.blockPosition();
			Identifier biome = minecraft.level.getBiome(pos).unwrapKey().map(ResourceKey::identifier).orElse(null);
			return new Here(minecraft.level.dimension().identifier(), biome, pos.getY(), pos);
		}
	}

	static List<Element> build(PageContext ctx) {
		List<Element> out = new ArrayList<>();
		Knowledge knowledge = ctx.knowledge();
		if (knowledge == null) {
			out.add(Elements.text(t("wtfi.page.loading_data")));
			return out;
		}
		ItemStack stack = ctx.pageStack();
		Here here = Here.of(ctx.minecraft());
		if (here.biome != null) {
			out.add(Elements.text(t("wtfi.where.you_are", Sources.biomeName(here.biome), Sources.dimensionName(here.dimension), here.y), 0xFF555555));
		}
		boolean any = false;
		any |= storage(ctx, stack, here, out);
		any |= spawnEgg(knowledge, stack, here, out);
		any |= generation(knowledge, stack, here, out);
		any |= mobs(knowledge, stack, here, out);
		any |= structures(ctx, knowledge, stack, here, out);
		if (!any) {
			out.add(Elements.spacer(4));
			out.add(Elements.text(t("wtfi.where.nothing")));
		}
		return out;
	}

	private static boolean storage(PageContext ctx, ItemStack stack, Here here, List<Element> out) {
		StorageMemory storage = ctx.session().storage();
		if (storage == null) return false;
		List<StorageMemory.Found> found = storage.find(stack, here.dimension, here.pos);
		if (found.isEmpty()) return false;
		int total = found.stream().mapToInt(StorageMemory.Found::count).sum();
		out.add(Elements.heading(t("wtfi.where.storage", total)));
		for (StorageMemory.Found f : found.subList(0, Math.min(12, found.size()))) {
			StorageMemory.Stored stored = f.stored();
			ItemStack icon = BuiltInRegistries.BLOCK.getValue(Identifier.parse(stored.kind())).asItem().getDefaultInstance();
			if (icon.isEmpty()) icon = new ItemStack(Items.CHEST);
			List<Component> details = new ArrayList<>();
			MutableComponent line = t("wtfi.where.storage_count", f.count());
			if (here.pos != null && stored.dimension().equals(here.dimension) && !stored.isEnderChest()) {
				int distance = (int)Math.sqrt(stored.pos().distSqr(here.pos));
				line.append(" · ").append(t("wtfi.where.distance", distance, direction(here.pos, stored.pos())));
			} else if (!stored.isEnderChest()) {
				line.append(" · ").append(Sources.dimensionName(stored.dimension()));
			}
			line.append(" · ").append(t("wtfi.where.seen", ago(stored.seenAt())));
			details.add(line.withStyle(ChatFormatting.DARK_GRAY));
			Component title = stored.isEnderChest()
				? icon.getHoverName()
				: Component.empty().append(icon.getHoverName()).append(" ").append(t("wtfi.storage.at", stored.pos().getX(), stored.pos().getY(), stored.pos().getZ()));
			out.add(Elements.source(List.of(icon), title, details, null));
			if (!stored.isEnderChest()) {
				out.add(Elements.buttons(List.of(new Elements.ButtonSpec(t("wtfi.where.highlight"), () -> {
					StorageHighlight.show(stored);
					ctx.minecraft().gui.setScreen(null);
				}))));
			}
		}
		return true;
	}

	/** For a spawn egg (what the in-world lookup gives for mobs): where the mob spawns. */
	private static boolean spawnEgg(Knowledge knowledge, ItemStack stack, Here here, List<Element> out) {
		if (!(stack.getItem() instanceof net.minecraft.world.item.SpawnEggItem)) return false;
		var type = net.minecraft.world.item.SpawnEggItem.getType(stack);
		if (type == null) return false;
		Identifier entity = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		List<SpawnInfo> spawns = knowledge.spawns(entity);
		out.add(Elements.heading(t("wtfi.where.mob_spawns", type.getDescription())));
		if (spawns.isEmpty()) {
			out.add(Elements.text(t("wtfi.where.no_natural_spawn"), 0xFF707070));
			return true;
		}
		for (SpawnInfo spawn : spawns) {
			Identifier dimension = spawn.biomes().isEmpty() ? null : knowledge.dimensionOf(spawn.biomes().getFirst());
			List<Component> details = new ArrayList<>();
			details.add(t("wtfi.where.biomes", Sources.biomeSummary(knowledge, spawn.biomes(), dimension, 6)));
			if (here.biome != null) {
				boolean inBiome = spawn.biomes().contains(here.biome);
				details.add(t(inBiome ? "wtfi.where.mob_here_yes" : "wtfi.where.mob_here_no").withStyle(inBiome ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_GRAY));
			}
			out.add(Elements.source(List.of(stack), Component.translatable("wtfi.spawn_category." + spawn.category()), details, null));
		}
		return true;
	}

	private static boolean generation(Knowledge knowledge, ItemStack stack, Here here, List<Element> out) {
		List<Knowledge.GenSource> sources = knowledge.generationSources(stack.getItem());
		if (sources.isEmpty()) return false;
		out.add(Elements.heading(t("wtfi.where.generation")));
		// Merge features with the same block, kind, dimension and height range (ore_diamond + ore_diamond_large...).
		Map<String, List<Knowledge.GenSource>> groups = new LinkedHashMap<>();
		for (Knowledge.GenSource source : sources) {
			GenInfo gen = source.gen();
			String key = gen.block() + "|" + gen.featureType() + "|" + gen.dimension() + "|" + gen.minY() + "|" + gen.maxY() + "|" + gen.distribution();
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
		}
		List<List<Knowledge.GenSource>> sorted = new ArrayList<>(groups.values());
		sorted.sort(Comparator.comparingInt((List<Knowledge.GenSource> g) -> g.getFirst().gen().featureType().equals("terrain") ? 1 : 0));
		for (List<Knowledge.GenSource> group : sorted) {
			GenInfo first = group.getFirst().gen();
			ItemStack blockIcon = group.getFirst().block();
			Set<Identifier> biomes = new LinkedHashSet<>();
			float perChunk = 0;
			boolean perChunkKnown = true;
			for (Knowledge.GenSource s : group) {
				GenInfo g = s.gen();
				biomes.addAll(g.biomes());
				if (g.perChunk() < 0) perChunkKnown = false;
				else perChunk += g.perChunk();
			}
			List<Identifier> biomeList = List.copyOf(biomes);
			String featureKey = "wtfi.feature." + first.featureType().replace(':', '.').replace('/', '.');
			MutableComponent title = Component.empty();
			if (!group.getFirst().direct()) title.append(blockIcon.getHoverName()).append(" · ");
			title.append(net.minecraft.locale.Language.getInstance().has(featureKey)
				? Component.translatable(featureKey)
				: Component.literal(Sources.pretty(first.featureType().substring(first.featureType().indexOf(':') + 1))))
				.append(" · ").append(Sources.dimensionName(first.dimension()));
			List<Component> details = new ArrayList<>();
			if (first.hasHeight()) {
				MutableComponent height = t("wtfi.where.height", first.minY(), first.maxY());
				if (first.peakY() != Integer.MIN_VALUE) height.append(", ").append(t("wtfi.where.peak", first.peakY()));
				details.add(height);
				if (here.dimension != null && here.dimension.equals(first.dimension())) {
					int target = first.peakY() != Integer.MIN_VALUE ? first.peakY() : (first.minY() + first.maxY()) / 2;
					if (here.y >= first.minY() && here.y <= first.maxY()) {
						details.add(t("wtfi.where.in_range", here.y).withStyle(ChatFormatting.DARK_GREEN));
					} else {
						int dy = target - here.y;
						details.add(t(dy < 0 ? "wtfi.where.go_down" : "wtfi.where.go_up", Math.abs(dy), target).withStyle(ChatFormatting.GOLD));
					}
				}
			} else if (first.distribution().equals("surface")) {
				details.add(t("wtfi.where.surface"));
			}
			if (perChunkKnown && perChunk > 0 && !first.featureType().equals("terrain")) {
				details.add(t("wtfi.where.per_chunk", OverviewTab.num(perChunk)));
			}
			details.add(t("wtfi.where.biomes", Sources.biomeSummary(knowledge, biomeList, first.dimension(), 4)));
			if (here.biome != null && here.dimension != null && here.dimension.equals(first.dimension())) {
				boolean inBiome = biomes.contains(here.biome);
				details.add(t(inBiome ? "wtfi.where.here_yes" : "wtfi.where.here_no").withStyle(inBiome ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
			}
			out.add(Elements.source(List.of(blockIcon), title, details, group.getFirst().direct() ? null : blockIcon));
		}
		return true;
	}

	private static boolean structures(PageContext ctx, Knowledge knowledge, ItemStack stack, Here here, List<Element> out) {
		Map<Identifier, StructureInfo> structures = new LinkedHashMap<>();
		Map<Identifier, Float> bestChance = new LinkedHashMap<>();
		for (LootDrop drop : knowledge.loot(stack)) {
			if (!drop.kind().equals("chest") && !drop.kind().equals("archaeology") && !drop.kind().equals("trial_spawner")) continue;
			for (StructureInfo s : Sources.structuresFor(knowledge, drop.table())) {
				structures.putIfAbsent(s.id(), s);
				bestChance.merge(s.id(), drop.chance(), Math::max);
			}
		}
		if (structures.isEmpty()) return false;
		out.add(Elements.heading(t("wtfi.where.structures")));
		boolean canLocate = CheatGiver.canCheat(ctx.minecraft());
		List<StructureInfo> ranked = new ArrayList<>(structures.values());
		ranked.sort(Comparator.comparingDouble((StructureInfo s) -> -bestChance.getOrDefault(s.id(), 0f)));
		for (StructureInfo s : ranked.subList(0, Math.min(10, ranked.size()))) {
			List<Component> details = new ArrayList<>();
			float chance = bestChance.getOrDefault(s.id(), -1f);
			if (chance >= 0) details.add(t("wtfi.where.best_chest", Sources.percent(chance)));
			details.add(t("wtfi.where.biomes", Sources.biomeSummary(knowledge, s.biomes(), s.dimension(), 4)));
			if (here.biome != null) {
				boolean inBiome = s.biomes().contains(here.biome);
				details.add(t(inBiome ? "wtfi.where.structure_here_yes" : "wtfi.where.structure_here_no").withStyle(inBiome ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_GRAY));
			}
			out.add(Elements.source(List.of(new ItemStack(Items.CHEST)), Component.empty().append(Sources.structureName(s.id())).append(" · ").append(Sources.dimensionName(s.dimension())), details, null));
			if (canLocate) {
				out.add(Elements.buttons(List.of(new Elements.ButtonSpec(t("wtfi.where.locate"), () -> {
					var player = ctx.minecraft().player;
					if (player != null) player.connection.sendCommand("locate structure " + s.id());
					ctx.minecraft().gui.setScreen(null);
				}, () -> true, t("wtfi.where.locate.tooltip")))));
			}
		}
		return true;
	}

	private static boolean mobs(Knowledge knowledge, ItemStack stack, Here here, List<Element> out) {
		Map<Identifier, LootDrop> mobs = new LinkedHashMap<>();
		for (LootDrop drop : knowledge.loot(stack)) {
			if (!drop.kind().equals("entity")) continue;
			Identifier entity = Knowledge.entityOf(drop.table());
			LootDrop existing = mobs.get(entity);
			if (existing == null || existing.chance() < drop.chance()) mobs.put(entity, drop);
		}
		if (mobs.isEmpty()) return false;
		out.add(Elements.heading(t("wtfi.where.mobs")));
		for (var entry : mobs.entrySet()) {
			Identifier entity = entry.getKey();
			List<Component> details = new ArrayList<>(Sources.lootDetails(entry.getValue()));
			List<SpawnInfo> spawns = knowledge.spawns(entity);
			if (spawns.isEmpty()) {
				details.add(t("wtfi.where.no_natural_spawn").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				Set<Identifier> biomes = new LinkedHashSet<>();
				for (SpawnInfo spawn : spawns) biomes.addAll(spawn.biomes());
				List<Identifier> list = List.copyOf(biomes);
				Identifier dimension = list.isEmpty() ? null : knowledge.dimensionOf(list.getFirst());
				details.add(t("wtfi.where.spawns_in", Sources.biomeSummary(knowledge, list, dimension, 4)));
				if (here.biome != null) {
					boolean inBiome = biomes.contains(here.biome);
					details.add(t(inBiome ? "wtfi.where.mob_here_yes" : "wtfi.where.mob_here_no").withStyle(inBiome ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_GRAY));
				}
			}
			out.add(Elements.source(List.of(Sources.entityIcon(entity)), Sources.entityName(entity), details, null));
		}
		return true;
	}

	static Component direction(BlockPos from, BlockPos to) {
		return Sources.direction(from, to);
	}

	static Component ago(long epochMillis) {
		long minutes = Math.max(0, (Util.getEpochMillis() - epochMillis) / 60000);
		if (minutes < 1) return t("wtfi.time.now");
		if (minutes < 60) return t("wtfi.time.minutes", minutes);
		long hours = minutes / 60;
		if (hours < 48) return t("wtfi.time.hours", hours);
		return t("wtfi.time.days", hours / 24);
	}
}
