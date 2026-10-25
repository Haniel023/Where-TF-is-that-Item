package com.wtfi.client.cheat;

import com.wtfi.net.WtfiNetworking.GivePayload;
import java.util.StringJoiner;
import net.minecraft.core.component.TypedDataComponent;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Cheat mode: puts a stack in the player's inventory using the best channel available.
 * <ol>
 *   <li>Server has WTFI: ask it (it re-checks permissions).</li>
 *   <li>Creative mode on a vanilla server: the creative inventory packet.</li>
 *   <li>Operator on a vanilla server: a /give command.</li>
 * </ol>
 */
public final class CheatGiver {
	private CheatGiver() {
	}

	public static boolean canCheat(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		return player != null && (player.hasInfiniteMaterials() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
	}

	public static void give(Minecraft minecraft, ItemStack stack, boolean fullStack) {
		LocalPlayer player = minecraft.player;
		if (player == null || stack.isEmpty()) return;
		if (!canCheat(minecraft)) {
			player.sendOverlayMessage(Component.translatable("wtfi.cheat.no_permission").withStyle(ChatFormatting.RED));
			return;
		}
		int count = fullStack ? stack.getMaxStackSize() : 1;
		ItemStack give = stack.copyWithCount(count);

		if (ClientPlayNetworking.canSend(GivePayload.TYPE)) {
			ClientPlayNetworking.send(new GivePayload(give));
		} else if (player.hasInfiniteMaterials()) {
			Inventory inventory = player.getInventory();
			int free = inventory.getFreeSlot();
			if (free < 0) {
				minecraft.gameMode.handleCreativeModeItemDrop(give);
			} else {
				inventory.setItem(free, give);
				// InventoryMenu numbers the hotbar 36-44 and the main inventory 9-35.
				int menuSlot = free < 9 ? 36 + free : free;
				minecraft.gameMode.handleCreativeModeItemAdd(give, menuSlot);
			}
		} else {
			player.connection.sendCommand("give @s " + itemArgument(minecraft, give) + " " + count);
		}
		player.sendOverlayMessage(Component.translatable("wtfi.cheat.given", count, give.getHoverName()));
	}

	/** The stack as a /give item argument, e.g. {@code minecraft:enchanted_book[stored_enchantments={...}]}. */
	static String itemArgument(Minecraft minecraft, ItemStack stack) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		var patch = stack.getComponentsPatch();
		if (patch.isEmpty()) return id;
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, minecraft.getConnection().registryAccess());
		StringJoiner parts = new StringJoiner(",", "[", "]");
		var split = patch.split();
		for (TypedDataComponent<?> component : split.added()) {
			String key = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()));
			String value = encode(component.type(), component.value(), ops);
			if (value != null) parts.add(key + "=" + value);
		}
		for (DataComponentType<?> removed : split.removed()) {
			parts.add("!" + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(removed));
		}
		return id + parts;
	}

	@SuppressWarnings("unchecked")
	private static <T> String encode(DataComponentType<T> type, Object value, RegistryOps<Tag> ops) {
		if (type.codec() == null) return null;
		return type.codec().encodeStart(ops, (T)value).result().map(Tag::toString).orElse(null);
	}
}
