package com.wtfi.client.index;

import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** One stack in the library. Strings are pre-lowercased so searching is a plain contains(). */
public final class IndexEntry {
	public final int ordinal;
	public final ItemStack stack;
	public final Identifier id;
	public final String idLower;
	public String nameLower;
	public final String modName;
	public final String modLower;
	/** Space separated tag ids, computed on first '#' search. */
	public volatile String tagsLower;
	/** Tooltip text, computed on first '$' search. */
	public volatile String tooltipLower;
	/** Bits from {@link Category} with ordinal below {@link Category#STATIC_BITS}. */
	public int categoryMask;

	IndexEntry(int ordinal, ItemStack stack, Identifier id, String modName) {
		this.ordinal = ordinal;
		this.stack = stack;
		this.id = id;
		this.idLower = id.toString();
		this.modName = modName;
		this.modLower = (id.getNamespace() + " " + modName).toLowerCase(Locale.ROOT);
		this.nameLower = "";
	}

	public String namespace() {
		return this.id.getNamespace();
	}

	public boolean has(Category category) {
		return (this.categoryMask & category.bit()) != 0;
	}
}
