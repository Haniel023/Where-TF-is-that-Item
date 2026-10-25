package com.wtfi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.wtfi.data.WtfiDataset.TradeInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;

/** Lists every villager and wandering trader offer from the data-driven trade sets. */
final class TradeAnalyzer {
	static final Identifier WANDERING_TRADER = Identifier.withDefaultNamespace("wandering_trader");
	private final HolderLookup.Provider registries;
	private final RegistryOps<JsonElement> ops;

	TradeAnalyzer(HolderLookup.Provider registries) {
		this.registries = registries;
		this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
	}

	List<TradeInfo> analyze() {
		List<TradeInfo> out = new ArrayList<>();
		Set<ResourceKey<TradeSet>> seen = new HashSet<>();
		HolderLookup.RegistryLookup<TradeSet> sets = this.registries.lookupOrThrow(Registries.TRADE_SET);

		this.registries.lookupOrThrow(Registries.VILLAGER_PROFESSION).listElements().forEach(profession -> {
			VillagerProfession value = profession.value();
			value.tradeSetsByLevel().forEach((level, key) -> {
				seen.add(key);
				sets.get(key).ifPresent(set -> this.addSet(set.value(), profession.key().identifier(), level, out));
			});
		});
		for (ResourceKey<TradeSet> key : List.of(TradeSets.WANDERING_TRADER_BUYING, TradeSets.WANDERING_TRADER_COMMON, TradeSets.WANDERING_TRADER_UNCOMMON)) {
			seen.add(key);
			sets.get(key).ifPresent(set -> this.addSet(set.value(), WANDERING_TRADER, 0, out));
		}
		// Trade sets nobody we know uses (mods' traders): list them under the set's own name.
		sets.listElements().forEach(set -> {
			if (seen.contains(set.key())) return;
			String path = set.key().identifier().getPath();
			String trader = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
			this.addSet(set.value(), Identifier.fromNamespaceAndPath(set.key().identifier().getNamespace(), trader), 0, out);
		});
		return out;
	}

	private void addSet(TradeSet set, Identifier trader, int level, List<TradeInfo> out) {
		HolderSet<VillagerTrade> trades = set.trades();
		trades.stream().forEach(holder -> {
			JsonObject json = Json.object(Json.encode(VillagerTrade.CODEC, holder.value(), this.registries));
			if (json == null) return;
			TradeInfo info = this.parse(json, trader, level);
			if (info != null) out.add(info);
		});
	}

	private TradeInfo parse(JsonObject json, Identifier trader, int level) {
		List<ItemStack> wants = new ArrayList<>();
		ItemStack want = this.cost(json.get("wants"));
		if (want.isEmpty()) return null;
		wants.add(want);
		ItemStack extra = this.cost(json.get("additional_wants"));
		if (!extra.isEmpty()) wants.add(extra);

		JsonElement givesJson = json.get("gives");
		ItemStack gives = givesJson == null ? ItemStack.EMPTY
			: ItemStackTemplate.CODEC.parse(this.ops, givesJson).result().map(ItemStackTemplate::create).orElse(ItemStack.EMPTY);
		if (gives.isEmpty()) return null;

		List<Identifier> enchantments = new ArrayList<>();
		boolean variablePrice = false;
		for (JsonElement modElement : Json.list(json.get("given_item_modifier"))) {
			JsonObject mod = Json.object(modElement);
			if (mod == null) continue;
			String type = Json.type(mod);
			if (type.equals("enchant_randomly") || type.equals("enchant_with_levels")) {
				enchantments.addAll(this.enchantmentOptions(mod.get("options")));
				if (mod.has("include_additional_cost_component") && mod.get("include_additional_cost_component").getAsBoolean()) {
					variablePrice = true;
				}
			}
		}
		int maxUses = Math.round(Json.range(json.get("max_uses"), 4)[2]);
		return new TradeInfo(trader, level, wants, gives, enchantments, maxUses, variablePrice);
	}

	private ItemStack cost(JsonElement element) {
		JsonObject o = Json.object(element);
		if (o == null) return ItemStack.EMPTY;
		String id = Json.string(o, "id");
		if (id == null) return ItemStack.EMPTY;
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
		if (item == Items.AIR) return ItemStack.EMPTY;
		// A count of 0 means the price is added later (enchanted books); show at least 1.
		int count = Math.max(1, Math.round(Json.range(o.get("count"), 1)[2]));
		return new ItemStack(item, count);
	}

	private List<Identifier> enchantmentOptions(JsonElement options) {
		HolderLookup.RegistryLookup<Enchantment> lookup = this.registries.lookupOrThrow(Registries.ENCHANTMENT);
		List<Identifier> out = new ArrayList<>();
		for (JsonElement option : Json.list(options)) {
			if (!option.isJsonPrimitive()) continue;
			String value = option.getAsString();
			if (value.startsWith("#")) {
				TagKey<Enchantment> tag = TagKey.create(Registries.ENCHANTMENT, Identifier.parse(value.substring(1)));
				lookup.get(tag).ifPresent(s -> s.stream().forEach(h -> h.unwrapKey().ifPresent(k -> out.add(k.identifier()))));
			} else {
				out.add(Identifier.parse(value));
			}
		}
		return out;
	}
}
