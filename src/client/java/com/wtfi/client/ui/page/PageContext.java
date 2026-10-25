package com.wtfi.client.ui.page;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.knowledge.Knowledge;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** What page elements can ask of the page they're on. */
public interface PageContext {
	Minecraft minecraft();

	Font font();

	WtfiSession session();

	@Nullable Knowledge knowledge();

	ItemStack pageStack();

	/** Open another item's page (keeps history so Back works). */
	void navigate(ItemStack stack, ItemPageScreen.Tab tab);

	void tooltip(List<Component> lines);

	void itemTooltip(ItemStack stack, @Nullable Component extra);

	/** Which of several alternatives to show right now; cycles once a second. */
	ItemStack pick(List<ItemStack> options);

	/**
	 * Draws one slot showing {@code options} (cycling), with hover highlight and tooltip.
	 * Returns the stack under the mouse, or EMPTY.
	 */
	ItemStack slot(GuiGraphicsExtractor g, int x, int y, List<ItemStack> options, int mouseX, int mouseY, @Nullable Component extra);

	/** Rebuild the page content (e.g. after a background calculation finished). */
	void rebuild();
}
