package com.wtfi.client.knowledge;

import com.wtfi.data.WtfiDataset;
import com.wtfi.data.WtfiDataset.AdvancementInfo;
import com.wtfi.data.WtfiDataset.GenInfo;
import com.wtfi.data.WtfiDataset.ItemProps;
import com.wtfi.data.WtfiDataset.LootDrop;
import com.wtfi.data.WtfiDataset.SpawnInfo;
import com.wtfi.data.WtfiDataset.StructureInfo;
import com.wtfi.data.WtfiDataset.TradeInfo;
import com.wtfi.data.WtfiDataset.TransformInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

/**
 * The dataset turned into lookups: what makes an item, what it's used in, where it drops, who
 * sells it, where it generates, and whether it's obtainable/renewable in survival.
 */
public final class Knowledge {
	/** Mobs whose drops can't be farmed because the mob doesn't come back. */
	private static final Set<Identifier> ONE_TIME_MOBS = Set.of(
		Identifier.withDefaultNamespace("ender_dragon"),
		Identifier.withDefaultNamespace("elder_guardian")
	);
	private static final Set<String> RENEWABLE_LOOT = Set.of(
		"entity", "fishing", "barter", "hero_gift", "gift", "sniffer", "gameplay", "shearing", "brush", "harvest", "carve",
		"charged_creeper", "trial_spawner", "equipment"
	);

	public final WtfiDataset data;
	public final boolean fromServer;
	public final List<RecipeView> recipes = new ArrayList<>();
	private final Map<Item, List<RecipeView>> producedBy = new IdentityHashMap<>();
	private final Map<Item, List<RecipeView>> usedIn = new IdentityHashMap<>();
	private final Map<Item, List<LootDrop>> loot = new IdentityHashMap<>();
	private final Map<Identifier, List<LootDrop>> lootByTable = new HashMap<>();
	private final Map<Item, List<TradeInfo>> tradesGiving = new IdentityHashMap<>();
	private final Map<Item, List<TradeInfo>> tradesWanting = new IdentityHashMap<>();
	private final Map<Item, List<GenInfo>> generation = new IdentityHashMap<>();
	private final Map<Identifier, List<GenInfo>> genByBlock = new HashMap<>();
	private final Map<Identifier, StructureInfo> structures = new HashMap<>();
	private final Map<Identifier, List<SpawnInfo>> spawns = new HashMap<>();
	private final Map<Item, List<AdvancementInfo>> advancements = new IdentityHashMap<>();
	private final Map<Item, ItemProps> props = new IdentityHashMap<>();
	private final Map<Item, List<TransformInfo>> transformsFrom = new IdentityHashMap<>();
	private final Map<Item, List<TransformInfo>> transformsTo = new IdentityHashMap<>();
	private final Map<Item, Obtainability> obtainability = new IdentityHashMap<>();
	private final Map<Item, Renewability> renewability = new IdentityHashMap<>();
	private final Map<Identifier, Integer> dimensionBiomes = new HashMap<>();
	private final Map<Identifier, Identifier> biomeDimension = new HashMap<>();
	private final ItemNotes notes;

	private Knowledge(WtfiDataset data, boolean fromServer, ItemNotes notes) {
		this.data = data;
		this.fromServer = fromServer;
		this.notes = notes;
	}

