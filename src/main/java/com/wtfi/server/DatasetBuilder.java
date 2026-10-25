package com.wtfi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.wtfi.Wtfi;
import com.wtfi.data.WtfiDataset;
import com.wtfi.data.WtfiDataset.AdvancementInfo;
import com.wtfi.data.WtfiDataset.ItemProps;
import com.wtfi.data.WtfiDataset.RecipeInfo;
import com.wtfi.data.WtfiDataset.SlotInfo;
import com.wtfi.data.WtfiDataset.TransformInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BrewingFuel;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

/** Builds the full {@link WtfiDataset} from a running server. Call on the server thread. */
public final class DatasetBuilder {
	private static final Set<String> NON_INGREDIENT_KEYS = Set.of("type", "category", "group", "result", "show_notification", "allowed_generations", "pattern", "key");
	private final MinecraftServer server;
	private final HolderLookup.Provider registries;
	private final ContextMap slotContext;

	public DatasetBuilder(MinecraftServer server) {
		this.server = server;
		this.registries = server.reloadableRegistries().lookup();
		this.slotContext = SlotDisplayContext.fromLevel(server.overworld());
	}

	public WtfiDataset build() {
		long start = Util.getMillis();
		List<RecipeInfo> recipes = this.recipes();
		var loot = new LootAnalyzer(this.registries).analyze();
		var trades = new TradeAnalyzer(this.registries).analyze();
		WorldGenAnalyzer worldgen = new WorldGenAnalyzer(this.server);
		worldgen.analyze();
		List<AdvancementInfo> advancements = this.advancements();
		List<ItemProps> props = this.itemProps();
		List<TransformInfo> transforms = this.transforms();
		Wtfi.LOGGER.info(
			"WTFI dataset: {} recipes, {} loot drops, {} trades, {} worldgen entries, {} advancements in {} ms",
			recipes.size(), loot.size(), trades.size(), worldgen.gen.size(), advancements.size(), Util.getMillis() - start
		);
		return new WtfiDataset(
			WtfiDataset.FORMAT, recipes, loot, trades, worldgen.gen, worldgen.structures, worldgen.spawns, advancements, props, transforms
		);
	}

	// ---- recipes ----

	private List<RecipeInfo> recipes() {
		List<RecipeInfo> out = new ArrayList<>();
		for (RecipeHolder<?> holder : this.server.getRecipeManager().getRecipes()) {
			try {
				this.recipe(holder, out);
			} catch (RuntimeException e) {
				Wtfi.LOGGER.debug("Skipping recipe {}: {}", holder.id().identifier(), e.toString());
			}
		}
		return out;
	}

	private void recipe(RecipeHolder<?> holder, List<RecipeInfo> out) {
		Recipe<?> recipe = holder.value();
		Identifier id = holder.id().identifier();
		Identifier type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
		if (type == null) type = Identifier.withDefaultNamespace("unknown");

		if (recipe instanceof BrewingRecipe brewing) {
			out.add(new RecipeInfo(
				id, type, "brewing", 0, 0,
				List.of(this.potionSlot(brewing.getInput()), this.potionSlot(brewing.getReagent())),
				SlotInfo.of(brewing.getOutput().create()),
				SlotInfo.of(new ItemStack(Items.BREWING_STAND)), 400, 0
			));
			return;
		}

		List<RecipeDisplay> displays = recipe.display();
		if (displays.isEmpty()) {
			RecipeInfo special = this.special(recipe, id, type);
			if (special != null) out.add(special);
			return;
		}
		for (int i = 0; i < displays.size(); i++) {
			Identifier displayId = displays.size() == 1 ? id : id.withSuffix("#" + i);
			RecipeInfo info = this.fromDisplay(recipe, displays.get(i), displayId, type);
			if (info != null && !info.output().isEmpty()) out.add(info);
		}
	}

