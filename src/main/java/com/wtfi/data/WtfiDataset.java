package com.wtfi.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Everything WTFI knows that only a server has: recipes, loot tables, trades, world generation,
 * advancements and resolved item properties. Built on the server, sent to clients as compressed
 * NBT, and shipped in the jar (vanilla only) for servers without WTFI.
 */
public record WtfiDataset(
	int format,
	List<RecipeInfo> recipes,
	List<LootDrop> loot,
	List<TradeInfo> trades,
	List<GenInfo> worldgen,
	List<StructureInfo> structures,
	List<SpawnInfo> spawns,
	List<AdvancementInfo> advancements,
	List<ItemProps> itemProps,
	List<TransformInfo> transforms
) {
	public static final int FORMAT = 1;

	public static final Codec<WtfiDataset> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("format").forGetter(WtfiDataset::format),
		WtfiCodecs.lenientList(RecipeInfo.CODEC).fieldOf("recipes").forGetter(WtfiDataset::recipes),
		WtfiCodecs.lenientList(LootDrop.CODEC).fieldOf("loot").forGetter(WtfiDataset::loot),
		WtfiCodecs.lenientList(TradeInfo.CODEC).fieldOf("trades").forGetter(WtfiDataset::trades),
		WtfiCodecs.lenientList(GenInfo.CODEC).fieldOf("worldgen").forGetter(WtfiDataset::worldgen),
		WtfiCodecs.lenientList(StructureInfo.CODEC).fieldOf("structures").forGetter(WtfiDataset::structures),
		WtfiCodecs.lenientList(SpawnInfo.CODEC).fieldOf("spawns").forGetter(WtfiDataset::spawns),
		WtfiCodecs.lenientList(AdvancementInfo.CODEC).fieldOf("advancements").forGetter(WtfiDataset::advancements),
		WtfiCodecs.lenientList(ItemProps.CODEC).fieldOf("item_props").forGetter(WtfiDataset::itemProps),
		WtfiCodecs.lenientList(TransformInfo.CODEC).fieldOf("transforms").forGetter(WtfiDataset::transforms)
	).apply(i, WtfiDataset::new));

	public static WtfiDataset empty() {
		return new WtfiDataset(FORMAT, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}

	/**
	 * Only the "minecraft" namespace, for the dataset bundled in the jar: whatever mods happen to
	 * be in the dev environment must not leak into the vanilla fallback.
	 */
	public WtfiDataset vanillaOnly() {
		java.util.function.Predicate<Identifier> v = id -> id.getNamespace().equals("minecraft");
		java.util.function.Predicate<ItemStack> vs = s -> v.test(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()));
		java.util.function.Predicate<SlotInfo> vslot = s -> s.tag().map(t -> v.test(t.location())).orElse(true) && s.stacks().stream().allMatch(vs);
		return new WtfiDataset(
			this.format,
			this.recipes.stream().filter(r -> v.test(r.id()) && v.test(r.type()) && vslot.test(r.output()) && r.inputs().stream().allMatch(vslot)).toList(),
			this.loot.stream().filter(d -> v.test(d.table()) && vs.test(d.stack()) && d.enchantments().stream().allMatch(v)).toList(),
			this.trades.stream().filter(t -> v.test(t.trader()) && vs.test(t.gives()) && t.wants().stream().allMatch(vs)).toList(),
			this.worldgen.stream().filter(g -> v.test(g.block()) && v.test(g.dimension()) && g.biomes().stream().allMatch(v)).toList(),
			this.structures.stream().filter(s -> v.test(s.id())).toList(),
			this.spawns.stream().filter(s -> v.test(s.entity())).toList(),
			this.advancements.stream().filter(a -> v.test(a.id())).toList(),
			this.itemProps.stream().filter(p -> v.test(p.item())).toList(),
			this.transforms.stream().filter(t -> v.test(t.transformer()) && v.test(t.from()) && v.test(t.to())).toList()
		);
	}

	/** One recipe slot: either a whole item tag (resolved on the client) or explicit stacks. */
	public record SlotInfo(Optional<TagKey<Item>> tag, List<ItemStack> stacks) {
		public static final SlotInfo EMPTY = new SlotInfo(Optional.empty(), List.of());
		public static final Codec<SlotInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			WtfiCodecs.ITEM_TAG.optionalFieldOf("tag").forGetter(SlotInfo::tag),
			WtfiCodecs.lenientList(ItemStack.CODEC).optionalFieldOf("stacks", List.of()).forGetter(SlotInfo::stacks)
		).apply(i, SlotInfo::new));

		public static SlotInfo of(ItemStack stack) {
			return stack.isEmpty() ? EMPTY : new SlotInfo(Optional.empty(), List.of(stack));
		}

		public boolean isEmpty() {
			return this.tag.isEmpty() && this.stacks.isEmpty();
		}
	}

	/**
	 * A recipe flattened to a layout the client knows how to draw.
	 * Layouts: shaped, shapeless, furnace, stonecutter, smithing, brewing, generic.
	 */
	public record RecipeInfo(
		Identifier id,
		Identifier type,
		String layout,
		int width,
		int height,
		List<SlotInfo> inputs,
		SlotInfo output,
		SlotInfo station,
		int time,
		float xp
	) {
		public static final Codec<RecipeInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("id").forGetter(RecipeInfo::id),
			Identifier.CODEC.fieldOf("type").forGetter(RecipeInfo::type),
			Codec.STRING.fieldOf("layout").forGetter(RecipeInfo::layout),
			Codec.INT.optionalFieldOf("w", 0).forGetter(RecipeInfo::width),
			Codec.INT.optionalFieldOf("h", 0).forGetter(RecipeInfo::height),
			SlotInfo.CODEC.listOf().fieldOf("in").forGetter(RecipeInfo::inputs),
			SlotInfo.CODEC.fieldOf("out").forGetter(RecipeInfo::output),
			SlotInfo.CODEC.optionalFieldOf("station", SlotInfo.EMPTY).forGetter(RecipeInfo::station),
			Codec.INT.optionalFieldOf("time", 0).forGetter(RecipeInfo::time),
			Codec.FLOAT.optionalFieldOf("xp", 0f).forGetter(RecipeInfo::xp)
		).apply(i, RecipeInfo::new));
	}

	/**
	 * One item a loot table can produce. Chance is the approximate probability that a single
	 * use of the table (one chest, one kill, one block) yields at least one, or -1 if unknown.
	 */
	public record LootDrop(
		Identifier table,
		String kind,
		ItemStack stack,
		List<Identifier> enchantments,
		float chance,
		int min,
		int max,
		List<String> conditions
	) {
		public static final Codec<LootDrop> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("table").forGetter(LootDrop::table),
			Codec.STRING.fieldOf("kind").forGetter(LootDrop::kind),
			ItemStack.CODEC.fieldOf("stack").forGetter(LootDrop::stack),
			Identifier.CODEC.listOf().optionalFieldOf("ench", List.of()).forGetter(LootDrop::enchantments),
			Codec.FLOAT.optionalFieldOf("chance", -1f).forGetter(LootDrop::chance),
			Codec.INT.optionalFieldOf("min", 1).forGetter(LootDrop::min),
			Codec.INT.optionalFieldOf("max", 1).forGetter(LootDrop::max),
			Codec.STRING.listOf().optionalFieldOf("cond", List.of()).forGetter(LootDrop::conditions)
		).apply(i, LootDrop::new));
	}

	/**
	 * A villager or wandering trader offer. Level 0 means the trader has no levels. A variable
	 * price means the first wanted stack's count depends on the roll (enchanted books).
	 */
	public record TradeInfo(Identifier trader, int level, List<ItemStack> wants, ItemStack gives, List<Identifier> enchantments, int maxUses, boolean variablePrice) {
		public static final Codec<TradeInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("trader").forGetter(TradeInfo::trader),
			Codec.INT.fieldOf("level").forGetter(TradeInfo::level),
			ItemStack.CODEC.listOf().fieldOf("wants").forGetter(TradeInfo::wants),
			ItemStack.CODEC.fieldOf("gives").forGetter(TradeInfo::gives),
			Identifier.CODEC.listOf().optionalFieldOf("ench", List.of()).forGetter(TradeInfo::enchantments),
			Codec.INT.optionalFieldOf("max_uses", 0).forGetter(TradeInfo::maxUses),
			Codec.BOOL.optionalFieldOf("variable_price", false).forGetter(TradeInfo::variablePrice)
		).apply(i, TradeInfo::new));
	}

	/** A block placed by a world generation feature in some biomes, within a height range. */
	public record GenInfo(
		Identifier block,
		Identifier feature,
		String featureType,
		Identifier dimension,
		List<Identifier> biomes,
		int minY,
		int maxY,
		int peakY,
		String distribution,
		float perChunk
	) {
		public static final Codec<GenInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("block").forGetter(GenInfo::block),
			Identifier.CODEC.fieldOf("feature").forGetter(GenInfo::feature),
			Codec.STRING.fieldOf("feature_type").forGetter(GenInfo::featureType),
			Identifier.CODEC.fieldOf("dimension").forGetter(GenInfo::dimension),
			Identifier.CODEC.listOf().fieldOf("biomes").forGetter(GenInfo::biomes),
			Codec.INT.optionalFieldOf("min_y", Integer.MIN_VALUE).forGetter(GenInfo::minY),
			Codec.INT.optionalFieldOf("max_y", Integer.MAX_VALUE).forGetter(GenInfo::maxY),
			Codec.INT.optionalFieldOf("peak_y", Integer.MIN_VALUE).forGetter(GenInfo::peakY),
			Codec.STRING.optionalFieldOf("distribution", "").forGetter(GenInfo::distribution),
			Codec.FLOAT.optionalFieldOf("per_chunk", -1f).forGetter(GenInfo::perChunk)
		).apply(i, GenInfo::new));

		public boolean hasHeight() {
			return this.minY != Integer.MIN_VALUE && this.maxY != Integer.MAX_VALUE;
		}
	}

	public record StructureInfo(Identifier id, Identifier dimension, List<Identifier> biomes) {
		public static final Codec<StructureInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("id").forGetter(StructureInfo::id),
			Identifier.CODEC.fieldOf("dimension").forGetter(StructureInfo::dimension),
			Identifier.CODEC.listOf().fieldOf("biomes").forGetter(StructureInfo::biomes)
		).apply(i, StructureInfo::new));
	}

	/** Natural mob spawns: where an entity type can spawn. */
	public record SpawnInfo(Identifier entity, String category, List<Identifier> biomes) {
		public static final Codec<SpawnInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("entity").forGetter(SpawnInfo::entity),
			Codec.STRING.fieldOf("category").forGetter(SpawnInfo::category),
			Identifier.CODEC.listOf().fieldOf("biomes").forGetter(SpawnInfo::biomes)
		).apply(i, SpawnInfo::new));
	}

	public record AdvancementInfo(
		Identifier id,
		Component title,
		Component description,
		ItemStack icon,
		String frame,
		List<Identifier> items,
		List<TagKey<Item>> itemTags
	) {
		public static final Codec<AdvancementInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("id").forGetter(AdvancementInfo::id),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(AdvancementInfo::title),
			ComponentSerialization.CODEC.fieldOf("description").forGetter(AdvancementInfo::description),
			ItemStack.OPTIONAL_CODEC.fieldOf("icon").forGetter(AdvancementInfo::icon),
			Codec.STRING.fieldOf("frame").forGetter(AdvancementInfo::frame),
			Identifier.CODEC.listOf().fieldOf("items").forGetter(AdvancementInfo::items),
			WtfiCodecs.ITEM_TAG.listOf().fieldOf("item_tags").forGetter(AdvancementInfo::itemTags)
		).apply(i, AdvancementInfo::new));
	}

	/**
	 * Item properties whose values live in server-only registries: furnace burn time, average
	 * composter layers per item, brewing fuel uses.
	 */
	public record ItemProps(Identifier item, int burnTicks, float compostChance, int brewingFuelUses) {
		public static final Codec<ItemProps> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("item").forGetter(ItemProps::item),
			Codec.INT.optionalFieldOf("burn", 0).forGetter(ItemProps::burnTicks),
			Codec.FLOAT.optionalFieldOf("compost", 0f).forGetter(ItemProps::compostChance),
			Codec.INT.optionalFieldOf("brew_fuel", 0).forGetter(ItemProps::brewingFuelUses)
		).apply(i, ItemProps::new));
	}

	/** A tool interaction from a block transformer: using the tool on {@code from} gives {@code to}. */
	public record TransformInfo(Identifier transformer, Identifier from, Identifier to) {
		public static final Codec<TransformInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("transformer").forGetter(TransformInfo::transformer),
			Identifier.CODEC.fieldOf("from").forGetter(TransformInfo::from),
			Identifier.CODEC.fieldOf("to").forGetter(TransformInfo::to)
		).apply(i, TransformInfo::new));
	}
}
