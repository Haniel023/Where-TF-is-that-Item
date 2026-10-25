package com.wtfi.client.ui.page;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** One block of content on an item page. Elements stack vertically inside the scroll area. */
public interface Element {
	int height(int width);

	void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY);

	/** Return true if the click was used. Coordinates are absolute screen coordinates. */
	default boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
		return false;
	}
}