	private RecipeInfo fromDisplay(Recipe<?> recipe, RecipeDisplay display, Identifier id, Identifier type) {
		SlotInfo station = this.slot(display.craftingStation());
		return switch (display) {
			case ShapedCraftingRecipeDisplay s -> new RecipeInfo(id, type, "shaped", s.width(), s.height(), this.slots(s.ingredients()), this.slot(s.result()), station, 0, 0);
			case ShapelessCraftingRecipeDisplay s -> new RecipeInfo(id, type, "shapeless", 0, 0, this.slots(s.ingredients()), this.slot(s.result()), station, 0, 0);
			case FurnaceRecipeDisplay f -> new RecipeInfo(id, type, "furnace", 0, 0, List.of(this.slot(f.ingredient())), this.slot(f.result()), station, f.duration(), f.experience());
			case StonecutterRecipeDisplay s -> new RecipeInfo(id, type, "stonecutter", 0, 0, List.of(this.slot(s.input())), this.slot(s.result()), station, 0, 0);
			case SmithingRecipeDisplay s -> new RecipeInfo(
				id, type, "smithing", 0, 0, List.of(this.slot(s.template()), this.slot(s.base()), this.slot(s.addition())), this.slot(s.result()), station, 0, 0
			);
			default -> {
				// A display type from another mod: inputs from the recipe's placement info.
				List<SlotInfo> inputs = new ArrayList<>();
				if (!recipe.placementInfo().isImpossibleToPlace()) {
					for (Ingredient ingredient : recipe.placementInfo().ingredients()) inputs.add(this.slot(ingredient.display()));
				}
				yield new RecipeInfo(id, type, "generic", 0, 0, inputs, this.slot(display.result()), station, 0, 0);
			}
		};
	}

	/**
	 * Recipes without a display (fireworks, shield patterns, map extending, modded "special"
	 * recipes): read their JSON for ingredients and a result.
	 */
	private RecipeInfo special(Recipe<?> recipe, Identifier id, Identifier type) {
		@SuppressWarnings("unchecked")
		JsonObject json = Json.object(Json.encode((com.mojang.serialization.Codec<Recipe<?>>)(Object)Recipe.DIRECT_CODEC, recipe, this.registries));
		if (json == null) return null;
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, this.registries);

		List<SlotInfo> inputs = new ArrayList<>();
		for (var entry : json.entrySet()) {
			if (NON_INGREDIENT_KEYS.contains(entry.getKey())) continue;
			JsonElement value = entry.getValue();
			Optional<Ingredient> ingredient = Ingredient.CODEC.parse(ops, value).result();
			if (ingredient.isPresent()) {
				inputs.add(this.slot(ingredient.get().display()));
			} else if (value.isJsonObject()) {
				for (var inner : value.getAsJsonObject().entrySet()) {
					Ingredient.CODEC.parse(ops, inner.getValue()).result().ifPresent(i -> inputs.add(this.slot(i.display())));
				}
			}
		}
		inputs.removeIf(SlotInfo::isEmpty);
		if (inputs.isEmpty()) return null;

