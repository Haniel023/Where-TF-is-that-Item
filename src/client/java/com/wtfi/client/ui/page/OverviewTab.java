package com.wtfi.client.ui.page;

import static com.wtfi.client.ui.page.ItemPageScreen.t;

import com.wtfi.client.index.ItemStats;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.ui.VanillaGui;
import com.wtfi.data.WtfiDataset.AdvancementInfo;
import com.wtfi.data.WtfiDataset.ItemProps;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;

/** Properties, stats, comparison with what you're holding, and related advancements. */
final class OverviewTab {
	private OverviewTab() {
	}

	static List<Element> build(PageContext ctx) {
		List<Element> out = new ArrayList<>();
		ItemStack stack = ctx.pageStack();
		Knowledge knowledge = ctx.knowledge();

		if (knowledge != null) {
			mobInfo(knowledge, stack, out);
			blockDrops(knowledge, stack, out);
		}

		out.add(Elements.heading(t("wtfi.overview.properties")));
		out.add(Elements.stat(t("wtfi.prop.stack_size"), Component.literal(String.valueOf(stack.getMaxStackSize()))));
		var rarity = stack.getRarity();
		out.add(Elements.stat(t("wtfi.prop.rarity"), Component.literal(Sources.pretty(rarity.getSerializedName())).withStyle(readable(rarity.color()))));
		if (stack.isDamageableItem()) out.add(Elements.stat(t("wtfi.prop.durability"), Component.literal(String.valueOf(stack.getMaxDamage()))));
		ItemStats stats = ItemStats.of(stack);
		if (stats.isWeapon()) {
			out.add(Elements.stat(t("wtfi.prop.attack_damage"), num(stats.attackDamage())));
			out.add(Elements.stat(t("wtfi.prop.attack_speed"), num(stats.attackSpeed())));
			out.add(Elements.stat(t("wtfi.prop.dps"), num(stats.dps())));
		}
		if (stats.armor() > 0 || stats.toughness() > 0) {
			out.add(Elements.stat(t("wtfi.prop.armor"), num(stats.armor())));
			if (stats.toughness() > 0) out.add(Elements.stat(t("wtfi.prop.toughness"), num(stats.toughness())));
		}
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable != null) out.add(Elements.stat(t("wtfi.prop.slot"), Component.literal(Sources.pretty(equippable.slot().getName()))));
		Tool tool = stack.get(DataComponents.TOOL);
		if (tool != null && !stats.isWeapon()) {
			// Ignore "instant" rules like swords on cobwebs; they'd show a huge number.
			float best = tool.defaultMiningSpeed();
			for (Tool.Rule rule : tool.rules()) {
				float speed = rule.speed().orElse(0f);
				if (speed < 1000f) best = Math.max(best, speed);
			}
			if (best > 1f) out.add(Elements.stat(t("wtfi.prop.mining_speed"), num(best)));
		}
		if (stats.enchantability() > 0) out.add(Elements.stat(t("wtfi.prop.enchantability"), Component.literal(String.valueOf(stats.enchantability()))));
		var food = stack.get(DataComponents.FOOD);
		if (food != null) {
			out.add(Elements.stat(t("wtfi.prop.nutrition"), t("wtfi.prop.nutrition.value", food.nutrition(), num(food.nutrition() / 2.0))));
			out.add(Elements.stat(t("wtfi.prop.saturation"), num(food.saturation())));
		}
		var consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable != null) out.add(Elements.stat(t("wtfi.prop.consume_time"), Component.literal(num(consumable.consumeSeconds()).getString() + "s")));
		if (stack.has(DataComponents.DAMAGE_RESISTANT)) out.add(Elements.stat(t("wtfi.prop.resistant"), t("wtfi.yes")));
		if (stack.has(DataComponents.GLIDER)) out.add(Elements.stat(t("wtfi.prop.glider"), t("wtfi.yes")));
		if (stack.has(DataComponents.DEATH_PROTECTION)) out.add(Elements.stat(t("wtfi.prop.death_protection"), t("wtfi.yes")));

		if (stack.getItem() instanceof BlockItem blockItem) {
			Block block = blockItem.getBlock();
			var state = block.defaultBlockState();
			float hardness = block.defaultDestroyTime();
			out.add(Elements.stat(t("wtfi.prop.hardness"), hardness < 0 ? t("wtfi.prop.unbreakable") : num(hardness)));
			out.add(Elements.stat(t("wtfi.prop.blast_resistance"), num(block.getExplosionResistance())));
			if (state.getLightEmission() > 0) out.add(Elements.stat(t("wtfi.prop.light"), Component.literal(String.valueOf(state.getLightEmission()))));
			if (state.requiresCorrectToolForDrops()) out.add(Elements.stat(t("wtfi.prop.needs_tool"), t("wtfi.yes")));
		}
		if (knowledge != null) {
			ItemProps props = knowledge.props(stack.getItem());
			if (props != null) {
				if (props.burnTicks() > 0) out.add(Elements.stat(t("wtfi.prop.fuel"), t("wtfi.prop.fuel.value", num(props.burnTicks() / 20.0), num(props.burnTicks() / 200.0))));
				if (props.compostChance() > 0) out.add(Elements.stat(t("wtfi.prop.compost"), Component.literal(Sources.percent(Math.min(1f, props.compostChance())).replace("≈", ""))));
				if (props.brewingFuelUses() > 0) out.add(Elements.stat(t("wtfi.prop.brewing_fuel"), Component.literal(String.valueOf(props.brewingFuelUses()))));
			}
		}

		var repairable = stack.get(DataComponents.REPAIRABLE);
		if (repairable != null) {
			List<ItemStack> materials = new ArrayList<>();
			for (Holder<Item> h : repairable.items()) materials.add(new ItemStack(h));
			if (!materials.isEmpty()) {
				out.add(Elements.spacer(3));
				out.add(Elements.text(t("wtfi.prop.repair_with")));
				out.add(Elements.items(materials));
			}
		}

		compare(ctx, stack, stats, out);

		String tags = stack.tags().map(tag -> "#" + tag.location()).sorted().collect(Collectors.joining("  "));
		if (!tags.isEmpty()) {
			out.add(Elements.heading(t("wtfi.overview.tags")));
			out.add(Elements.text(Component.literal(tags), 0xFF555555));
		}

		if (knowledge != null) advancements(ctx, knowledge, stack, out);
		return out;
	}

	/** For a spawn egg (what the in-world lookup opens for mobs): what the mob drops and where it spawns. */
	private static void mobInfo(Knowledge knowledge, ItemStack stack, List<Element> out) {
		if (!(stack.getItem() instanceof net.minecraft.world.item.SpawnEggItem)) return;
		var type = net.minecraft.world.item.SpawnEggItem.getType(stack);
		if (type == null) return;
		net.minecraft.resources.Identifier entity = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
		List<com.wtfi.data.WtfiDataset.LootDrop> drops = new ArrayList<>();
		for (var table : knowledge.data.loot()) {
			if (table.kind().equals("entity") && Knowledge.entityOf(table.table()).equals(entity)) drops.add(table);
		}
		out.add(Elements.heading(t("wtfi.overview.mob_drops", type.getDescription())));
		if (drops.isEmpty()) {
			out.add(Elements.text(t("wtfi.overview.no_drops"), 0xFF707070));
		} else {
			drops.sort(java.util.Comparator.comparingDouble((com.wtfi.data.WtfiDataset.LootDrop d) -> -d.chance()));
			for (var drop : drops) out.add(Elements.source(List.of(drop.stack()), drop.stack().getHoverName(), Sources.lootDetails(drop), drop.stack()));
		}
		var spawns = knowledge.spawns(entity);
		if (!spawns.isEmpty()) {
			java.util.Set<net.minecraft.resources.Identifier> biomes = new java.util.LinkedHashSet<>();
			for (var spawn : spawns) biomes.addAll(spawn.biomes());
			List<net.minecraft.resources.Identifier> list = List.copyOf(biomes);
			out.add(Elements.text(t("wtfi.where.spawns_in", Sources.biomeSummary(knowledge, list, list.isEmpty() ? null : knowledge.dimensionOf(list.getFirst()), 6))));
		}
	}

	/** For a block: what breaking it drops. */
	private static void blockDrops(Knowledge knowledge, ItemStack stack, List<Element> out) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) return;
		var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
		var drops = knowledge.lootTable(id.withPrefix("blocks/"));
		if (drops.isEmpty()) return;
		boolean onlyItself = drops.size() == 1 && drops.getFirst().stack().is(stack.getItem()) && drops.getFirst().conditions().isEmpty();
		if (onlyItself) return;
		out.add(Elements.heading(t("wtfi.overview.block_drops")));
		for (var drop : drops) out.add(Elements.source(List.of(drop.stack()), drop.stack().getHoverName(), Sources.lootDetails(drop), drop.stack()));
	}

	private static void compare(PageContext ctx, ItemStack stack, ItemStats stats, List<Element> out) {
		Player player = ctx.minecraft().player;
		if (player == null) return;
		ItemStack other = ItemStack.EMPTY;
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		if (stats.isWeapon()) {
			other = player.getMainHandItem();
		} else if (equippable != null && equippable.slot().isArmor()) {
			other = player.getItemBySlot(equippable.slot());
		} else if (stack.has(DataComponents.TOOL)) {
			other = player.getMainHandItem();
		}
		if (other.isEmpty() || ItemStack.isSameItemSameComponents(other, stack)) return;
		ItemStats theirs = ItemStats.of(other);
		out.add(Elements.heading(t("wtfi.overview.compare", other.getHoverName())));
		if (stats.isWeapon() || theirs.isWeapon()) {
			out.add(Elements.stat(t("wtfi.prop.attack_damage"), delta(stats.attackDamage(), theirs.attackDamage())));
			out.add(Elements.stat(t("wtfi.prop.attack_speed"), delta(stats.attackSpeed(), theirs.attackSpeed())));
			out.add(Elements.stat(t("wtfi.prop.dps"), delta(stats.dps(), theirs.dps())));
		}
		if (stats.armor() > 0 || theirs.armor() > 0) {
			out.add(Elements.stat(t("wtfi.prop.armor"), delta(stats.armor(), theirs.armor())));
			out.add(Elements.stat(t("wtfi.prop.toughness"), delta(stats.toughness(), theirs.toughness())));
		}
		if (stats.durability() > 0 || theirs.durability() > 0) {
			out.add(Elements.stat(t("wtfi.prop.durability"), delta(stats.durability(), theirs.durability())));
		}
		if (stats.enchantability() > 0 || theirs.enchantability() > 0) {
			out.add(Elements.stat(t("wtfi.prop.enchantability"), delta(stats.enchantability(), theirs.enchantability())));
		}
	}

	private static Component delta(double mine, double theirs) {
		double d = mine - theirs;
		MutableComponent value = num(mine).copy();
		if (Math.abs(d) < 1e-6) return value.append(Component.literal("  (=)").withStyle(ChatFormatting.GRAY));
		String sign = d > 0 ? "+" : "";
		return value.append(Component.literal("  (" + sign + num(d).getString() + ")").withStyle(d > 0 ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
	}

	private static void advancements(PageContext ctx, Knowledge knowledge, ItemStack stack, List<Element> out) {
		List<AdvancementInfo> list = knowledge.advancements(stack.getItem());
		if (list.isEmpty()) return;
		out.add(Elements.heading(t("wtfi.overview.advancements")));
		var connection = ctx.minecraft().getConnection();
		ClientAdvancements progress = connection == null ? null : connection.getAdvancements();
		for (AdvancementInfo info : list) {
			boolean done = false;
			if (progress != null) {
				var holder = progress.get(info.id());
				if (holder != null) {
					var p = progress.progress().get(holder);
					done = p != null && p.isDone();
				}
			}
			Component status = done
				? Component.translatable("wtfi.advancement.done").withStyle(ChatFormatting.DARK_GREEN)
				: Component.translatable("wtfi.advancement.todo").withStyle(ChatFormatting.DARK_GRAY);
			out.add(Elements.source(List.of(info.icon()), Component.empty().append(info.title()).append("  ").append(status), List.of(info.description()), null));
		}
	}

	/** Vanilla text colours are made for dark backgrounds; swap the light ones for darker shades. */
	static ChatFormatting readable(ChatFormatting color) {
		return switch (color) {
			case WHITE, GRAY -> ChatFormatting.DARK_GRAY;
			case YELLOW -> ChatFormatting.GOLD;
			case AQUA -> ChatFormatting.DARK_AQUA;
			case LIGHT_PURPLE -> ChatFormatting.DARK_PURPLE;
			case GREEN -> ChatFormatting.DARK_GREEN;
			case RED -> ChatFormatting.DARK_RED;
			case BLUE -> ChatFormatting.DARK_BLUE;
			default -> color;
		};
	}

	/** An enchantment name in a colour readable on the light page (curses stay red). */
	static Component enchantName(Holder<net.minecraft.world.item.enchantment.Enchantment> holder, int level) {
		boolean curse = holder.is(net.minecraft.tags.EnchantmentTags.CURSE);
		return Component.literal(net.minecraft.world.item.enchantment.Enchantment.getFullname(holder, level).getString())
			.withStyle(curse ? ChatFormatting.DARK_RED : ChatFormatting.BLACK);
	}

	static Component num(double value) {
		if (Math.abs(value - Math.round(value)) < 1e-4) return Component.literal(String.valueOf(Math.round(value)));
		return Component.literal(String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ""));
	}

}
