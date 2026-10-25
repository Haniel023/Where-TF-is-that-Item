package com.wtfi.client.hud;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.knowledge.RecipeView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Pinned crafting goals. A goal is broken down into base materials (intermediates that can only
 * be crafted are expanded) and shown on the HUD, counting down as the player gathers them.
 */
public final class Checklist {
	private static final Checklist INSTANCE = new Checklist();
	public static final int MAX_GOALS = 3;
	private static final int MAX_DEPTH = 4;
	private final List<Goal> goals = new ArrayList<>();

	public record Need(List<ItemStack> options, int count) {
		public ItemStack icon() {
			return this.options.isEmpty() ? ItemStack.EMPTY : this.options.getFirst();
		}
	}

	public record Goal(ItemStack output, RecipeView recipe, List<Need> needs) {
	}

	public static Checklist get() {
		return INSTANCE;
	}

	public List<Goal> goals() {
		return this.goals;
	}

	public boolean isPinned(Item item) {
		for (Goal goal : this.goals) if (goal.output.is(item)) return true;
		return false;
	}

	public void unpin(Item item) {
		this.goals.removeIf(g -> g.output.is(item));
	}

	public void clear() {
		this.goals.clear();
	}

	public void pin(RecipeView recipe) {
		ItemStack output = recipe.firstOutput().copy();
		this.unpin(output.getItem());
		WtfiSession session = WtfiSession.get();
		Knowledge knowledge = session == null ? null : session.knowledge();
		Map<String, Need> needs = new LinkedHashMap<>();
		this.expand(knowledge, recipe, 1, 0, needs);
		this.goals.addFirst(new Goal(output, recipe, List.copyOf(needs.values())));
		while (this.goals.size() > MAX_GOALS) this.goals.removeLast();
	}

	private void expand(Knowledge knowledge, RecipeView recipe, int crafts, int depth, Map<String, Need> out) {
		for (List<ItemStack> slot : recipe.inputs()) {
			if (slot.isEmpty()) continue;
			int count = Math.max(1, slot.getFirst().getCount()) * crafts;
			if (slot.size() == 1 && depth < MAX_DEPTH && knowledge != null) {
				ItemStack only = slot.getFirst();
				RecipeView sub = craftOnly(knowledge, only.getItem());
				if (sub != null) {
					int perCraft = Math.max(1, sub.firstOutput().getCount());
					this.expand(knowledge, sub, (count + perCraft - 1) / perCraft, depth + 1, out);
					continue;
				}
			}
			String key = slot.size() == 1 ? slot.getFirst().getItem().toString() : slot.toString();
			Need previous = out.get(key);
			out.put(key, new Need(slot, previous == null ? count : previous.count + count));
		}
	}

	/**
	 * The recipe to break an intermediate down with, only when the item can't be found in the
	 * world (no loot, trade or generation): planks become logs, but iron ingots stay ingots.
	 */
	private static RecipeView craftOnly(Knowledge knowledge, Item item) {
		ItemStack stack = new ItemStack(item);
		if (!knowledge.loot(stack).isEmpty() || !knowledge.tradesGiving(stack).isEmpty() || !knowledge.generation(item).isEmpty()) return null;
		RecipeView best = null;
		for (RecipeView recipe : knowledge.producedBy(item)) {
			String layout = recipe.layout();
			if (!layout.equals("shaped") && !layout.equals("shapeless") && !layout.equals("furnace")) continue;
			// Skip recipes that use the item itself (e.g. block <-> ingot loops).
			boolean loops = false;
			for (List<ItemStack> slot : recipe.inputs()) for (ItemStack s : slot) if (s.is(item)) loops = true;
			if (loops) continue;
			if (best == null || layout.equals("shaped") && !best.layout().equals("shaped")) best = recipe;
		}
		return best;
	}

	/** How many of a need the player carries (any of its alternatives). */
	public static int have(Need need, Map<Item, Integer> inventory) {
		int total = 0;
		Map<Item, Boolean> counted = new IdentityHashMap<>();
		for (ItemStack option : need.options) {
			if (counted.put(option.getItem(), true) == null) total += inventory.getOrDefault(option.getItem(), 0);
		}
		return total;
	}
}
