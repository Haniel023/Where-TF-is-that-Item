package com.wtfi.client.hud;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.memory.StorageMemory;
import com.wtfi.data.WtfiDataset.GenInfo;
import com.wtfi.data.WtfiDataset.LootDrop;
import com.wtfi.data.WtfiDataset.SpawnInfo;
import com.wtfi.data.WtfiDataset.StructureInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * Hunt mode: one pinned item, and live hints on the HUD about finding it from where you stand
 * (right height? right biome? nearest chest that has it? which mobs here drop it?).
 */
public final class HuntTarget {
	private static ItemStack target = ItemStack.EMPTY;
	private static List<Component> lines = List.of();
	private static long computedAt;

	private HuntTarget() {
	}

	public static boolean isTarget(ItemStack stack) {
		return !target.isEmpty() && ItemStack.isSameItemSameComponents(target, stack);
	}

	public static void toggle(ItemStack stack) {
		target = isTarget(stack) ? ItemStack.EMPTY : stack.copyWithCount(1);
		computedAt = 0;
	}

	public static void clear() {
		target = ItemStack.EMPTY;
		lines = List.of();
	}

	public static ItemStack target() {
		return target;
	}

	/** HUD lines, recomputed at most twice a second. */
	public static List<Component> lines(Minecraft minecraft) {
		if (target.isEmpty()) return List.of();
		long now = Util.getMillis();
		if (now - computedAt < 500) return lines;
		computedAt = now;
		lines = compute(minecraft);
		return lines;
	}

	private static List<Component> compute(Minecraft minecraft) {
		List<Component> out = new ArrayList<>();
		WtfiSession session = WtfiSession.get();
		LocalPlayer player = minecraft.player;
		if (session == null || player == null || minecraft.level == null) return out;
		Knowledge knowledge = session.knowledge();
		BlockPos pos = player.blockPosition();
		Identifier dimension = minecraft.level.dimension().identifier();
		Identifier biome = minecraft.level.getBiome(pos).unwrapKey().map(ResourceKey::identifier).orElse(null);

		int have = WtfiSession.inventoryCounts(player.getInventory()).getOrDefault(target.getItem(), 0);
		if (have > 0) out.add(Component.translatable("wtfi.hunt.have", have).withStyle(ChatFormatting.GREEN));

		StorageMemory storage = session.storage();
		if (storage != null) {
			List<StorageMemory.Found> found = storage.find(target, dimension, pos);
			if (!found.isEmpty()) {
				StorageMemory.Found nearest = found.getFirst();
				BlockPos at = nearest.stored().pos();
				if (nearest.stored().isEnderChest()) {
					out.add(Component.translatable("wtfi.hunt.ender_chest", nearest.count()).withStyle(ChatFormatting.AQUA));
				} else if (nearest.stored().dimension().equals(dimension)) {
					int distance = (int)Math.sqrt(at.distSqr(pos));
					out.add(Component.translatable("wtfi.hunt.storage", nearest.count(), distance, com.wtfi.client.ui.page.Sources.direction(pos, at)).withStyle(ChatFormatting.AQUA));
				}
			}
		}
		if (knowledge == null) return out;

		// Natural generation in this dimension: height advice and biome check.
		GenInfo best = null;
		for (Knowledge.GenSource source : knowledge.generationSources(target.getItem())) {
			GenInfo gen = source.gen();
			if (!gen.dimension().equals(dimension)) continue;
			if (best == null || gen.hasHeight() && !best.hasHeight() || biome != null && gen.biomes().contains(biome) && !best.biomes().contains(biome)) best = gen;
		}
		if (best != null) {
			boolean inBiome = biome != null && best.biomes().contains(biome);
			if (best.hasHeight()) {
				int targetY = best.peakY() != Integer.MIN_VALUE ? best.peakY() : (best.minY() + best.maxY()) / 2;
				int y = pos.getY();
				if (y >= best.minY() && y <= best.maxY()) {
					out.add(Component.translatable("wtfi.hunt.height_ok", best.minY(), best.maxY()).withStyle(ChatFormatting.GREEN));
				} else {
					int dy = targetY - y;
					out.add(Component.translatable(dy < 0 ? "wtfi.hunt.go_down" : "wtfi.hunt.go_up", Math.abs(dy), targetY).withStyle(ChatFormatting.GOLD));
				}
			} else {
				out.add(Component.translatable("wtfi.hunt.generates").withStyle(ChatFormatting.GREEN));
			}
			out.add(Component.translatable(inBiome ? "wtfi.hunt.biome_yes" : "wtfi.hunt.biome_no").withStyle(inBiome ? ChatFormatting.GREEN : ChatFormatting.RED));
		}

		// Structures that can hold it and can spawn in this biome.
		if (biome != null) {
			List<Component> structures = new ArrayList<>();
			for (LootDrop drop : knowledge.loot(target)) {
				if (!drop.kind().equals("chest") && !drop.kind().equals("archaeology")) continue;
				for (StructureInfo s : com.wtfi.client.ui.page.Sources.structuresFor(knowledge, drop.table())) {
					if (s.biomes().contains(biome)) {
						Component name = com.wtfi.client.ui.page.Sources.structureName(s.id());
						if (structures.stream().noneMatch(c -> c.getString().equals(name.getString()))) structures.add(name);
					}
				}
			}
			if (!structures.isEmpty()) {
				out.add(Component.translatable("wtfi.hunt.structures", join(structures, 3)).withStyle(ChatFormatting.YELLOW));
			}
			List<Component> mobs = new ArrayList<>();
			for (LootDrop drop : knowledge.loot(target)) {
				if (!drop.kind().equals("entity")) continue;
				Identifier entity = Knowledge.entityOf(drop.table());
				for (SpawnInfo spawn : knowledge.spawns(entity)) {
					if (spawn.biomes().contains(biome)) {
						Component name = com.wtfi.client.ui.page.Sources.entityName(entity);
						if (mobs.stream().noneMatch(c -> c.getString().equals(name.getString()))) mobs.add(name);
						break;
					}
				}
			}
			if (!mobs.isEmpty()) out.add(Component.translatable("wtfi.hunt.mobs", join(mobs, 3)).withStyle(ChatFormatting.YELLOW));
		}
		if (out.isEmpty()) out.add(Component.translatable("wtfi.hunt.nothing_here").withStyle(ChatFormatting.GRAY));
		return out;
	}

	private static Component join(List<Component> parts, int limit) {
		var out = Component.empty();
		for (int i = 0; i < Math.min(limit, parts.size()); i++) {
			if (i > 0) out.append(", ");
			out.append(parts.get(i));
		}
		if (parts.size() > limit) out.append(" +" + (parts.size() - limit));
		return out;
	}
}
