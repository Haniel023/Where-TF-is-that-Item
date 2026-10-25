package com.wtfi.client.index;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public enum SortMode {
	CREATIVE(Items.COMPASS, false),
	NAME(Items.NAME_TAG, false),
	MOD(Items.BOOKSHELF, false),
	ID(Items.PAPER, false),
	ATTACK_DAMAGE(Items.IRON_SWORD, true),
	DPS(Items.GOLDEN_SWORD, true),
	ARMOR(Items.IRON_CHESTPLATE, true),
	DURABILITY(Items.ANVIL, true),
	NUTRITION(Items.BREAD, true),
	SATURATION(Items.GOLDEN_CARROT, true),
	FUEL(Items.COAL, true),
	ENCHANTABILITY(Items.ENCHANTING_TABLE, true);

	private final Item icon;
	/** Stat sorts put the biggest numbers first and push items without the stat to the end. */
	public final boolean stat;

	SortMode(Item icon, boolean stat) {
		this.icon = icon;
		this.stat = stat;
	}

	public ItemStack icon() {
		return new ItemStack(this.icon);
	}

	public Component displayName() {
		return Component.translatable("wtfi.sort." + this.name().toLowerCase());
	}

	public static SortMode byName(String name) {
		for (SortMode mode : values()) {
			if (mode.name().equals(name)) return mode;
		}
		return CREATIVE;
	}
}
