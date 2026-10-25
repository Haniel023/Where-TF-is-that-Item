package com.wtfi.client.ui.page;

import static com.wtfi.client.ui.page.ItemPageScreen.t;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.enchant.AnvilCalc;
import com.wtfi.client.enchant.AnvilPlanner;
import com.wtfi.client.enchant.TableSimulator;
import com.wtfi.client.index.IndexEntry;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The enchanting planner: table odds and bookshelf advice, what a book costs on your held item,
 * the cheapest anvil order for the books you carry, anvil uses left, and grindstone XP.
 */
final class EnchantTab {
	private EnchantTab() {
	}

	static boolean relevant(ItemStack stack) {
		return stack.has(DataComponents.ENCHANTABLE) || stack.is(Items.BOOK) || ItemPageScreen.hasStoredEnchantments(stack) || stack.isEnchanted();
	}

	static List<Element> build(PageContext ctx) {
		List<Element> out = new ArrayList<>();
		ItemStack stack = ctx.pageStack();
		var connection = ctx.minecraft().getConnection();
		if (connection == null) return out;
		HolderLookup.Provider registries = connection.registryAccess();

		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) {
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : stored.entrySet()) {
				enchantmentInfo(ctx, registries, entry.getKey(), entry.getIntValue(), out);
			}
			applyToHeld(ctx, stack, out);
		} else {
			itemInfo(ctx, registries, stack, out);
		}
		grindstone(ctx, stack, out);
		return out;
	}

	// ---- one enchantment (enchanted book pages) ----

	private static void enchantmentInfo(PageContext ctx, HolderLookup.Provider registries, Holder<Enchantment> holder, int level, List<Element> out) {
		Enchantment enchantment = holder.value();
		out.add(Elements.heading(OverviewTab.enchantName(holder, level)));
		out.add(Elements.stat(t("wtfi.enchant.max_level"), Component.literal(String.valueOf(enchantment.getMaxLevel()))));
		out.add(Elements.stat(t("wtfi.enchant.weight"), Component.literal(enchantment.getWeight() + " (" + rarity(enchantment.getWeight()).getString() + ")")));
		out.add(Elements.stat(t("wtfi.enchant.anvil_cost"), t("wtfi.enchant.anvil_cost.value", enchantment.getAnvilCost() * level, Math.max(1, enchantment.getAnvilCost() / 2) * level)));
		boolean inTable = holder.is(EnchantmentTags.IN_ENCHANTING_TABLE);
		boolean curse = holder.is(EnchantmentTags.CURSE);
		out.add(Elements.stat(t("wtfi.enchant.table"), inTable ? t("wtfi.yes").withStyle(ChatFormatting.DARK_GREEN)
			: t(curse ? "wtfi.enchant.curse" : "wtfi.enchant.treasure").withStyle(ChatFormatting.DARK_RED)));
		if (inTable) {
			out.add(Elements.stat(t("wtfi.enchant.power"), Component.literal(enchantment.getMinCost(level) + "–" + enchantment.getMaxCost(level))));
		}

		List<Component> conflicts = new ArrayList<>();
		for (Holder<Enchantment> other : enchantment.exclusiveSet()) {
			if (!other.equals(holder)) conflicts.add(OverviewTab.enchantName(other, 1).copy().withStyle(ChatFormatting.DARK_RED));
		}
		if (!conflicts.isEmpty()) {
			MutableComponent line = t("wtfi.enchant.conflicts").append(" ");
			for (int i = 0; i < conflicts.size(); i++) {
				if (i > 0) line.append(", ");
				line.append(conflicts.get(i));
			}
			out.add(Elements.text(line));
		}

		WtfiSession session = ctx.session();
		List<ItemStack> supported = new ArrayList<>();
		Map<net.minecraft.world.item.Item, Boolean> seen = new IdentityHashMap<>();
		for (IndexEntry entry : session.index().entries()) {
			if (supported.size() >= 40) break;
			ItemStack candidate = entry.stack;
			if (seen.putIfAbsent(candidate.getItem(), true) != null) continue;
			if (candidate.is(Items.ENCHANTED_BOOK) || candidate.is(Items.BOOK)) continue;
			if (enchantment.isSupportedItem(candidate)) supported.add(candidate.getItem().getDefaultInstance());
		}
		if (!supported.isEmpty()) {
			out.add(Elements.text(t("wtfi.enchant.applies_to")));
			out.add(Elements.items(supported));
		}

		if (inTable) {
			ItemStack held = ctx.minecraft().player == null ? ItemStack.EMPTY : ctx.minecraft().player.getMainHandItem();
			ItemStack subject = !held.isEmpty() && held.has(DataComponents.ENCHANTABLE) && enchantment.isPrimaryItem(held) ? held : new ItemStack(Items.BOOK);
			out.add(Elements.text(t("wtfi.enchant.odds_for", subject.getHoverName()), 0xFF555555));
			out.add(new Deferred(TableSimulator.odds(subject, registries).thenApply(odds -> oddsForOne(odds, holder, level))));
		}
	}

	private static List<Element> oddsForOne(TableSimulator.Odds odds, Holder<Enchantment> holder, int level) {
		List<Element> out = new ArrayList<>();
		int best = odds.bestLevel(holder, level);
		if (best == 0) {
			out.add(Elements.text(t("wtfi.enchant.never"), 0xFF8B2E2E));
			return out;
		}
		out.add(Elements.stat(t("wtfi.enchant.best_level", best), pct(odds.at(holder, level, best))));
		out.add(Elements.stat(t("wtfi.enchant.at_30"), pct(odds.at(holder, level, 30))));
		int bestShelves = 15;
		float bestChance = -1;
		for (int shelves = 0; shelves <= 15; shelves++) {
			float c = odds.withShelves(holder, level, shelves, 2);
			if (c > bestChance + 1e-4f) {
				bestChance = c;
				bestShelves = shelves;
			}
		}
		out.add(Elements.stat(t("wtfi.enchant.best_shelves", bestShelves), pct(bestChance)));
		for (int shelves : new int[] {0, 5, 10, 15}) {
			out.add(Elements.stat(t("wtfi.enchant.shelves_row", shelves),
				Component.literal(pctString(odds.withShelves(holder, level, shelves, 0)) + " / " + pctString(odds.withShelves(holder, level, shelves, 1)) + " / " + pctString(odds.withShelves(holder, level, shelves, 2)))));
		}
		out.add(Elements.text(t("wtfi.enchant.odds_note"), 0xFF707070));
		return out;
	}

	private static void applyToHeld(PageContext ctx, ItemStack book, List<Element> out) {
		LocalPlayer player = ctx.minecraft().player;
		if (player == null) return;
		ItemStack held = player.getMainHandItem();
		out.add(Elements.heading(t("wtfi.enchant.apply_held")));
		if (held.isEmpty()) {
			out.add(Elements.text(t("wtfi.enchant.hold_item"), 0xFF707070));
			return;
		}
		AnvilCalc.Result result = AnvilCalc.combine(held, book, false);
		out.add(Elements.source(List.of(held), held.getHoverName(), List.of(anvilVerdict(result, player.experienceLevel)), null));
		if (result.valid()) out.add(Elements.items(List.of(result.result())));
	}

	static Component anvilVerdict(AnvilCalc.Result result, int playerLevels) {
		if (!result.valid()) return t("wtfi.anvil.cant").withStyle(ChatFormatting.DARK_RED);
		if (result.tooExpensive()) return t("wtfi.anvil.too_expensive", result.cost()).withStyle(ChatFormatting.DARK_RED);
		boolean afford = playerLevels >= result.cost();
		return t("wtfi.anvil.cost", result.cost(), playerLevels).withStyle(afford ? ChatFormatting.DARK_GREEN : ChatFormatting.GOLD);
	}

	// ---- an enchantable item ----

	private static void itemInfo(PageContext ctx, HolderLookup.Provider registries, ItemStack stack, List<Element> out) {
		LocalPlayer player = ctx.minecraft().player;
		var enchantable = stack.get(DataComponents.ENCHANTABLE);
		out.add(Elements.heading(t("wtfi.enchant.item")));
		if (enchantable != null) out.add(Elements.stat(t("wtfi.prop.enchantability"), Component.literal(String.valueOf(enchantable.value()))));
		ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
		ItemStack subject = !held.isEmpty() && held.is(stack.getItem()) ? held : stack;
		if (subject == held) {
			int prior = AnvilCalc.priorWork(held);
			out.add(Elements.stat(t("wtfi.enchant.prior_work"), Component.literal(prior + " (" + held.getOrDefault(DataComponents.REPAIR_COST, 0) + ")")));
			out.add(Elements.stat(t("wtfi.enchant.uses_left"), Component.literal(String.valueOf(AnvilCalc.usesLeft(held)))));
		}

		// Odds for every table enchantment on this item at level 30.
		out.add(Elements.heading(t("wtfi.enchant.table_odds")));
		out.add(new Deferred(TableSimulator.odds(stack.copyWithCount(1), registries).thenApply(odds -> {
			List<Element> rows = new ArrayList<>();
			List<Holder<Enchantment>> pool = new ArrayList<>(odds.pool());
			pool.removeIf(h -> odds.bestLevel(h, 1) == 0);
			pool.sort(Comparator.comparingDouble((Holder<Enchantment> h) -> -odds.at(h, 1, 30)));
			for (Holder<Enchantment> h : pool) {
				int max = h.value().getMaxLevel();
				int reachable = 1;
				for (int l = max; l >= 1; l--) {
					if (odds.bestLevel(h, l) > 0) {
						reachable = l;
						break;
					}
				}
				// "Sharpness IV: 12% at L30 (any level 39%)": odds for the best level the table can reach.
				int bestTable = odds.bestLevel(h, reachable);
				rows.add(Elements.stat(OverviewTab.enchantName(h, reachable),
					t("wtfi.enchant.odds_row", pctString(odds.at(h, reachable, bestTable)), bestTable, pctString(odds.at(h, 1, 30))).withStyle(ChatFormatting.DARK_GRAY)));
			}
			if (rows.isEmpty()) rows.add(Elements.text(t("wtfi.enchant.not_enchantable"), 0xFF707070));
			else rows.add(Elements.text(t("wtfi.enchant.odds_note"), 0xFF707070));
			return rows;
		})));

		// Treasure enchantments: can go on the item but never come from the table.
		HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
		List<Component> treasure = new ArrayList<>();
		lookup.listElements().forEach(h -> {
			if (!h.is(EnchantmentTags.IN_ENCHANTING_TABLE) && h.value().isSupportedItem(stack)) {
				treasure.add(OverviewTab.enchantName(h, h.value().getMaxLevel()));
			}
		});
		if (!treasure.isEmpty()) {
			out.add(Elements.heading(t("wtfi.enchant.treasure_list")));
			MutableComponent line = Component.empty();
			for (int i = 0; i < treasure.size(); i++) {
				if (i > 0) line.append(", ");
				line.append(treasure.get(i));
			}
			out.add(Elements.text(line));
			out.add(Elements.text(t("wtfi.enchant.treasure_hint"), 0xFF707070));
		}

		planner(ctx, subject, out);
	}

	private static void planner(PageContext ctx, ItemStack target, List<Element> out) {
		LocalPlayer player = ctx.minecraft().player;
		out.add(Elements.heading(t("wtfi.enchant.planner")));
		if (player == null) return;
		List<ItemStack> books = new ArrayList<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.is(Items.ENCHANTED_BOOK) && ItemPageScreen.hasStoredEnchantments(stack)) {
				for (int i = 0; i < stack.getCount() && books.size() < AnvilPlanner.MAX_BOOKS; i++) books.add(stack.copyWithCount(1));
			}
		}
		books.removeIf(book -> {
			ItemEnchantments e = book.get(DataComponents.STORED_ENCHANTMENTS);
			if (e == null) return true;
			for (Holder<Enchantment> h : e.keySet()) if (h.value().isSupportedItem(target)) return false;
			return !target.is(Items.ENCHANTED_BOOK) && !target.is(Items.BOOK);
		});
		if (books.isEmpty()) {
			out.add(Elements.text(t("wtfi.enchant.planner_hint"), 0xFF707070));
			return;
		}
		out.add(Elements.text(t("wtfi.enchant.planner_books", books.size(), target.getHoverName()), 0xFF555555));
		int levels = player.experienceLevel;
		out.add(new Deferred(AnvilPlanner.plan(target, books).thenApply(plan -> {
			List<Element> rows = new ArrayList<>();
			if (plan == null) {
				rows.add(Elements.text(t("wtfi.enchant.planner_none"), 0xFF8B2E2E));
				return rows;
			}
			Map<ItemStack, Integer> stepOf = new IdentityHashMap<>();
			int number = 0;
			for (AnvilPlanner.Step step : plan.steps()) {
				number++;
				Component left = label(step.leftStack(), stepOf);
				Component right = label(step.rightStack(), stepOf);
				rows.add(Elements.source(List.of(step.result()),
					t("wtfi.enchant.step", number, left, right),
					List.of(t("wtfi.enchant.step_cost", step.cost()).withStyle(step.cost() > levels ? ChatFormatting.GOLD : ChatFormatting.DARK_GREEN)),
					null));
				stepOf.put(step.result(), number);
			}
			rows.add(Elements.stat(t("wtfi.enchant.total"), t("wtfi.enchant.total.value", plan.totalLevels(), plan.maxStep())));
			rows.add(Elements.items(List.of(plan.result())));
			return rows;
		})));
	}

	private static Component label(ItemStack stack, Map<ItemStack, Integer> stepOf) {
		Integer step = stepOf.get(stack);
		if (step != null) return t("wtfi.enchant.result_of", step);
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) {
			MutableComponent names = Component.empty();
			boolean first = true;
			for (Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
				if (!first) names.append(" + ");
				first = false;
				names.append(OverviewTab.enchantName(e.getKey(), e.getIntValue()));
			}
			return names;
		}
		return stack.getHoverName();
	}

	private static void grindstone(PageContext ctx, ItemStack stack, List<Element> out) {
		LocalPlayer player = ctx.minecraft().player;
		ItemStack subject = stack;
		if (!stack.isEnchanted() && !ItemPageScreen.hasStoredEnchantments(stack) && player != null && player.getMainHandItem().is(stack.getItem())) {
			subject = player.getMainHandItem();
		}
		int[] xp = AnvilCalc.grindstoneXp(subject);
		if (xp[1] <= 0) return;
		out.add(Elements.heading(t("wtfi.enchant.grindstone")));
		out.add(Elements.stat(t("wtfi.enchant.grindstone_xp"), Component.literal(xp[0] + "–" + xp[1])));
		if (EnchantmentHelper.hasAnyEnchantments(subject) && subject.getEnchantments().keySet().stream().anyMatch(h -> h.is(EnchantmentTags.CURSE))) {
			out.add(Elements.text(t("wtfi.enchant.grindstone_curse"), 0xFF707070));
		}
	}

	private static Component rarity(int weight) {
		String key = weight >= 10 ? "common" : weight >= 5 ? "uncommon" : weight >= 2 ? "rare" : "very_rare";
		return t("wtfi.enchant.rarity." + key);
	}

	private static Component pct(float chance) {
		return Component.literal(pctString(chance));
	}

	private static String pctString(float chance) {
		return Sources.percent(chance).replace("≈", "");
	}
}
