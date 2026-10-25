package com.wtfi.client;

import com.wtfi.client.ui.page.ItemPageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** "What's this?": look at a block or mob and press the key to open its page. */
final class WorldLookup {
	private WorldLookup() {
	}

	static void tick(Minecraft minecraft) {
		while (WtfiKeys.LOOKUP_WORLD.consumeClick()) {
			if (minecraft.gui.screen() != null || minecraft.level == null || minecraft.player == null) continue;
			ItemStack target = targeted(minecraft);
			if (!target.isEmpty()) ItemPageScreen.open(minecraft, target, ItemPageScreen.Tab.OVERVIEW);
		}
	}

	private static ItemStack targeted(Minecraft minecraft) {
		HitResult hit = minecraft.hitResult;
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			ItemStack pick = entity.getPickResult();
			return pick == null ? ItemStack.EMPTY : pick.copyWithCount(1);
		}
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = minecraft.level.getBlockState(blockHit.getBlockPos());
			ItemStack stack = state.getCloneItemStack(minecraft.level, blockHit.getBlockPos(), false);
			if (stack.isEmpty()) stack = new ItemStack(state.getBlock().asItem());
			return stack.copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}
}
