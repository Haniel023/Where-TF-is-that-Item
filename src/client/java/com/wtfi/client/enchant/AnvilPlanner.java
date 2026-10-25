package com.wtfi.client.enchant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Finds the cheapest order to combine an item with a set of enchanted books, never letting a
 * single anvil step reach "Too Expensive!". Exhaustive over merge trees with memoised subsets and
 * a small Pareto front per subset (fewest levels vs. lowest prior-work penalty).
 */
public final class AnvilPlanner {
	public static final int MAX_BOOKS = 8;
	private static final int FRONT = 4;

	private final List<ItemStack> items;
	private final boolean hasTarget;
	private final Map<Integer, List<Node>> memo = new HashMap<>();

	public record Step(int left, int right, ItemStack leftStack, ItemStack rightStack, ItemStack result, int cost, int index) {
	}

	/** A finished plan. Inputs are numbered: 0 is the target item, books follow in the order given. */
	public record Plan(ItemStack result, int totalLevels, int maxStep, List<Step> steps) {
	}

	private record Node(ItemStack stack, int total, int maxStep, List<Step> steps, int id) {
	}

	private AnvilPlanner(@Nullable ItemStack target, List<ItemStack> books) {
		this.items = new ArrayList<>();
		this.hasTarget = target != null && !target.isEmpty();
		if (this.hasTarget) this.items.add(target);
		this.items.addAll(books.subList(0, Math.min(MAX_BOOKS, books.size())));
	}

	/** Plans off the render thread. Completes with null when no valid order exists. */
	public static CompletableFuture<@Nullable Plan> plan(@Nullable ItemStack target, List<ItemStack> books) {
		AnvilPlanner planner = new AnvilPlanner(target == null ? null : target.copy(), books.stream().map(ItemStack::copy).toList());
		return CompletableFuture.supplyAsync(planner::solve, Util.backgroundExecutor());
	}

	private @Nullable Plan solve() {
		if (this.items.size() < 2) return null;
		int all = (1 << this.items.size()) - 1;
		List<Node> best = this.solve(all);
		if (best.isEmpty()) return null;
		Node node = best.stream().min(Comparator.comparingInt(Node::total).thenComparingInt(n -> repairCost(n.stack))).orElseThrow();
		List<Step> ordered = new ArrayList<>(node.steps);
		return new Plan(node.stack, node.total, node.maxStep, ordered);
	}

	private List<Node> solve(int mask) {
		List<Node> cached = this.memo.get(mask);
		if (cached != null) return cached;
		List<Node> out = new ArrayList<>();
		if (Integer.bitCount(mask) == 1) {
			int index = Integer.numberOfTrailingZeros(mask);
			out.add(new Node(this.items.get(index), 0, 0, List.of(), index));
			this.memo.put(mask, out);
			return out;
		}
		boolean containsTarget = this.hasTarget && (mask & 1) != 0;
		// Enumerate proper non-empty submasks as the left side.
		for (int left = (mask - 1) & mask; left > 0; left = (left - 1) & mask) {
			int right = mask ^ left;
			if (containsTarget && (left & 1) == 0) continue; // the item always stays on the left
			for (Node a : this.solve(left)) {
				for (Node b : this.solve(right)) {
					AnvilCalc.Result r = AnvilCalc.combine(a.stack, b.stack, false);
					if (!r.valid() || r.tooExpensive()) continue;
					List<Step> steps = new ArrayList<>(a.steps.size() + b.steps.size() + 1);
					steps.addAll(a.steps);
					steps.addAll(b.steps);
					int id = -mask;
					steps.add(new Step(a.id, b.id, a.stack, b.stack, r.result(), r.cost(), steps.size()));
					out.add(new Node(r.result(), a.total + b.total + r.cost(), Math.max(r.cost(), Math.max(a.maxStep, b.maxStep)), steps, id));
				}
			}
		}
		List<Node> front = pareto(out);
		this.memo.put(mask, front);
		return front;
	}

	private static List<Node> pareto(List<Node> nodes) {
		nodes.sort(Comparator.comparingInt(Node::total).thenComparingInt(n -> repairCost(n.stack)));
		List<Node> front = new ArrayList<>();
		int bestRepair = Integer.MAX_VALUE;
		for (Node node : nodes) {
			int repair = repairCost(node.stack);
			if (repair < bestRepair) {
				front.add(node);
				bestRepair = repair;
				if (front.size() >= FRONT) break;
			}
		}
		return front;
	}

	private static int repairCost(ItemStack stack) {
		return stack.getOrDefault(DataComponents.REPAIR_COST, 0);
	}
}
