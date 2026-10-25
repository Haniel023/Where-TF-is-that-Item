package com.wtfi.server;

import com.wtfi.Wtfi;
import com.wtfi.net.WtfiNetworking.GivePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/** Handles cheat mode requests. Only creative players and operators (permission level 2) get items. */
public final class GiveHandler {
	private GiveHandler() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(GivePayload.TYPE, (payload, context) -> handle(context.player(), payload.stack()));
	}

	public static boolean canCheat(ServerPlayer player) {
		return player.hasInfiniteMaterials() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void handle(ServerPlayer player, ItemStack requested) {
		if (requested.isEmpty()) return;
		if (!canCheat(player)) {
			player.sendSystemMessage(Component.translatable("wtfi.cheat.denied"));
			return;
		}
		int count = Math.max(1, Math.min(requested.getCount(), requested.getMaxStackSize()));
		ItemStack stack = requested.copyWithCount(count);
		Wtfi.LOGGER.info("WTFI cheat mode: gave {} x {} to {}", count, stack.getItem(), player.getName().getString());
		player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		player.containerMenu.broadcastChanges();
	}
}