	/** Safe to call off the render thread: only reads registries, tags and the dataset. */
	public static Knowledge build(WtfiDataset data, boolean fromServer, ItemNotes notes) {
		Knowledge k = new Knowledge(data, fromServer, notes);
		for (var info : data.recipes()) {
			RecipeView view = RecipeView.of(info);
			if (view.outputs().isEmpty()) continue;
			k.recipes.add(view);
			Set<Item> outItems = Collections.newSetFromMap(new IdentityHashMap<>());
			for (ItemStack out : view.outputs()) outItems.add(out.getItem());
			for (Item item : outItems) k.producedBy.computeIfAbsent(item, x -> new ArrayList<>()).add(view);
			Set<Item> inItems = Collections.newSetFromMap(new IdentityHashMap<>());
			for (List<ItemStack> slot : view.inputs()) for (ItemStack stack : slot) inItems.add(stack.getItem());
			for (Item item : inItems) k.usedIn.computeIfAbsent(item, x -> new ArrayList<>()).add(view);
		}
		for (LootDrop drop : data.loot()) {
			k.loot.computeIfAbsent(drop.stack().getItem(), x -> new ArrayList<>()).add(drop);
			k.lootByTable.computeIfAbsent(drop.table(), x -> new ArrayList<>()).add(drop);
		}
		for (TradeInfo trade : data.trades()) {
			k.tradesGiving.computeIfAbsent(trade.gives().getItem(), x -> new ArrayList<>()).add(trade);
			for (ItemStack want : trade.wants()) k.tradesWanting.computeIfAbsent(want.getItem(), x -> new ArrayList<>()).add(trade);
		}
		for (GenInfo gen : data.worldgen()) {
			Block block = BuiltInRegistries.BLOCK.getValue(gen.block());
			Item item = block.asItem();
			if (item != Items.AIR) k.generation.computeIfAbsent(item, x -> new ArrayList<>()).add(gen);
			k.genByBlock.computeIfAbsent(gen.block(), x -> new ArrayList<>()).add(gen);
			if (gen.featureType().equals("terrain")) {
				k.dimensionBiomes.merge(gen.dimension(), gen.biomes().size(), Math::max);
				for (Identifier biome : gen.biomes()) k.biomeDimension.put(biome, gen.dimension());
			}
		}
		for (StructureInfo structure : data.structures()) k.structures.put(structure.id(), structure);
		for (SpawnInfo spawn : data.spawns()) k.spawns.computeIfAbsent(spawn.entity(), x -> new ArrayList<>()).add(spawn);
		for (AdvancementInfo advancement : data.advancements()) {
			Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Identifier id : advancement.items()) items.add(BuiltInRegistries.ITEM.getValue(id));
			for (var tag : advancement.itemTags()) for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) items.add(h.value());
			items.remove(Items.AIR);
			for (Item item : items) k.advancements.computeIfAbsent(item, x -> new ArrayList<>()).add(advancement);
		}
		for (ItemProps p : data.itemProps()) k.props.put(BuiltInRegistries.ITEM.getValue(p.item()), p);
		for (TransformInfo t : data.transforms()) {
			Item from = BuiltInRegistries.BLOCK.getValue(t.from()).asItem();
			Item to = BuiltInRegistries.BLOCK.getValue(t.to()).asItem();
			if (from != Items.AIR) k.transformsFrom.computeIfAbsent(from, x -> new ArrayList<>()).add(t);
			if (to != Items.AIR) k.transformsTo.computeIfAbsent(to, x -> new ArrayList<>()).add(t);
		}
		k.classify();
		return k;
	}

	// ---- lookups ----

	public List<RecipeView> producedBy(ItemStack stack) {
		return filterVariant(this.producedBy.getOrDefault(stack.getItem(), List.of()), stack, true);
	}

	public List<RecipeView> usedIn(ItemStack stack) {
		return filterVariant(this.usedIn.getOrDefault(stack.getItem(), List.of()), stack, false);
	}

	public List<RecipeView> producedBy(Item item) {
		return this.producedBy.getOrDefault(item, List.of());
	}

	public List<RecipeView> usedIn(Item item) {
		return this.usedIn.getOrDefault(item, List.of());
	}

	/** Recipes whose output (or an input) matches this exact variant, e.g. one specific potion. */
	private static List<RecipeView> filterVariant(List<RecipeView> recipes, ItemStack stack, boolean outputs) {
		if (stack.getComponentsPatch().isEmpty()) return recipes;
		List<RecipeView> out = new ArrayList<>();
		for (RecipeView recipe : recipes) {
			if (outputs) {
				for (ItemStack o : recipe.outputs()) {
					if (variantMatches(o, stack)) {
						out.add(recipe);
						break;
					}
				}
			} else {
				outer:
				for (List<ItemStack> slot : recipe.inputs()) {
					for (ItemStack s : slot) {
						if (variantMatches(s, stack)) {
							out.add(recipe);
							break outer;
						}
					}
				}
			}
		}
		return out;
	}

	public static boolean variantMatches(ItemStack candidate, ItemStack page) {
		if (!candidate.is(page.getItem())) return false;
		if (page.getComponentsPatch().isEmpty() || candidate.getComponentsPatch().isEmpty()) return true;
		return ItemStack.isSameItemSameComponents(candidate, page);
	}

	public List<LootDrop> loot(ItemStack stack) {
		List<LootDrop> drops = this.loot.getOrDefault(stack.getItem(), List.of());
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) {
			// An enchanted book page: only sources that can roll this enchantment.
			Set<Identifier> wanted = enchantmentIds(stored);
			return drops.stream().filter(d -> d.enchantments().isEmpty() || d.enchantments().stream().anyMatch(wanted::contains)).toList();
		}
		if (!stack.getComponentsPatch().isEmpty()) {
			return drops.stream().filter(d -> variantMatches(d.stack(), stack)).toList();
		}
		return drops;
	}

	public List<LootDrop> lootTable(Identifier table) {
		return this.lootByTable.getOrDefault(table, List.of());
	}

	public List<TradeInfo> tradesGiving(ItemStack stack) {
		List<TradeInfo> trades = this.tradesGiving.getOrDefault(stack.getItem(), List.of());
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) {
			Set<Identifier> wanted = enchantmentIds(stored);
			return trades.stream().filter(t -> t.enchantments().isEmpty() || t.enchantments().stream().anyMatch(wanted::contains)).toList();
		}
		if (!stack.getComponentsPatch().isEmpty()) {
			return trades.stream().filter(t -> variantMatches(t.gives(), stack)).toList();
		}
		return trades;
	}

	public List<TradeInfo> tradesWanting(Item item) {
		return this.tradesWanting.getOrDefault(item, List.of());
	}

	public List<GenInfo> generation(Item item) {
		return this.generation.getOrDefault(item, List.of());
	}

	/** A generated block that gives this item, either as itself or as a drop (diamond ore → diamond). */
	public record GenSource(GenInfo gen, ItemStack block, boolean direct) {
	}

	public List<GenSource> generationSources(Item item) {
		List<GenSource> out = new ArrayList<>();
		for (GenInfo gen : this.generation(item)) out.add(new GenSource(gen, new ItemStack(item), true));
		for (LootDrop drop : this.loot.getOrDefault(item, List.of())) {
			if (!drop.kind().equals("block")) continue;
			String path = drop.table().getPath();
			Identifier blockId = Identifier.fromNamespaceAndPath(drop.table().getNamespace(), path.substring(path.indexOf('/') + 1));
			for (GenInfo gen : this.genByBlock.getOrDefault(blockId, List.of())) {
				Item blockItem = BuiltInRegistries.BLOCK.getValue(blockId).asItem();
				if (blockItem == item) continue;
				out.add(new GenSource(gen, blockItem == Items.AIR ? new ItemStack(item) : new ItemStack(blockItem), false));
			}
		}
		return out;
	}

	public StructureInfo structure(Identifier id) {
		return this.structures.get(id);
	}

	public java.util.Collection<StructureInfo> structures() {
		return this.structures.values();
	}

	public List<SpawnInfo> spawns(Identifier entity) {
		return this.spawns.getOrDefault(entity, List.of());
	}

	/** How many biomes a dimension has (from its terrain entry), or 0 if unknown. */
	public int biomeCount(Identifier dimension) {
		return this.dimensionBiomes.getOrDefault(dimension, 0);
	}

	/** The dimension a biome belongs to, if known. */
	public Identifier dimensionOf(Identifier biome) {
		return this.biomeDimension.get(biome);
	}

	public List<AdvancementInfo> advancements(Item item) {
		return this.advancements.getOrDefault(item, List.of());
	}

	public ItemProps props(Item item) {
		return this.props.get(item);
	}

	public List<TransformInfo> transformsFrom(Item item) {
		return this.transformsFrom.getOrDefault(item, List.of());
	}

	public List<TransformInfo> transformsTo(Item item) {
		return this.transformsTo.getOrDefault(item, List.of());
	}

	public Obtainability obtainability(Item item) {
		return this.obtainability.getOrDefault(item, Obtainability.UNKNOWN);
	}

	public Renewability renewability(Item item) {
		return this.renewability.getOrDefault(item, Renewability.UNKNOWN_RESULT);
	}

	public int burnTicks(ItemStack stack) {
		ItemProps p = this.props.get(stack.getItem());
		return p == null ? 0 : p.burnTicks();
	}

	private static Set<Identifier> enchantmentIds(ItemEnchantments enchantments) {
		Set<Identifier> out = new LinkedHashSet<>();
		for (var holder : enchantments.keySet()) holder.unwrapKey().ifPresent(k -> out.add(k.identifier()));
		return out;
	}

	// ---- survival obtainability and renewability ----

	private void classify() {
		Map<Item, String> renewableReason = new IdentityHashMap<>();
		Set<Item> obtainable = this.obtainableItems();

		// Direct renewable sources.
		for (var entry : this.tradesGiving.entrySet()) renewableReason.putIfAbsent(entry.getKey(), "trade");
		for (var entry : this.loot.entrySet()) {
			for (LootDrop drop : entry.getValue()) {
				if (!RENEWABLE_LOOT.contains(drop.kind())) continue;
				if (drop.kind().equals("entity") && ONE_TIME_MOBS.contains(entityOf(drop.table()))) continue;
				renewableReason.putIfAbsent(entry.getKey(), "loot:" + drop.kind());
				break;
			}
		}
		for (Item item : BuiltInRegistries.ITEM) {
			if (this.notes.flaggedRenewable(BuiltInRegistries.ITEM.getKey(item))) renewableReason.put(item, "known");
		}

		// Spread renewability through recipes, block drops and tool interactions until stable.
		boolean changed = true;
		for (int round = 0; changed && round < 32; round++) {
			changed = false;
			for (RecipeView recipe : this.recipes) {
				Item out = recipe.firstOutput().getItem();
				if (renewableReason.containsKey(out) || recipe.ingredientSlots() == 0) continue;
				boolean all = true;
				for (List<ItemStack> slot : recipe.inputs()) {
					if (slot.isEmpty()) continue;
					boolean any = false;
					for (ItemStack s : slot) {
						if (renewableReason.containsKey(s.getItem())) {
							any = true;
							break;
						}
					}
					if (!any) {
						all = false;
						break;
					}
				}
				if (all) {
					for (ItemStack o : recipe.outputs()) {
						if (renewableReason.putIfAbsent(o.getItem(), "recipe:" + recipe.info().type().getPath()) == null) changed = true;
					}
				}
			}
			for (var entry : this.lootByTable.entrySet()) {
				Identifier table = entry.getKey();
				if (!table.getPath().startsWith("blocks/")) continue;
				Block block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(table.getNamespace(), table.getPath().substring(7)));
				if (!renewableReason.containsKey(block.asItem())) continue;
				for (LootDrop drop : entry.getValue()) {
					if (renewableReason.putIfAbsent(drop.stack().getItem(), "block_drop") == null) changed = true;
				}
			}
			for (var entry : this.transformsFrom.entrySet()) {
				if (!renewableReason.containsKey(entry.getKey())) continue;
				for (TransformInfo t : entry.getValue()) {
					Item to = BuiltInRegistries.BLOCK.getValue(t.to()).asItem();
					if (to != Items.AIR && renewableReason.putIfAbsent(to, "tool_interaction") == null) changed = true;
				}
			}
		}

		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) continue;
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			boolean vanilla = id.getNamespace().equals("minecraft");
			Obtainability o;
			if (this.notes.flaggedCreativeOnly(id)) {
				o = Obtainability.CREATIVE_ONLY;
			} else if (obtainable.contains(item)) {
				o = Obtainability.SURVIVAL;
			} else {
				// Vanilla data is complete (plus notes), so no source means creative only.
				// For mods we can't be sure: they may hand items out in code.
				o = vanilla ? Obtainability.CREATIVE_ONLY : Obtainability.UNKNOWN;
			}
			this.obtainability.put(item, o);

			Renewability r;
			if (o != Obtainability.SURVIVAL) {
				r = Renewability.UNKNOWN_RESULT;
			} else if (this.notes.flaggedNonRenewable(id)) {
				r = new Renewability(false, "known");
			} else if (renewableReason.containsKey(item)) {
				r = new Renewability(true, renewableReason.get(item));
			} else {
				r = new Renewability(false, "finite");
			}
			this.renewability.put(item, r);
		}
	}

	/**
	 * Items reachable in survival. Block drops only count once the block itself can exist (it
	 * generates, or its item is obtainable and can be placed), so e.g. a petrified oak slab,
	 * which drops itself but never generates, stays creative-only.
	 */
	private Set<Item> obtainableItems() {
		Set<Item> obtainable = Collections.newSetFromMap(new IdentityHashMap<>());
		// Generated blocks don't count by themselves (bedrock generates but can't be mined): their
		// item is only obtainable if the block's loot table drops it, handled below.
		Set<Identifier> generatedBlocks = new java.util.HashSet<>();
		for (GenInfo gen : this.data.worldgen()) generatedBlocks.add(gen.block());
		obtainable.addAll(this.tradesGiving.keySet());
		for (var entry : this.lootByTable.entrySet()) {
			if (entry.getKey().getPath().startsWith("blocks/")) continue;
			for (LootDrop drop : entry.getValue()) obtainable.add(drop.stack().getItem());
		}
		for (Item item : BuiltInRegistries.ITEM) {
			if (this.notes.flaggedObtainable(BuiltInRegistries.ITEM.getKey(item))) obtainable.add(item);
		}

		boolean changed = true;
		for (int round = 0; changed && round < 32; round++) {
			changed = false;
			for (RecipeView recipe : this.recipes) {
				boolean all = true;
				for (List<ItemStack> slot : recipe.inputs()) {
					if (slot.isEmpty()) continue;
					boolean any = false;
					for (ItemStack s : slot) {
						if (obtainable.contains(s.getItem())) {
							any = true;
							break;
						}
					}
					if (!any) {
						all = false;
						break;
					}
				}
				if (!all) continue;
				for (ItemStack o : recipe.outputs()) changed |= obtainable.add(o.getItem());
			}
			for (var entry : this.lootByTable.entrySet()) {
				Identifier table = entry.getKey();
				if (!table.getPath().startsWith("blocks/")) continue;
				Identifier blockId = Identifier.fromNamespaceAndPath(table.getNamespace(), table.getPath().substring(7));
				Block block = BuiltInRegistries.BLOCK.getValue(blockId);
				if (!generatedBlocks.contains(blockId) && !obtainable.contains(block.asItem())) continue;
				for (LootDrop drop : entry.getValue()) changed |= obtainable.add(drop.stack().getItem());
			}
			for (TransformInfo t : this.data.transforms()) {
				Item from = BuiltInRegistries.BLOCK.getValue(t.from()).asItem();
				if (!generatedBlocks.contains(t.from()) && !obtainable.contains(from)) continue;
				Item to = BuiltInRegistries.BLOCK.getValue(t.to()).asItem();
				if (to != Items.AIR) changed |= obtainable.add(to);
			}
		}
		return obtainable;
	}

	public static Identifier entityOf(Identifier table) {
		String path = table.getPath();
		if (!path.startsWith("entities/")) return table;
		String rest = path.substring("entities/".length());
		int slash = rest.indexOf('/');
		return Identifier.fromNamespaceAndPath(table.getNamespace(), slash < 0 ? rest : rest.substring(0, slash));
	}

	public enum Obtainability {
		SURVIVAL,
		CREATIVE_ONLY,
		UNKNOWN
	}

	/** Whether an item can be farmed forever. Reason is a short key shown in the tooltip. */
	public record Renewability(boolean renewable, String reason) {
		public static final Renewability UNKNOWN_RESULT = new Renewability(false, "unknown");

		public boolean known() {
			return !this.reason.equals("unknown");
		}
	}
}
