package com.wtfi.client.index;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.config.WtfiConfig;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * The library's current result list: filters and sort produce a cached "base" order, then the
 * search query narrows it. Typing a longer query only rescans the previous results.
 */
public final class LibraryView {
	private int[] base = new int[0];
	private int[] results = new int[0];
	private SearchQuery query = SearchQuery.EMPTY;
	private String baseKey = "";
	private boolean pendingTooltips;

	public int[] results() {
		return this.results;
	}

	public SearchQuery query() {
		return this.query;
	}

	/** True while a '$' search is still indexing tooltips; call {@link #tick} until it clears. */
	public boolean waitingForTooltips() {
		return this.pendingTooltips;
	}

	public void tick(WtfiSession session, String text) {
		if (this.pendingTooltips && session.index().stepTooltips(Minecraft.getInstance(), 400)) {
			this.pendingTooltips = false;
			this.update(session, text, true);
		}
	}

	public void update(WtfiSession session, String text, boolean force) {
		ItemIndex index = session.index();
		WtfiConfig config = WtfiConfig.get();
		String key = config.filters + "|" + config.sort + "|" + config.sortDescending + "|" + config.modFilter + "|" + session.dynamicVersion() + "|" + index.namesReady() + "|" + index.size();
		boolean baseChanged = force || !key.equals(this.baseKey);
		if (baseChanged) {
			this.base = this.buildBase(session, config);
			this.baseKey = key;
		}

		SearchQuery next = SearchQuery.parse(text);
		if (next.usesTags()) index.ensureTags();
		if (next.usesTooltips() && !index.stepTooltips(Minecraft.getInstance(), 400)) {
			this.pendingTooltips = true;
		}

		int[] source = !baseChanged && next.narrows(this.query) ? this.results : this.base;
		if (next.isEmpty()) {
			this.results = this.base;
		} else {
			IntArrayList out = new IntArrayList(Math.min(source.length, 1024));
			IndexEntry[] entries = index.entries();
			for (int ordinal : source) {
				if (next.matches(entries[ordinal])) out.add(ordinal);
			}
			this.results = out.toIntArray();
		}
		this.query = next;
	}

	private int[] buildBase(WtfiSession session, WtfiConfig config) {
		ItemIndex index = session.index();
		Set<Category> selected = EnumSet.noneOf(Category.class);
		for (String name : config.filters) {
			try {
				selected.add(Category.valueOf(name));
			} catch (IllegalArgumentException ignored) {
			}
		}
		int typeMask = 0;
		int statusMask = 0;
		EnumSet<Category> dynamic = EnumSet.noneOf(Category.class);
		for (Category category : selected) {
			if (!category.isStatic()) {
				dynamic.add(category);
			} else if (category.group == Category.Group.TYPE) {
				typeMask |= category.bit();
			} else {
				statusMask |= category.bit();
			}
		}
		String mod = config.modFilter;

		IntArrayList out = new IntArrayList(index.size());
		for (IndexEntry entry : index.entries()) {
			if (!mod.isEmpty() && !entry.namespace().equals(mod)) continue;
			if (typeMask != 0 && (entry.categoryMask & typeMask) == 0) continue;
			if ((entry.categoryMask & statusMask) != statusMask) continue;
			boolean keep = true;
			for (Category category : dynamic) {
				if (!session.dynamicCategory(category, entry)) {
					keep = false;
					break;
				}
			}
			if (keep) out.add(entry.ordinal);
		}

		int[] ordinals = out.toIntArray();
		SortMode sort = SortMode.byName(config.sort);
		if (sort != SortMode.CREATIVE || config.sortDescending) {
			Integer[] boxed = Arrays.stream(ordinals).boxed().toArray(Integer[]::new);
			Arrays.sort(boxed, this.comparator(session, sort, config.sortDescending));
			ordinals = Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
		}
		return ordinals;
	}

	private Comparator<Integer> comparator(WtfiSession session, SortMode sort, boolean descending) {
		ItemIndex index = session.index();
		IndexEntry[] e = index.entries();
		Comparator<Integer> creative = Comparator.naturalOrder();
		Comparator<Integer> cmp = switch (sort) {
			case CREATIVE -> creative;
			case NAME -> Comparator.<Integer, String>comparing(i -> e[i].nameLower).thenComparing(creative);
			case MOD -> Comparator.<Integer, String>comparing(i -> e[i].namespace().equals("minecraft") ? "" : e[i].modLower).thenComparing(creative);
			case ID -> Comparator.<Integer, String>comparing(i -> e[i].idLower).thenComparing(creative);
			default -> {
				float[] values = index.statValues(sort, session::fuelTicks);
				// Biggest first; items without the stat (0) sink to the end either way.
				yield Comparator.<Integer>comparingInt(i -> values[i] > 0 ? 0 : 1)
					.thenComparing(Comparator.<Integer>comparingDouble(i -> values[i]).reversed())
					.thenComparing(creative);
			}
		};
		return descending ? cmp.reversed() : cmp;
	}
}
