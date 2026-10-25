package com.wtfi.client.ui.page;

import com.wtfi.client.ui.VanillaGui;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/** The basic building blocks of item pages. */
public final class Elements {
	private Elements() {
	}

	public static Element heading(Component text) {
		return new Heading(text);
	}

	public static Element text(Component text) {
		return new Paragraph(text, VanillaGui.TEXT_DARK);
	}

	public static Element text(Component text, int color) {
		return new Paragraph(text, color);
	}

	public static Element spacer(int height) {
		return new Spacer(height);
	}

	public static Element stat(Component key, Component value) {
		return new Stat(key, value);
	}

	public static Element items(List<ItemStack> items) {
		return new ItemFlow(items);
	}

	public static Element source(List<ItemStack> icon, Component title, List<Component> details, @Nullable ItemStack target) {
		return new Source(icon, title, details, target);
	}

	public static Element buttons(List<ButtonSpec> buttons) {
		return new Buttons(buttons);
	}

	public record ButtonSpec(Component label, Runnable action, BooleanSupplier enabled, @Nullable Component tooltip) {
		public ButtonSpec(Component label, Runnable action) {
			this(label, action, () -> true, null);
		}
	}

	record Spacer(int h) implements Element {
		@Override
		public int height(int width) {
			return this.h;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
		}
	}

	record Heading(Component text) implements Element {
		@Override
		public int height(int width) {
			return 16;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			g.text(ctx.font(), this.text, x, y + 4, VanillaGui.TEXT_DARK, false);
			g.fill(x, y + 14, x + width, y + 15, 0xFF8B8B8B);
		}
	}

	static final class Paragraph implements Element {
		private final Component text;
		private final int color;
		private int cachedWidth = -1;
		private List<FormattedCharSequence> lines = List.of();

		Paragraph(Component text, int color) {
			this.text = text;
			this.color = color;
		}

		private List<FormattedCharSequence> lines(Font font, int width) {
			if (width != this.cachedWidth) {
				this.lines = font.split(this.text, width);
				this.cachedWidth = width;
			}
			return this.lines;
		}

		@Override
		public int height(int width) {
			return Math.max(1, this.lines(VanillaGui.font(), width).size()) * 10 + 2;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			int ly = y + 1;
			for (FormattedCharSequence line : this.lines(ctx.font(), width)) {
				g.text(ctx.font(), line, x, ly, this.color, false);
				ly += 10;
			}
		}
	}

	record Stat(Component key, Component value) implements Element {
		/** Label and value side by side when they fit, otherwise the value goes on its own line. */
		private boolean fits(int width) {
			Font font = VanillaGui.font();
			return font.width(this.key) + font.width(this.value) + 8 <= width;
		}

		@Override
		public int height(int width) {
			return this.fits(width) ? 11 : 21;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			g.text(ctx.font(), this.key, x, y + 1, VanillaGui.TEXT_DARK, false);
			int vw = ctx.font().width(this.value);
			int vy = this.fits(width) ? y + 1 : y + 11;
			g.text(ctx.font(), this.value, x + width - vw, vy, 0xFF202020, false);
		}
	}

	/** A wrapping row of item slots; click opens the item, right click its uses. */
	static final class ItemFlow implements Element {
		private final List<ItemStack> items;

		ItemFlow(List<ItemStack> items) {
			this.items = items;
		}

		private static int perRow(int width) {
			return Math.max(1, width / 18);
		}

		@Override
		public int height(int width) {
			int rows = (this.items.size() + perRow(width) - 1) / perRow(width);
			return Math.max(1, rows) * 18 + 2;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			int per = perRow(width);
			for (int i = 0; i < this.items.size(); i++) {
				ctx.slot(g, x + (i % per) * 18, y + (i / per) * 18, List.of(this.items.get(i)), mouseX, mouseY, null);
			}
		}