		ItemStack result = ItemStack.EMPTY;
		JsonElement resultJson = json.get("result");
		if (resultJson != null) {
			result = ItemStackTemplate.CODEC.parse(ops, resultJson).result().map(ItemStackTemplate::create).orElse(ItemStack.EMPTY);
		}
		if (result.isEmpty()) {
			// "Same item as the input" (e.g. map extending): show the first input as the result.
			SlotInfo first = inputs.getFirst();
			if (!first.stacks().isEmpty()) {
				result = first.stacks().getFirst().copyWithCount(1);
			} else if (first.tag().isPresent()) {
				var tagged = BuiltInRegistries.ITEM.getTagOrEmpty(first.tag().get()).iterator();
				if (tagged.hasNext()) result = new ItemStack(tagged.next());
			}
		}
		if (result.isEmpty()) return null;
		SlotInfo station = recipe.getType() == RecipeType.CRAFTING ? SlotInfo.of(new ItemStack(Items.CRAFTING_TABLE)) : SlotInfo.EMPTY;
		return new RecipeInfo(id, type, "shapeless", 0, 0, inputs, SlotInfo.of(result), station, 0, 0);
	}

	private List<SlotInfo> slots(List<SlotDisplay> displays) {
		List<SlotInfo> out = new ArrayList<>(displays.size());
		for (SlotDisplay display : displays) out.add(this.slot(display));
		return out;
	}

	private SlotInfo slot(SlotDisplay display) {
		return switch (display) {
			case SlotDisplay.Empty empty -> SlotInfo.EMPTY;
			case SlotDisplay.TagSlotDisplay tag when tag.tag() instanceof HolderSet.Named<Item> named -> new SlotInfo(Optional.of(named.key()), List.of());
			case SlotDisplay.WithRemainder remainder -> this.slot(remainder.input());
			default -> {
				List<ItemStack> stacks = new ArrayList<>();
				for (ItemStack stack : display.resolveForStacks(this.slotContext)) {
					if (!stack.isEmpty()) stacks.add(stack);
					if (stacks.size() >= 64) break;
				}
				yield new SlotInfo(Optional.empty(), stacks);
			}
		};
	}

	private SlotInfo potionSlot(PotionIngredient ingredient) {
		List<ItemStack> stacks = new ArrayList<>();
		List<Holder<Item>> items = ingredient.ingredient().items().toList();
		var potions = ingredient.potions().flatMap(p -> p.potions());
		for (Holder<Item> item : items) {
			if (potions.isPresent()) {
				for (var potion : potions.get()) stacks.add(PotionContents.createItemStack(item.value(), potion));
			} else {
				stacks.add(new ItemStack(item));
			}
		}
		return new SlotInfo(Optional.empty(), stacks);
	}

	// ---- advancements ----

	private List<AdvancementInfo> advancements() {
		List<AdvancementInfo> out = new ArrayList<>();
		for (AdvancementHolder holder : this.server.getAdvancements().getAllAdvancements()) {
			Advancement advancement = holder.value();
			Optional<DisplayInfo> display = advancement.display();
			if (display.isEmpty()) continue;
			JsonObject json = Json.object(Json.encode(Advancement.CODEC, advancement, this.registries));
			if (json == null) continue;
			Set<Identifier> items = new LinkedHashSet<>();
			Set<TagKey<Item>> tags = new LinkedHashSet<>();
			collectItems(json.get("criteria"), false, items, tags);
			DisplayInfo info = display.get();
			out.add(new AdvancementInfo(
				holder.id(), info.title(), info.description(), info.icon().create(), info.type().getSerializedName(), List.copyOf(items), List.copyOf(tags)
			));
		}
		return out;
	}

	/** Collects ids that appear under "items"/"item" keys, i.e. item predicates in criteria. */
	private static void collectItems(JsonElement element, boolean itemContext, Set<Identifier> items, Set<TagKey<Item>> tags) {
		if (element == null || element.isJsonNull()) return;
		if (element.isJsonPrimitive()) {
			if (!itemContext || !element.getAsJsonPrimitive().isString()) return;
			String value = element.getAsString();
			if (value.startsWith("#")) {
				Identifier tag = Identifier.tryParse(value.substring(1));
				if (tag != null) tags.add(TagKey.create(Registries.ITEM, tag));
			} else {
				Identifier id = Identifier.tryParse(value);
				if (id != null && BuiltInRegistries.ITEM.containsKey(id) && !id.equals(BuiltInRegistries.ITEM.getKey(Items.AIR))) items.add(id);
			}
		} else if (element.isJsonArray()) {
			for (JsonElement e : element.getAsJsonArray()) collectItems(e, itemContext, items, tags);
		} else {
			for (var entry : element.getAsJsonObject().entrySet()) {
				boolean isItemKey = entry.getKey().equals("items") || entry.getKey().equals("item");
				collectItems(entry.getValue(), isItemKey || itemContext && entry.getValue().isJsonArray(), items, tags);
			}
		}
	}

	// ---- item properties ----

	private List<ItemProps> itemProps() {
		List<ItemProps> out = new ArrayList<>();
		ServerLevel level = this.server.overworld();
		LootContext furnace = blockContext(level, Blocks.FURNACE.defaultBlockState());
		LootContext composter = blockContext(level, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 1));
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack stack = item.getDefaultInstance();
			CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
			Compostable compostable = stack.get(DataComponents.COMPOSTABLE);
			BrewingFuel brewingFuel = stack.get(DataComponents.BREWING_FUEL);
			if (fuel == null && compostable == null && brewingFuel == null) continue;
			int burn = 0;
			float compost = 0;
			int brew = 0;
			try {
				if (fuel != null) burn = fuel.burnTime().get(furnace, 0);
				if (compostable != null) compost = average(compostable.layers(), composter, 200);
				if (brewingFuel != null) brew = brewingFuel.uses().get(furnace, 0);
			} catch (RuntimeException e) {
				Wtfi.LOGGER.debug("Could not resolve properties of {}: {}", item, e.toString());
			}
			out.add(new ItemProps(BuiltInRegistries.ITEM.getKey(item), burn, compost, brew));
		}
		return out;
	}

	private static LootContext blockContext(ServerLevel level, net.minecraft.world.level.block.state.BlockState state) {
		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.BLOCK_STATE, state)
			.withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
			.create(LootContextParamSets.BLOCK_INTERACT);
		return new LootContext.Builder(params).create(Optional.empty());
	}

	private static float average(ResolvableInt value, LootContext context, int samples) {
		if (value instanceof ResolvableInt.Constant constant) return constant.value();
		long sum = 0;
		for (int i = 0; i < samples; i++) sum += value.get(context, 0);
		return sum / (float)samples;
	}

	// ---- tool interactions ----

	private List<TransformInfo> transforms() {
		List<TransformInfo> out = new ArrayList<>();
		this.registries.lookupOrThrow(Registries.BLOCK_TRANSFORMER).listElements().forEach(holder -> {
			JsonElement json = Json.encode(BlockTransformer.DIRECT_CODEC, holder.value(), this.registries);
			for (JsonElement transform : Json.list(json)) {
				JsonObject provider = Json.object(Json.object(transform) == null ? null : transform.getAsJsonObject().get("block_state_provider"));
				if (provider == null) continue;
				for (JsonElement ruleElement : Json.list(provider.get("rules"))) {
					JsonObject rule = Json.object(ruleElement);
					if (rule == null) continue;
					Set<Identifier> from = new LinkedHashSet<>();
					predicateBlocks(rule.get("if_true"), from);
					Set<Identifier> to = new LinkedHashSet<>();
					Json.strings(rule.get("then"), Set.of(), s -> {
						Identifier id = Identifier.tryParse(s);
						if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) to.add(id);
					});
					for (Identifier f : from) {
						for (Identifier t : to) out.add(new TransformInfo(holder.key().identifier(), f, t));
					}
				}
			}
		});
		return out;
	}

	private static void predicateBlocks(JsonElement element, Set<Identifier> out) {
		JsonObject o = Json.object(element);
		if (o == null) return;
		if (o.has("offset")) return; // checks a neighbour, not the clicked block
		switch (Json.type(o)) {
			case "matching_blocks" -> {
				for (JsonElement b : Json.list(o.get("blocks"))) {
					if (!b.isJsonPrimitive()) continue;
					String value = b.getAsString();
					if (value.startsWith("#")) {
						addBlockTag(value.substring(1), out);
					} else {
						Identifier id = Identifier.tryParse(value);
						if (id != null) out.add(id);
					}
				}
			}
			case "matching_block_tag" -> {
				String tag = Json.string(o, "tag");
				if (tag != null) addBlockTag(tag, out);
			}
			case "all_of", "any_of" -> {
				for (JsonElement p : Json.list(o.get("predicates"))) predicateBlocks(p, out);
			}
			default -> {
			}
		}
	}

	private static void addBlockTag(String tag, Set<Identifier> out) {
		Identifier id = Identifier.tryParse(tag);
		if (id == null) return;
		BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, id))
			.forEach(h -> h.unwrapKey().ifPresent(k -> out.add(k.identifier())));
	}
}
