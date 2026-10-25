package com.wtfi.client.index;

import com.wtfi.Wtfi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;

/**
 * Every stack shown in the library: the creative tab contents (so mods' variants show up) plus
 * any registered item no tab lists. Built once per world join on the render thread; names are
 * filled in on a worker thread right after.
 */
public final class ItemIndex {
	private final IndexEntry[] entries;
	private final Map<Item, List<IndexEntry>> byItem;
	private final List<String> namespaces;
	private final EnumMap<SortMode, float[]> statCache = new EnumMap<>(SortMode.class);
	private final Map<IndexEntry, ItemStats> stats = new IdentityHashMap<>();
	private volatile boolean namesReady;
	private volatile boolean tagsReady;
	private volatile boolean tooltipsReady;
	private int tooltipCursor;
	private String language;

	private ItemIndex(IndexEntry[] entries) {
		this.entries = entries;
		this.byItem = new IdentityHashMap<>();
		for (IndexEntry entry : entries) {
			this.byItem.computeIfAbsent(entry.stack.getItem(), k -> new ArrayList<>(1)).add(entry);
		}
		this.namespaces = Arrays.stream(entries).map(IndexEntry::namespace).distinct().sorted(Comparator.comparing((String ns) -> !ns.equals("minecraft")).thenComparing(ns -> ns)).toList();
	}

	public static ItemIndex build(Minecraft minecraft) {
		long start = Util.getMillis();
		ClientPacketListener connection = minecraft.getConnection();
		FeatureFlagSet features = connection.enabledFeatures();
		boolean permissions = minecraft.player.canUseGameMasterBlocks() && minecraft.options.operatorItemsTab().get();
		if (CreativeModeTabs.tryRebuildTabContents(features, permissions, connection.registryAccess())) {
			// Same follow-up the creative screen does, so its search keeps working after we rebuilt the tabs.
			List<ItemStack> searchItems = List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
			connection.searchTrees().updateCreativeTooltips(connection.registryAccess(), searchItems);
			connection.searchTrees().updateCreativeTags(searchItems);
		}

		Set<ItemStack> ordered = ItemStackLinkedSet.createTypeAndComponentsSet();
		ordered.addAll(CreativeModeTabs.searchTab().getDisplayItems());
		Map<Item, Integer> tabBitsByItem = new IdentityHashMap<>();
		for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
			if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
			Collection<ItemStack> items = tab.getDisplayItems();
			ordered.addAll(items);
			int bit = tabBit(tab);
			if (bit != 0) {
				for (ItemStack stack : items) {
					tabBitsByItem.merge(stack.getItem(), bit, (a, b) -> a | b);
				}
			}
		}

