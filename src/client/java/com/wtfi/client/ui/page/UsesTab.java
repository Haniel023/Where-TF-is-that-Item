package com.wtfi.client.ui.page;

import static com.wtfi.client.ui.page.ItemPageScreen.t;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.knowledge.ItemNotes;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.knowledge.RecipeView;
import com.wtfi.data.WtfiDataset.ItemProps;
import com.wtfi.data.WtfiDataset.TradeInfo;
import com.wtfi.data.WtfiDataset.TransformInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** What an item is good for: recipes it goes into, trades, fuel, composting and tool interactions. */
final class UsesTab {
	private static final int MAX_RECIPES = 80;
	private static Map<Identifier, List<ItemStack>> toolCache;
	private static WtfiSession toolCacheSession;

	private UsesTab() {
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
		boolean any = false;

		Component note = ItemNotes.useNote(id);
		if (note != null) {
			any = true;
			out.add(Elements.heading(t("wtfi.uses.notes")));
			out.add(Elements.text(note));
		}

		ItemProps props = knowledge.props(item);
		if (props != null && (props.burnTicks() > 0 || props.compostChance() > 0 || props.brewingFuelUses() > 0)) {
			any = true;
			out.add(Elements.heading(t("wtfi.uses.properties")));
			if (props.burnTicks() > 0) out.add(Elements.stat(t("wtfi.prop.fuel"), t("wtfi.prop.fuel.value", OverviewTab.num(props.burnTicks() / 20.0), OverviewTab.num(props.burnTicks() / 200.0))));
			if (props.compostChance() > 0) out.add(Elements.stat(t("wtfi.prop.compost"), Component.literal(Sources.percent(Math.min(1f, props.compostChance())).replace("≈", ""))));
			if (props.brewingFuelUses() > 0) out.add(Elements.stat(t("wtfi.prop.brewing_fuel"), Component.literal(String.valueOf(props.brewingFuelUses()))));
		}

		List<RecipeView> recipes = new ArrayList<>(knowledge.usedIn(stack));
		recipes.sort(Comparator.comparingInt(ObtainTab::layoutRank));
		if (!recipes.isEmpty()) {
			any = true;
			Set<Item> craftable = ctx.session().craftable();
			out.add(Elements.heading(t("wtfi.uses.recipes", recipes.size())));
			// Recipes you can make right now come first.
			recipes.sort(Comparator.comparingInt((RecipeView r) -> craftable.contains(r.firstOutput().getItem()) ? 0 : 1).thenComparingInt(ObtainTab::layoutRank));
			for (RecipeView recipe : recipes.subList(0, Math.min(MAX_RECIPES, recipes.size()))) out.add(new RecipeElement(recipe, false));
			if (recipes.size() > MAX_RECIPES) out.add(Elements.text(t("wtfi.page.more", recipes.size() - MAX_RECIPES), 0xFF707070));
		}

		List<TradeInfo> wanting = knowledge.tradesWanting(item);
		if (!wanting.isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.uses.trades", wanting.size())));
			for (TradeInfo trade : wanting) {
				out.add(Elements.source(List.of(Sources.professionIcon(trade.trader())), Sources.traderName(trade.trader(), trade.level()), List.of(ObtainTab.tradeLine(trade, false)), trade.gives()));
			}
		}

		var transformer = stack.get(DataComponents.BLOCK_TRANSFORMER);
		if (transformer != null) {
			Identifier transformerId = transformer.unwrapKey().map(ResourceKey::identifier).orElse(null);
			List<TransformInfo> pairs = new ArrayList<>();
			for (TransformInfo t : knowledge.data.transforms()) if (t.transformer().equals(transformerId)) pairs.add(t);
			if (!pairs.isEmpty()) {
				any = true;
				out.add(Elements.heading(t("wtfi.uses.tool_on", pairs.size())));
				for (TransformInfo pair : pairs) {
					ItemStack from = BuiltInRegistries.BLOCK.getValue(pair.from()).asItem().getDefaultInstance();
					ItemStack to = BuiltInRegistries.BLOCK.getValue(pair.to()).asItem().getDefaultInstance();
					if (from.isEmpty() || to.isEmpty()) continue;
					out.add(Elements.source(List.of(from), Component.empty().append(from.getHoverName()).append(" → ").append(to.getHoverName()), List.of(), to));
				}
			}
		}

		List<TransformInfo> fromThis = knowledge.transformsFrom(item);
		if (!fromThis.isEmpty()) {
			any = true;
			out.add(Elements.heading(t("wtfi.uses.becomes")));
			for (TransformInfo pair : fromThis) {
				ItemStack to = BuiltInRegistries.BLOCK.getValue(pair.to()).asItem().getDefaultInstance();
				List<ItemStack> tools = toolsFor(ctx, pair.transformer());
				out.add(Elements.source(tools.isEmpty() ? List.of(to) : tools,
					t("wtfi.uses.with_tool", tools.isEmpty() ? Component.literal("?") : tools.getFirst().getHoverName(), to.getHoverName()), List.of(), to));
			}
		}

		if (!any) out.add(Elements.text(t("wtfi.uses.none").withStyle(ChatFormatting.DARK_GRAY)));
		return out;
	}

	/** Items whose block transformer component is {@code transformer} (all axes, all hoes...). */
	static List<ItemStack> toolsFor(PageContext ctx, Identifier transformer) {
		if (toolCache == null || toolCacheSession != ctx.session()) {
			toolCache = new HashMap<>();
			toolCacheSession = ctx.session();
			for (Item item : BuiltInRegistries.ITEM) {
				var holder = item.components().get(DataComponents.BLOCK_TRANSFORMER);
				if (holder == null) continue;
				holder.unwrapKey().ifPresent(key -> toolCache.computeIfAbsent(key.identifier(), k -> new ArrayList<>()).add(new ItemStack(item)));
			}
		}
		return toolCache.getOrDefault(transformer, List.of());
	}
}
