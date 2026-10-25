package com.wtfi.client.ui.page;

import static com.wtfi.client.ui.page.ItemPageScreen.t;

import com.wtfi.client.knowledge.ItemNotes;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.knowledge.RecipeView;
import com.wtfi.data.WtfiDataset.LootDrop;
import com.wtfi.data.WtfiDataset.TradeInfo;
import com.wtfi.data.WtfiDataset.TransformInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** How to get an item: verdict, notes, recipes (with what you're missing), loot, trades, tool interactions. */
final class ObtainTab {
	private static final int MAX_RECIPES = 60;
	private static final int MAX_LOOT = 80;
	private static final List<String> KIND_ORDER = List.of(
		"block", "entity", "chest", "archaeology", "trial_spawner", "fishing", "barter", "hero_gift", "gift", "sniffer", "shearing",
		"brush", "harvest", "carve", "till", "charged_creeper", "equipment", "gameplay", "other"
	);

	private ObtainTab() {
	}

	static List<Element> build(PageContext ctx) {
		List<Element> out = new ArrayList<>();
		Knowledge knowledge = ctx.knowledge();
		if (knowledge == null) {
			out.add(Elements.text(t("wtfi.page.loading_data")));
			return out;
		}
		ItemStack stack = ctx.pageStack();
		Item item = stack.getItem();
		Identifier id = BuiltInRegistries.ITEM.getKey(item);

		out.add(Elements.text(verdict(knowledge, item)));
		if (!knowledge.fromServer) out.add(Elements.text(t("wtfi.page.vanilla_data"), 0xFF707070));
		Component note = ItemNotes.obtainNote(id);
		if (note != null) {
			out.add(Elements.heading(t("wtfi.obtain.notes")));
			out.add(Elements.text(note));
		}

		boolean any = note != null;
		any |= brewingPath(knowledge, stack, out);

		List<RecipeView> recipes = new ArrayList<>(knowledge.producedBy(stack));
		recipes.removeIf(r -> r.layout().equals("brewing") && ItemPageScreen.hasPotion(stack));
		recipes.sort(Comparator.comparingInt(ObtainTab::layoutRank));
		if (!recipes.isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.obtain.recipes", recipes.size())));
			for (RecipeView recipe : recipes.subList(0, Math.min(MAX_RECIPES, recipes.size()))) out.add(new RecipeElement(recipe, true));
			if (recipes.size() > MAX_RECIPES) out.add(Elements.text(t("wtfi.page.more", recipes.size() - MAX_RECIPES), 0xFF707070));
		}

		List<LootDrop> loot = knowledge.loot(stack);
		if (!loot.isEmpty()) {
			any = true;
			Map<String, List<LootDrop>> byKind = new LinkedHashMap<>();
			for (String kind : KIND_ORDER) byKind.put(kind, new ArrayList<>());
			for (LootDrop drop : loot) byKind.computeIfAbsent(drop.kind(), k -> new ArrayList<>()).add(drop);
			int shown = 0;
			for (var entry : byKind.entrySet()) {
				List<LootDrop> drops = entry.getValue();
				if (drops.isEmpty()) continue;
				boolean bookPage = ItemPageScreen.hasStoredEnchantments(stack);
				// On a book page, a random-enchantment book is only "this" book 1 time in n.
				java.util.function.ToDoubleFunction<LootDrop> effective = d ->
					bookPage && !d.enchantments().isEmpty() ? d.chance() / d.enchantments().size() : d.chance();
				drops.sort(Comparator.comparingDouble((LootDrop d) -> -effective.applyAsDouble(d)));
				out.add(Elements.heading(t("wtfi.obtain.loot", Component.translatable("wtfi.loot_kind." + entry.getKey()), drops.size())));
				for (LootDrop drop : drops) {
					if (shown++ >= MAX_LOOT) break;
					List<Component> details = new ArrayList<>(Sources.lootDetails(drop));
					if (bookPage && !drop.enchantments().isEmpty()) {
						details.add(t("wtfi.obtain.random_book", Sources.percent((float)effective.applyAsDouble(drop)), drop.enchantments().size())
							.withStyle(ChatFormatting.DARK_PURPLE));
					}
					out.add(Elements.source(List.of(Sources.lootIcon(drop)), Sources.lootName(drop), details, null));
				}
			}
			if (shown > MAX_LOOT) out.add(Elements.text(t("wtfi.page.more", shown - MAX_LOOT), 0xFF707070));
		}

		List<TradeInfo> trades = knowledge.tradesGiving(stack);
		if (!trades.isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.obtain.trades", trades.size())));
			for (TradeInfo trade : trades) {
				out.add(Elements.source(List.of(Sources.professionIcon(trade.trader())), Sources.traderName(trade.trader(), trade.level()), List.of(tradeLine(trade, true)), null));
			}
		}

		List<TransformInfo> transforms = knowledge.transformsTo(item);
		if (!transforms.isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.obtain.interactions")));
			for (TransformInfo transform : transforms) {
				ItemStack from = BuiltInRegistries.BLOCK.getValue(transform.from()).asItem().getDefaultInstance();
				List<ItemStack> tools = UsesTab.toolsFor(ctx, transform.transformer());
				out.add(Elements.source(tools.isEmpty() ? List.of(from) : tools, t("wtfi.obtain.use_on", tools.isEmpty() ? Component.literal("?") : tools.getFirst().getHoverName(), from.getHoverName()), List.of(), from));
			}
		}

		if (!knowledge.generation(item).isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.obtain.generates")));
			out.add(Elements.text(t("wtfi.obtain.see_where"), 0xFF555555));
		}

		if (!any) {
			out.add(Elements.spacer(6));
			out.add(Elements.text(t(knowledge.obtainability(item) == Knowledge.Obtainability.CREATIVE_ONLY ? "wtfi.obtain.none_creative" : "wtfi.obtain.none")));
		}
		return out;
	}

	static Component verdict(Knowledge knowledge, Item item) {
		return switch (knowledge.obtainability(item)) {
			case CREATIVE_ONLY -> t("wtfi.verdict.creative_only").withStyle(ChatFormatting.DARK_PURPLE);
			case UNKNOWN -> t("wtfi.verdict.unknown").withStyle(ChatFormatting.DARK_GRAY);
			case SURVIVAL -> {
				Knowledge.Renewability r = knowledge.renewability(item);
				MutableComponent base = t("wtfi.verdict.survival").withStyle(ChatFormatting.DARK_GREEN);
				if (!r.known()) yield base;
				Component reason = Component.translatable("wtfi.reason." + reasonKey(r.reason()));
				yield base.append(" ").append(t(r.renewable() ? "wtfi.verdict.renewable" : "wtfi.verdict.non_renewable", reason)
					.withStyle(r.renewable() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
			}
		};
	}

	private static String reasonKey(String reason) {
		int colon = reason.indexOf(':');
		return colon < 0 ? reason : reason.substring(0, colon);
	}

	static int layoutRank(RecipeView recipe) {
		return switch (recipe.layout()) {
			case "shaped", "shapeless" -> 0;
			case "furnace" -> 1;
			case "stonecutter" -> 2;
			case "smithing" -> 3;
			case "brewing" -> 4;
			default -> 5;
		};
	}

	static Component tradeLine(TradeInfo trade, boolean giving) {
		MutableComponent wants = Component.empty();
		for (int i = 0; i < trade.wants().size(); i++) {
			ItemStack want = trade.wants().get(i);
			if (i > 0) wants.append(" + ");
			boolean variable = i == 0 && trade.variablePrice();
			wants.append(variable ? t("wtfi.trade.some", want.getHoverName()) : Component.literal(want.getCount() + "× ").append(want.getHoverName()));
		}
		ItemStack gives = trade.gives();
		MutableComponent givesText = Component.literal(gives.getCount() + "× ").append(gives.getHoverName());
		MutableComponent line = t("wtfi.trade.line", wants, givesText);
		if (trade.maxUses() > 0) line.append(Component.literal(" · ").append(t("wtfi.trade.uses", trade.maxUses())).withStyle(ChatFormatting.DARK_GRAY));
		return line;
	}

	/**
	 * For potions: the full brewing chain from a water bottle to this exact potion, one step per
	 * row, by walking brewing recipes backwards.
	 */
	private static boolean brewingPath(Knowledge knowledge, ItemStack target, List<Element> out) {
		if (!ItemPageScreen.hasPotion(target)) return false;
		List<RecipeView> chain = new ArrayList<>();
		ItemStack current = target;
		Map<String, Boolean> visited = new HashMap<>();
		for (int depth = 0; depth < 8; depth++) {
			String key = current.getItem() + "|" + current.get(DataComponents.POTION_CONTENTS);
			if (visited.put(key, true) != null) break;
			RecipeView step = null;
			for (RecipeView recipe : knowledge.producedBy(current)) {
				if (!recipe.layout().equals("brewing")) continue;
				boolean exact = false;
				for (ItemStack o : recipe.outputs()) {
					if (ItemStack.isSameItemSameComponents(o, current)) {
						exact = true;
						break;
					}
				}
				if (exact) {
					step = recipe;
					break;
				}
			}
			if (step == null) break;
			chain.addFirst(step);
			List<ItemStack> inputs = step.inputs().isEmpty() ? List.of() : step.inputs().getFirst();
			ItemStack next = null;
			for (ItemStack input : inputs) {
				if (input.is(current.getItem())) {
					next = input;
					break;
				}
			}
			if (next == null && !inputs.isEmpty()) next = inputs.getFirst();
			if (next == null) break;
			current = next;
		}
		if (chain.isEmpty()) return false;
		out.add(Elements.heading(t("wtfi.obtain.brewing_path", chain.size())));
		for (RecipeView step : chain) out.add(new RecipeElement(step, false));
		return true;
	}
}