		Set<Item> present = ordered.stream().map(ItemStack::getItem).collect(Collectors.toCollection(() -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())));
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR || present.contains(item) || !item.isEnabled(features)) continue;
			ordered.add(new ItemStack(item));
		}

		IndexEntry[] entries = new IndexEntry[ordered.size()];
		int i = 0;
		for (ItemStack stack : ordered) {
			ItemStack copy = stack.copyWithCount(1);
			Identifier id = BuiltInRegistries.ITEM.getKey(copy.getItem());
			IndexEntry entry = new IndexEntry(i, copy, id, ModNames.of(id.getNamespace()));
			entry.categoryMask = typeMask(copy) | tabBitsByItem.getOrDefault(copy.getItem(), 0);
			entries[i++] = entry;
		}

		ItemIndex index = new ItemIndex(entries);
		index.fillNames(minecraft);
		Wtfi.LOGGER.info("WTFI indexed {} stacks in {} ms", entries.length, Util.getMillis() - start);
		return index;
	}

	private static int tabBit(CreativeModeTab tab) {
		var key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElse(null);
		if (key == null) return 0;
		if (key.equals(CreativeModeTabs.REDSTONE_BLOCKS)) return Category.REDSTONE.bit();
		if (key.equals(CreativeModeTabs.SPAWN_EGGS)) return Category.SPAWN_EGGS.bit();
		return 0;
	}

	private static int typeMask(ItemStack stack) {
		int mask = 0;
		if (stack.getItem() instanceof BlockItem) mask |= Category.BLOCKS.bit();
		if (stack.has(DataComponents.TOOL)) mask |= Category.TOOLS.bit();
		if (stack.has(DataComponents.WEAPON) || ItemStats.of(stack).isWeapon()) mask |= Category.WEAPONS.bit();
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable != null && equippable.slot().isArmor()) mask |= Category.ARMOR.bit();
		if (stack.has(DataComponents.FOOD)) mask |= Category.FOOD.bit();
		if (stack.has(DataComponents.POTION_CONTENTS)) mask |= Category.POTIONS.bit();
		if (stack.has(DataComponents.STORED_ENCHANTMENTS)) mask |= Category.ENCHANTED_BOOKS.bit();
		return mask;
	}

	/** Fills display names off-thread; searches fall back to ids until this finishes. */
	private void fillNames(Minecraft minecraft) {
		this.language = minecraft.options.languageCode;
		this.namesReady = false;
		CompletableFuture.runAsync(() -> {
			for (IndexEntry entry : this.entries) {
				entry.nameLower = entry.stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			}
			this.namesReady = true;
			this.statCache.remove(SortMode.NAME);
		}, Util.backgroundExecutor());
	}

	/** Call when the panel opens; refreshes names if the player switched language. */
	public void checkLanguage(Minecraft minecraft) {
		if (!minecraft.options.languageCode.equals(this.language)) {
			this.fillNames(minecraft);
			this.tooltipsReady = false;
			this.tooltipCursor = 0;
		}
	}

	public boolean namesReady() {
		return this.namesReady;
	}

	public void ensureTags() {
		if (this.tagsReady) return;
		for (IndexEntry entry : this.entries) {
			entry.tagsLower = entry.stack.tags().map(t -> t.location().toString()).collect(Collectors.joining(" "));
		}
		this.tagsReady = true;
	}

	/** Builds tooltip text a slice per call so a '$' search never freezes a frame. Returns true when done. */
	public boolean stepTooltips(Minecraft minecraft, int budget) {
		if (this.tooltipsReady || minecraft.level == null) return this.tooltipsReady;
		TooltipContext context = TooltipContext.of(minecraft.level);
		int end = Math.min(this.entries.length, this.tooltipCursor + budget);
		for (int i = this.tooltipCursor; i < end; i++) {
			IndexEntry entry = this.entries[i];
			try {
				List<Component> lines = entry.stack.getTooltipLines(context, minecraft.player, TooltipFlag.NORMAL);
				entry.tooltipLower = lines.stream().map(Component::getString).collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
			} catch (RuntimeException e) {
				entry.tooltipLower = entry.nameLower;
			}
		}
		this.tooltipCursor = end;
		this.tooltipsReady = end >= this.entries.length;
		return this.tooltipsReady;
	}

	public IndexEntry[] entries() {
		return this.entries;
	}

	public int size() {
		return this.entries.length;
	}

	public IndexEntry get(int ordinal) {
		return this.entries[ordinal];
	}

	public List<IndexEntry> byItem(Item item) {
		return this.byItem.getOrDefault(item, List.of());
	}

	/** The library entry for this exact stack (same components), or the item's first entry. */
	public IndexEntry find(ItemStack stack) {
		List<IndexEntry> candidates = this.byItem(stack.getItem());
		for (IndexEntry entry : candidates) {
			if (ItemStack.isSameItemSameComponents(entry.stack, stack)) return entry;
		}
		return candidates.isEmpty() ? null : candidates.getFirst();
	}

	public List<String> namespaces() {
		return this.namespaces;
	}

	public ItemStats stats(IndexEntry entry) {
		synchronized (this.stats) {
			return this.stats.computeIfAbsent(entry, e -> ItemStats.of(e.stack));
		}
	}

	/** Sort key per entry for stat sorts; bigger is better, 0 means "doesn't have this stat". */
	public float[] statValues(SortMode mode, java.util.function.ToIntFunction<ItemStack> fuelTicks) {
		return this.statCache.computeIfAbsent(mode, m -> {
			float[] values = new float[this.entries.length];
			for (IndexEntry entry : this.entries) {
				ItemStats s = this.stats(entry);
				values[entry.ordinal] = switch (m) {
					case ATTACK_DAMAGE -> (float)s.attackDamage();
					case DPS -> (float)s.dps();
					case ARMOR -> (float)(s.armor() + s.toughness() * 0.01);
					case DURABILITY -> s.durability();
					case NUTRITION -> s.nutrition();
					case SATURATION -> s.saturation();
					case FUEL -> fuelTicks.applyAsInt(entry.stack);
					case ENCHANTABILITY -> s.enchantability();
					default -> 0;
				};
			}
			return values;
		});
	}

	/** Forget cached stat sorts that depend on server data (fuel). */
	public void invalidateServerStats() {
		this.statCache.remove(SortMode.FUEL);
	}
}
