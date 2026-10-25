package com.wtfi.client.enchant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

/**
 * Enchanting table odds, by running the game's own enchantment roll (EnchantmentHelper) many
 * times. Mods that add or reweight enchantments are therefore reflected automatically.
 */
public final class TableSimulator {
	public static final int MAX_LEVEL = 30;
	private static final int SAMPLES = 1500;
	private static final int COST_SAMPLES = 4000;
	private static final Map<String, CompletableFuture<Odds>> CACHE = new ConcurrentHashMap<>();

	private TableSimulator() {
	}

	/**
	 * For one item: chance[enchantment][enchantLevel][tableLevel] that a roll at that table level
	 * includes that enchantment at that level (or higher), plus which table levels each bookshelf
	 * count offers.
	 */
	public record Odds(
		List<Holder<Enchantment>> pool,
		Map<Holder<Enchantment>, float[][]> chance,
		float[][][] offers
	) {
		/** Chance that a roll at table level {@code tableLevel} gives {@code enchantment} at {@code minLevel}+. */
		public float at(Holder<Enchantment> enchantment, int minLevel, int tableLevel) {
			float[][] byLevel = this.chance.get(enchantment);
			if (byLevel == null || tableLevel < 1 || tableLevel > MAX_LEVEL) return 0;
			int lvl = Math.max(1, Math.min(byLevel.length - 1, minLevel));
			return byLevel[lvl][tableLevel];
		}

		/** Best single table level for this enchantment, and its chance. */
		public int bestLevel(Holder<Enchantment> enchantment, int minLevel) {
			int best = 0;
			float bestChance = 0;
			for (int l = 1; l <= MAX_LEVEL; l++) {
				float c = this.at(enchantment, minLevel, l);
				if (c > bestChance + 1e-4f) {
					bestChance = c;
					best = l;
				}
			}
			return best;
		}

		/**
		 * Chance that the given option button (0 = top, 2 = bottom) offers the enchantment with
		 * {@code bookshelves} around the table.
		 */
		public float withShelves(Holder<Enchantment> enchantment, int minLevel, int bookshelves, int option) {
			float[] dist = this.offers[Math.max(0, Math.min(15, bookshelves))][option];
			float total = 0;
			for (int l = 1; l <= MAX_LEVEL; l++) total += dist[l] * this.at(enchantment, minLevel, l);
			return total;
		}
	}

	public static CompletableFuture<Odds> odds(ItemStack stack, HolderLookup.Provider registries) {
		String key = stack.getItem() + "|" + stack.getComponentsPatch();
		return CACHE.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> compute(stack.copyWithCount(1), registries), Util.backgroundExecutor()));
	}

	public static void clearCache() {
		CACHE.clear();
	}

	private static Odds compute(ItemStack stack, HolderLookup.Provider registries) {
		HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
		List<Holder<Enchantment>> pool = new ArrayList<>();
		lookup.get(EnchantmentTags.IN_ENCHANTING_TABLE).ifPresent(set -> set.forEach(pool::add));
		RandomSource random = RandomSource.create(0x5EED_57A7L);

		Map<Holder<Enchantment>, int[][]> counts = new HashMap<>();
		for (Holder<Enchantment> holder : pool) counts.put(holder, new int[holder.value().getMaxLevel() + 2][MAX_LEVEL + 1]);
		for (int level = 1; level <= MAX_LEVEL; level++) {
			for (int s = 0; s < SAMPLES; s++) {
				List<EnchantmentInstance> rolled = EnchantmentHelper.selectEnchantment(random, stack, level, pool.stream());
				for (EnchantmentInstance instance : rolled) {
					int[][] c = counts.get(instance.enchantment());
					if (c == null) continue;
					// Count toward every level up to the rolled one, so [n] means "level n or better".
					for (int l = 1; l <= Math.min(instance.level(), c.length - 1); l++) c[l][level]++;
				}
			}
		}
		Map<Holder<Enchantment>, float[][]> chance = new HashMap<>();
		counts.forEach((holder, c) -> {
			float[][] f = new float[c.length][MAX_LEVEL + 1];
			for (int l = 0; l < c.length; l++) for (int t = 1; t <= MAX_LEVEL; t++) f[l][t] = c[l][t] / (float)SAMPLES;
			chance.put(holder, f);
		});

		float[][][] offers = new float[16][3][MAX_LEVEL + 1];
		for (int shelves = 0; shelves <= 15; shelves++) {
			for (int s = 0; s < COST_SAMPLES; s++) {
				for (int option = 0; option < 3; option++) {
					int cost = EnchantmentHelper.getEnchantmentCost(random, option, shelves, stack);
					if (cost >= 1 && cost <= MAX_LEVEL) offers[shelves][option][cost] += 1f / COST_SAMPLES;
				}
			}
		}
		return new Odds(pool, chance, offers);
	}
}
