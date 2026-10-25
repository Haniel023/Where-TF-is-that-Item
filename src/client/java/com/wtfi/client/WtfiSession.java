package com.wtfi.client;

import com.wtfi.Wtfi;
import com.wtfi.client.config.WtfiConfig;
import com.wtfi.client.index.Category;
import com.wtfi.client.index.IndexEntry;
import com.wtfi.client.index.ItemIndex;
import com.wtfi.client.knowledge.ItemNotes;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.knowledge.RecipeView;
import com.wtfi.client.memory.SeenItems;
import com.wtfi.client.memory.StorageMemory;
import com.wtfi.data.DatasetIO;
import com.wtfi.data.WtfiDataset;
import com.wtfi.net.WtfiNetworking;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Everything WTFI holds for the current world/server connection. Created on join, dropped on
 * disconnect. All methods run on the render thread unless noted.
 */
public final class WtfiSession {
	private static @Nullable WtfiSession current;

	private final Minecraft minecraft;
	private final ItemIndex index;
	private final ItemNotes notes;
	private final SeenItems seen;
	private final @Nullable StorageMemory storage;
	private final boolean serverHasWtfi;
	private @Nullable Knowledge knowledge;
	private int knowledgeVersion;
	private Set<Item> craftable = Set.of();
	private long craftableAt;
	private int craftableVersion;
	private int ticks;

	private WtfiSession(Minecraft minecraft) {
		this.minecraft = minecraft;
		this.index = ItemIndex.build(minecraft);
		this.notes = ItemNotes.load(minecraft);
		this.seen = SeenItems.load();
		this.storage = WtfiConfig.get().storageMemory ? StorageMemory.open(minecraft) : null;
		// wtfi.forceBundled=true pretends the server is vanilla (used to test the fallback).
		this.serverHasWtfi = ClientPlayNetworking.canSend(WtfiNetworking.GivePayload.TYPE) && !Boolean.getBoolean("wtfi.forceBundled");
	}

	public static @Nullable WtfiSession get() {
		return current;
	}

	static void start(Minecraft minecraft) {
		stop();
		WtfiSession session = new WtfiSession(minecraft);
		current = session;
		if (!session.serverHasWtfi) {
			// No WTFI on the server: fall back to the vanilla data bundled in the jar.
			session.loadBundled();
		}
	}

	static void stop() {
		WtfiSession session = current;
		current = null;
		if (session != null) {
			session.seen.save();
			if (session.storage != null) session.storage.save();
		}
	}

	// ---- dataset ----

	void acceptDataset(byte[] bytes) {
		if (!this.serverHasWtfi) return;
		HolderLookup.Provider registries = this.minecraft.getConnection().registryAccess();
		CompletableFuture.supplyAsync(() -> {
			try {
				return Knowledge.build(DatasetIO.decode(bytes, registries), true, this.notes);
			} catch (Exception e) {
				Wtfi.LOGGER.error("WTFI could not read the server's dataset", e);
				return null;
			}
		}, Util.backgroundExecutor()).thenAcceptAsync(this::setKnowledge, this.minecraft);
	}

	private void loadBundled() {
		HolderLookup.Provider registries = this.minecraft.getConnection().registryAccess();
		CompletableFuture.supplyAsync(() -> {
			Optional<Path> path = FabricLoader.getInstance().getModContainer(Wtfi.MOD_ID).flatMap(c -> c.findPath("wtfi_data/vanilla_dataset.dat"));
			if (path.isEmpty()) {
				Wtfi.LOGGER.warn("WTFI: no bundled vanilla dataset; recipes and loot are unavailable on this server");
				return Knowledge.build(WtfiDataset.empty(), false, this.notes);
			}
			try (InputStream in = Files.newInputStream(path.get())) {
				return Knowledge.build(DatasetIO.decode(in, registries), false, this.notes);
			} catch (Exception e) {
				Wtfi.LOGGER.error("WTFI could not read its bundled dataset", e);
				return null;
			}
		}, Util.backgroundExecutor()).thenAcceptAsync(this::setKnowledge, this.minecraft);
	}

