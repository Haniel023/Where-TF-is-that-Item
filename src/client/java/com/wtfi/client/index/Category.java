package com.wtfi.client.index;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Library filters. TYPE filters are combined with OR (show blocks or tools); STATUS filters are
 * combined with AND (creative-only and new). Bits up to {@link #STATIC_BITS} are stored per entry,
 * the rest are evaluated live because they depend on the player's inventory or memory.
 */
public enum Category {
	BLOCKS(Group.TYPE, Items.GRASS_BLOCK),
	TOOLS(Group.TYPE, Items.IRON_PICKAXE),
	WEAPONS(Group.TYPE, Items.IRON_SWORD),
	ARMOR(Group.TYPE, Items.IRON_CHESTPLATE),
	FOOD(Group.TYPE, Items.APPLE),
	POTIONS(Group.TYPE, Items.POTION),
	ENCHANTED_BOOKS(Group.TYPE, Items.ENCHANTED_BOOK),
	REDSTONE(Group.TYPE, Items.REDSTONE),
	SPAWN_EGGS(Group.TYPE, Items.PIG_SPAWN_EGG),
	CREATIVE_ONLY(Group.STATUS, Items.COMMAND_BLOCK),
	NON_RENEWABLE(Group.STATUS, Items.ANCIENT_DEBRIS),
	RENEWABLE(Group.STATUS, Items.WHEAT),
	CRAFTABLE_NOW(Group.STATUS, Items.CRAFTING_TABLE),
	IN_STORAGE(Group.STATUS, Items.CHEST),
	NEW(Group.STATUS, Items.NETHER_STAR);

	/** Categories with ordinal below this are stored as bits on each entry. */
	public static final int STATIC_BITS = RENEWABLE.ordinal() + 1;

	public final Group group;
	private final Item icon;

	Category(Group group, Item icon) {
		this.group = group;
		this.icon = icon;
	}

	public int bit() {
		return 1 << this.ordinal();
	}

	public boolean isStatic() {
		return this.ordinal() < STATIC_BITS;
	}

	public ItemStack icon() {
		return new ItemStack(this.icon);
	}

	public Component displayName() {
		return Component.translatable("wtfi.category." + this.name().toLowerCase());
	}

	public enum Group {
		TYPE,
		STATUS
	}
}