		@Override
		public boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
			int per = perRow(width);
			int col = (int)(mouseX - x) / 18;
			int row = (int)(mouseY - y) / 18;
			int index = row * per + col;
			if (col < 0 || col >= per || index < 0 || index >= this.items.size()) return false;
			ctx.navigate(this.items.get(index), button == InputConstants.MOUSE_BUTTON_RIGHT ? ItemPageScreen.Tab.USES : ItemPageScreen.Tab.OBTAIN);
			return true;
		}
	}

	/** An icon slot followed by a title and some detail lines: a loot source, trade, mob... */
	static final class Source implements Element {
		private final List<ItemStack> icon;
		private final Component title;
		private final List<Component> details;
		private final @Nullable ItemStack target;

		Source(List<ItemStack> icon, Component title, List<Component> details, @Nullable ItemStack target) {
			this.icon = icon;
			this.title = title;
			this.details = details;
			this.target = target;
		}

		private int cachedWidth = -1;
		private List<FormattedCharSequence> titleLines = List.of();
		private List<FormattedCharSequence> detailLines = List.of();

		/** Wrapping is cached per width: pages call height() every frame. */
		private void wrap(int width) {
			if (width == this.cachedWidth) return;
			this.cachedWidth = width;
			Font font = VanillaGui.font();
			this.titleLines = font.split(this.title, width - 22);
			List<FormattedCharSequence> lines = new java.util.ArrayList<>();
			for (Component d : this.details) lines.addAll(font.split(d, width - 22));
			this.detailLines = lines;
		}

		@Override
		public int height(int width) {
			this.wrap(width);
			return Math.max(20, (this.titleLines.size() + this.detailLines.size()) * 10 + 2);
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			this.wrap(width);
			if (!this.icon.isEmpty()) ctx.slot(g, x, y, this.icon, mouseX, mouseY, null);
			int tx = x + 22;
			int ly = y + 1;
			for (FormattedCharSequence line : this.titleLines) {
				g.text(ctx.font(), line, tx, ly, 0xFF202020, false);
				ly += 10;
			}
			for (FormattedCharSequence line : this.detailLines) {
				g.text(ctx.font(), line, tx, ly, 0xFF555555, false);
				ly += 10;
			}
		}

		@Override
		public boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
			ItemStack go = this.target != null ? this.target : this.icon.isEmpty() ? null : ctx.pick(this.icon);
			if (go == null || go.isEmpty() || !VanillaGui.inside(mouseX, mouseY, x, y, 18, 18)) return false;
			ctx.navigate(go, button == InputConstants.MOUSE_BUTTON_RIGHT ? ItemPageScreen.Tab.USES : ItemPageScreen.Tab.OBTAIN);
			return true;
		}
	}

	/** A row of small vanilla buttons. */
	static final class Buttons implements Element {
		private final List<ButtonSpec> buttons;

		Buttons(List<ButtonSpec> buttons) {
			this.buttons = buttons;
		}

		private int buttonWidth(Font font, ButtonSpec spec) {
			return font.width(spec.label()) + 12;
		}

		@Override
		public int height(int width) {
			return 22;
		}

		@Override
		public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
			int bx = x;
			for (ButtonSpec spec : this.buttons) {
				int w = this.buttonWidth(ctx.font(), spec);
				boolean enabled = spec.enabled().getAsBoolean();
				boolean hover = VanillaGui.inside(mouseX, mouseY, bx, y + 1, w, 18);
				VanillaGui.textButton(g, ctx.font(), bx, y + 1, w, 18, spec.label(), hover, enabled);
				if (hover && spec.tooltip() != null) ctx.tooltip(List.of(spec.tooltip()));
				bx += w + 4;
			}
		}

		@Override
		public boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
			int bx = x;
			for (ButtonSpec spec : this.buttons) {
				int w = this.buttonWidth(ctx.font(), spec);
				if (VanillaGui.inside(mouseX, mouseY, bx, y + 1, w, 18) && spec.enabled().getAsBoolean()) {
					net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(ctx.minecraft().getSoundManager());
					spec.action().run();
					return true;
				}
				bx += w + 4;
			}
			return false;
		}
	}
}
