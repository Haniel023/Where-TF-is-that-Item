package com.wtfi.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int wtfi$getLeftPos();

	@Accessor("topPos")
	int wtfi$getTopPos();

	@Accessor("imageWidth")
	int wtfi$getImageWidth();

	@Accessor("imageHeight")
	int wtfi$getImageHeight();

	@Accessor("hoveredSlot")
	@Nullable Slot wtfi$getHoveredSlot();
}
