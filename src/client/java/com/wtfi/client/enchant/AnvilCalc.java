package com.wtfi.client.enchant;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The anvil's cost formula, ported line by line from vanilla's AnvilMenu#createResult (26.3) so
 * it can run off-thread and thousands of times for the order optimizer. Renaming isn't modelled.
 */
public final class AnvilCalc {
	public static final int TOO_EXPENSIVE = 40;

	private AnvilCalc() {
	}

	public record Result(ItemStack result, int cost, boolean tooExpensive, boolean valid) {
		static final Result INVALID = new Result(ItemStack.EMPTY, 0, false, false);
	}

	public static Result combine(ItemStack input, ItemStack addition, boolean creative) {
		if (input.isEmpty() || !EnchantmentHelper.canStoreEnchantments(input)) return Result.INVALID;
		ItemStack result = input.copy();
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting(result));
		long tax = (long)input.getOrDefault(DataComponents.REPAIR_COST, 0) + addition.getOrDefault(DataComponents.REPAIR_COST, 0);
		int price = 0;

		if (!addition.isEmpty()) {
			boolean usingBook = addition.has(DataComponents.STORED_ENCHANTMENTS);
			if (result.isDamageableItem() && input.isValidRepairItem(addition)) {
				int repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
				if (repairAmount <= 0) return Result.INVALID;
				int count;
				for (count = 0; repairAmount > 0 && count < addition.getCount(); count++) {
					result.setDamageValue(result.getDamageValue() - repairAmount);
					price++;
					repairAmount = Math.min(result.getDamageValue(), result.getMaxDamage() / 4);
				}
			} else {
				if (!usingBook && (!result.is(addition.getItem()) || !result.isDamageableItem())) return Result.INVALID;
				if (result.isDamageableItem() && !usingBook) {
					int remaining1 = input.getMaxDamage() - input.getDamageValue();
					int remaining2 = addition.getMaxDamage() - addition.getDamageValue();
					int additional = remaining2 + result.getMaxDamage() * 12 / 100;
					int resultDamage = Math.max(0, result.getMaxDamage() - (remaining1 + additional));
					if (resultDamage < result.getDamageValue()) {
						result.setDamageValue(resultDamage);
						price += 2;
					}
				}
				boolean anyCompatible = false;
				boolean anyIncompatible = false;
				for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(addition).entrySet()) {
					Holder<Enchantment> holder = entry.getKey();
					int current = enchantments.getLevel(holder);
					int level = entry.getIntValue();
					level = current == level ? level + 1 : Math.max(level, current);
					Enchantment enchantment = holder.value();
					boolean compatible = enchantment.canEnchant(input);
					if (creative || input.is(Items.ENCHANTED_BOOK)) compatible = true;
					for (Holder<Enchantment> other : enchantments.keySet()) {
						if (!other.equals(holder) && !Enchantment.areCompatible(holder, other)) {
							compatible = false;
							price++;
						}
					}
					if (!compatible) {
						anyIncompatible = true;
					} else {
						anyCompatible = true;
						level = Math.min(level, enchantment.getMaxLevel());
						enchantments.set(holder, level);
						int fee = enchantment.getAnvilCost();
						if (usingBook) fee = Math.max(1, fee / 2);
						price += fee * level;
						if (input.getCount() > 1) price = TOO_EXPENSIVE;
					}
				}
				if (anyIncompatible && !anyCompatible) return Result.INVALID;
			}
		}

		if (price <= 0) return Result.INVALID;
		int finalPrice = (int)Math.min(Integer.MAX_VALUE, tax + price);
		int baseCost = Math.max(result.getOrDefault(DataComponents.REPAIR_COST, 0), addition.getOrDefault(DataComponents.REPAIR_COST, 0));
		result.set(DataComponents.REPAIR_COST, (int)Math.min(baseCost * 2L + 1L, Integer.MAX_VALUE));
		EnchantmentHelper.setEnchantments(result, enchantments.toImmutable());
		return new Result(result, finalPrice, finalPrice >= TOO_EXPENSIVE && !creative, true);
	}

	/** How many more anvil operations an item can take before its prior-work penalty alone hits 39. */
	public static int usesLeft(ItemStack stack) {
		int cost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
		int uses = 0;
		while (cost < TOO_EXPENSIVE - 1 && uses < 10) {
			cost = cost * 2 + 1;
			if (cost >= TOO_EXPENSIVE) break;
			uses++;
		}
		return uses;
	}

	/** Number of anvil operations already done to this item (from its prior-work penalty). */
	public static int priorWork(ItemStack stack) {
		int cost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
		int n = 0;
		while (cost > 0) {
			cost = (cost - 1) / 2;
			n++;
		}
		return n;
	}

	/** Grindstone XP (in points) for removing this stack's enchantments: {min, max}. */
	public static int[] grindstoneXp(ItemStack stack) {
		int amount = 0;
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
			if (!entry.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE)) {
				amount += entry.getKey().value().getMinCost(entry.getIntValue());
			}
		}
		if (amount <= 0) return new int[] {0, 0};
		int half = (int)Math.ceil(amount / 2.0);
		return new int[] {half, half + half - 1};
	}
}
