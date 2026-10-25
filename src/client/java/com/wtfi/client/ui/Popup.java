package com.wtfi.client.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** A small dropdown list (dark tooltip style) with an icon, a label and a checkbox per row. */
public final class Popup {
	public static final int ROW_HEIGHT = 18;
	private final List<Row> rows;
	public final int x;
	public final int y;
	public final int width;
	public final int height;
	private int scroll;

	public Popup(List<Row> rows, int anchorX, int anchorY, int maxWidth, int maxBottom, Font font) {
		this.rows = rows;
		int textWidth = 0;
		for (Row row : rows) textWidth = Math.max(textWidth, font.width(row.label));
		this.width = Math.min(maxWidth, textWidth + 18 + 16 + 8);
		int wanted = rows.size() * ROW_HEIGHT;
		this.height = Math.max(ROW_HEIGHT, Math.min(wanted, maxBottom - anchorY));
		this.x = anchorX;
		this.y = anchorY;
	}

	public void extract(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		VanillaGui.darkBox(g, this.x, this.y, this.width, this.height);
		g.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
		int first = this.scroll;
		int visible = this.height / ROW_HEIGHT + 1;
		for (int i = first; i < Math.min(this.rows.size(), first + visible); i++) {
			Row row = this.rows.get(i);
			int ry = this.y + (i - first) * ROW_HEIGHT;
			boolean hovered = VanillaGui.inside(mouseX, mouseY, this.x, ry, this.width, ROW_HEIGHT);
			if (hovered) g.fill(this.x - 2, ry, this.x + this.width + 2, ry + ROW_HEIGHT, 0x30FFFFFF);
			if (!row.icon.isEmpty()) g.item(row.icon, this.x, ry + 1);
			g.text(font, row.label, this.x + 18, ry + 5, row.checked != null && row.checked.getAsBoolean() ? VanillaGui.TEXT_GOLD : VanillaGui.TEXT_LIGHT);
			if (row.checked != null) VanillaGui.checkbox(g, this.x + this.width - 13, ry + 3, row.checked.getAsBoolean());
		}
		g.disableScissor();
	}

	public boolean contains(double mx, double my) {
		return VanillaGui.inside(mx, my, this.x - 4, this.y - 4, this.width + 8, this.height + 8);
	}

	/** Runs the clicked row's action. Returns true if a row was hit. */
	public boolean click(double mx, double my) {
		if (!VanillaGui.inside(mx, my, this.x, this.y, this.width, this.height)) return false;
		int index = this.scroll + (int)((my - this.y) / ROW_HEIGHT);
		if (index < 0 || index >= this.rows.size()) return false;
		this.rows.get(index).action.run();
		return true;
	}

	public void scroll(double amount) {
		int visible = this.height / ROW_HEIGHT;
		int max = Math.max(0, this.rows.size() - visible);
		this.scroll = Math.max(0, Math.min(max, this.scroll - (int)Math.signum(amount)));
	}

	public record Row(ItemStack icon, Component label, BooleanSupplier checked, Runnable action) {
	}
}
