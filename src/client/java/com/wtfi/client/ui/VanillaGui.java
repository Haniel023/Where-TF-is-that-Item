package com.wtfi.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Draws WTFI's GUI out of the game's own textures (creative tab, chest and widget sprites), so it
 * looks like vanilla and follows any resource pack that reskins the vanilla menus.
 */
public final class VanillaGui {
	private static final Identifier CREATIVE_TAB = Identifier.withDefaultNamespace("textures/gui/container/creative_inventory/tab_items.png");
	private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final Identifier CRAFTING = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");
	private static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	private static final Identifier SCROLLER_DISABLED = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");
	private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
	private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
	private static final Identifier SLOT_HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
	private static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
	private static final Identifier TOOLTIP_BG = Identifier.withDefaultNamespace("tooltip/background");
	private static final Identifier TOOLTIP_FRAME = Identifier.withDefaultNamespace("tooltip/frame");
	private static final Identifier CHECKBOX = Identifier.withDefaultNamespace("widget/checkbox");
	private static final Identifier CHECKBOX_SELECTED = Identifier.withDefaultNamespace("widget/checkbox_selected");
	public static final int TEXT_DARK = 0xFF404040;
	public static final int TEXT_LIGHT = 0xFFFFFFFF;
	public static final int TEXT_GRAY = 0xFFA0A0A0;
	public static final int TEXT_GOOD = 0xFF55FF55;
	public static final int TEXT_BAD = 0xFFFF5555;
	public static final int TEXT_GOLD = 0xFFFFAA00;
	public static final int TEXT_AQUA = 0xFF55FFFF;
	public static final int TEXT_BLUE = 0xFF5555FF;
	public static final int TEXT_DARK_GREEN = 0xFF00802B;
	public static final int TEXT_DARK_RED = 0xFFAA0000;
	public static final int SCROLLER_WIDTH = 14;

	private VanillaGui() {
	}

	/** A light grey container panel of any size, nine-sliced from the creative tab texture. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		final int c = 4;
		final int tw = 256;
		final int th = 256;
		final int right = 195 - c;
		final int bottom = 136 - c;
		// corners
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y, 0, 0, c, c, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + w - c, y, right, 0, c, c, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y + h - c, 0, bottom, c, c, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + w - c, y + h - c, right, bottom, c, c, tw, th);
		// edges, stretched from a 1px slice
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + c, y, c, 0, w - 2 * c, c, 1, c, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + c, y + h - c, c, bottom, w - 2 * c, c, 1, c, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y + c, 0, c, c, h - 2 * c, c, 1, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + w - c, y + c, right, c, c, h - 2 * c, c, 1, tw, th);
		// fill
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x + c, y + c, c + 1, c + 1, w - 2 * c, h - 2 * c, 1, 1, tw, th);
	}

	/** One empty 18x18 slot; the item goes at (x + 1, y + 1). */
	public static void slot(GuiGraphicsExtractor g, int x, int y) {
		g.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y, 7, 17, 18, 18, 256, 256);
	}

	/** The crafting table's result slot, size 26x26; the item goes at (x + 5, y + 5). */
	public static void bigSlot(GuiGraphicsExtractor g, int x, int y) {
		g.blit(RenderPipelines.GUI_TEXTURED, CRAFTING, x, y, 119, 30, 26, 26, 256, 256);
	}

	/** The crafting table's progress arrow (22x15), drawn from its texture. */
	public static void arrow(GuiGraphicsExtractor g, int x, int y) {
		g.blit(RenderPipelines.GUI_TEXTURED, CRAFTING, x, y, 89, 34, 22, 15, 256, 256);
	}

	/** Scrollbar track (14 wide) with the creative-menu scroller; progress 0..1. */
	public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, float progress, boolean enabled) {
		final int tw = 256;
		final int th = 256;
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y, 174, 17, 14, 2, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y + 2, 174, 19, 14, h - 4, 14, 1, tw, th);
		g.blit(RenderPipelines.GUI_TEXTURED, CREATIVE_TAB, x, y + h - 2, 174, 127, 14, 2, tw, th);
		int knobY = y + 1 + (int)((h - 17) * Math.max(0, Math.min(1, progress)));
		g.blitSprite(RenderPipelines.GUI_TEXTURED, enabled ? SCROLLER : SCROLLER_DISABLED, x + 1, knobY, 12, 15);
	}

	public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered, boolean active) {
		Identifier sprite = !active ? BUTTON_DISABLED : hovered ? BUTTON_HOVER : BUTTON;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
	}

	/** A vanilla button with an item icon centred in it. */
	public static void iconButton(GuiGraphicsExtractor g, int x, int y, int size, ItemStack icon, boolean hovered, boolean active) {
		button(g, x, y, size, size, hovered, active);
		g.item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
	}

	public static void textButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, Component text, boolean hovered, boolean active) {
		button(g, x, y, w, h, hovered, active);
		int color = active ? TEXT_LIGHT : TEXT_GRAY;
		g.centeredText(font, text, x + w / 2, y + (h - 8) / 2, color);
	}

	public static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean checked) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, checked ? CHECKBOX_SELECTED : CHECKBOX, x, y, 12, 12);
	}

	public static void slotHighlightBack(GuiGraphicsExtractor g, int itemX, int itemY) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, itemX - 4, itemY - 4, 24, 24);
	}

	public static void slotHighlightFront(GuiGraphicsExtractor g, int itemX, int itemY) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, itemX - 4, itemY - 4, 24, 24);
	}

	/** Dark tooltip-style box, used for popups and the HUD. */
	public static void darkBox(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, TOOLTIP_BG, x - 9, y - 9, w + 18, h + 18);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, TOOLTIP_FRAME, x - 9, y - 9, w + 18, h + 18);
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}
}