	private void setKnowledge(@Nullable Knowledge knowledge) {
		if (knowledge == null || current != this) return;
		this.knowledge = knowledge;
		this.knowledgeVersion++;
		for (IndexEntry entry : this.index.entries()) {
			int mask = entry.categoryMask & ~(Category.CREATIVE_ONLY.bit() | Category.RENEWABLE.bit() | Category.NON_RENEWABLE.bit());
			Item item = entry.stack.getItem();
			Knowledge.Obtainability o = knowledge.obtainability(item);
			if (o == Knowledge.Obtainability.CREATIVE_ONLY) mask |= Category.CREATIVE_ONLY.bit();
			Knowledge.Renewability r = knowledge.renewability(item);
			if (r.known()) mask |= r.renewable() ? Category.RENEWABLE.bit() : Category.NON_RENEWABLE.bit();
			entry.categoryMask = mask;
		}
		this.index.invalidateServerStats();
		Wtfi.LOGGER.info("WTFI knowledge ready: {} recipes ({})", knowledge.recipes.size(), knowledge.fromServer ? "from server" : "bundled vanilla data");
	}

	// ---- per tick ----

	void tick() {
		this.ticks++;
		if (this.storage != null && this.ticks % 100 == 0) this.storage.validate(this.minecraft);
		if (this.ticks % 1200 == 0) {
			this.seen.save();
			if (this.storage != null) this.storage.save();
		}
	}

	// ---- accessors ----

	public ItemIndex index() {
		return this.index;
	}

	public @Nullable Knowledge knowledge() {
		return this.knowledge;
	}

	public ItemNotes notes() {
		return this.notes;
	}

	public SeenItems seen() {
		return this.seen;
	}

	public @Nullable StorageMemory storage() {
		return this.storage;
	}

	public boolean serverHasWtfi() {
		return this.serverHasWtfi;
	}

	public int fuelTicks(ItemStack stack) {
		return this.knowledge == null ? 0 : this.knowledge.burnTicks(stack);
	}

	/** Changes whenever a live filter's answer may have changed. */
	public long dynamicVersion() {
		long storageVersion = this.storage == null ? 0 : this.storage.version();
		return this.knowledgeVersion * 31L * 31L * 31L + storageVersion * 31L * 31L + this.seen.version() * 31L + this.craftableVersion;
	}

	public boolean dynamicCategory(Category category, IndexEntry entry) {
		Item item = entry.stack.getItem();
		return switch (category) {
			case CRAFTABLE_NOW -> this.craftable().contains(item);
			case IN_STORAGE -> this.storage != null && this.storage.contains(item);
			case NEW -> this.seen.isNew(item);
			default -> entry.has(category);
		};
	}

	/** Items the player could craft right now from their inventory. Recomputed at most once a second. */
	public Set<Item> craftable() {
		long now = Util.getMillis();
		if (now - this.craftableAt < 1000 || this.knowledge == null || this.minecraft.player == null) return this.craftable;
		this.craftableAt = now;
		Map<Item, Integer> have = inventoryCounts(this.minecraft.player.getInventory());
		Set<Item> out = Collections.newSetFromMap(new IdentityHashMap<>());
		for (RecipeView recipe : this.knowledge.recipes) {
			String layout = recipe.layout();
			if (!layout.equals("shaped") && !layout.equals("shapeless") && !layout.equals("stonecutter") && !layout.equals("smithing")) continue;
			Item result = recipe.firstOutput().getItem();
			if (out.contains(result)) continue;
			if (canMake(recipe.inputs(), have)) out.add(result);
		}
		if (!out.equals(this.craftable)) {
			this.craftable = out;
			this.craftableVersion++;
		}
		return this.craftable;
	}

	public static Map<Item, Integer> inventoryCounts(Inventory inventory) {
		Map<Item, Integer> have = new IdentityHashMap<>();
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			if (!stack.isEmpty()) have.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}
		return have;
	}

	/** Greedy count-aware check: every slot takes one of its alternatives from what's left. */
	public static boolean canMake(List<List<ItemStack>> slots, Map<Item, Integer> have) {
		Map<Item, Integer> left = new IdentityHashMap<>(have);
		for (List<ItemStack> slot : slots) {
			if (slot.isEmpty()) continue;
			boolean found = false;
			for (ItemStack option : slot) {
				int need = Math.max(1, option.getCount());
				Integer count = left.get(option.getItem());
				if (count != null && count >= need) {
					left.put(option.getItem(), count - need);
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return true;
	}

	public static String itemKey(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
