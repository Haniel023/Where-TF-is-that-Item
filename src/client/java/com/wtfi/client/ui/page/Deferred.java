package com.wtfi.client.ui.page;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Shows "Calculating..." until a background job produces its elements, then shows those. */
final class Deferred implements Element {
	private final CompletableFuture<List<Element>> future;

	Deferred(CompletableFuture<List<Element>> future) {
		this.future = future;
	}

	private List<Element> children() {
		return this.future.isDone() && !this.future.isCompletedExceptionally() ? this.future.join() : List.of();
	}

	@Override
	public int height(int width) {
		if (!this.future.isDone()) return 12;
		int h = 0;
		for (Element e : this.children()) h += e.height(width);
		return h;
	}

	@Override
	public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
		if (!this.future.isDone()) {
			g.text(ctx.font(), Component.translatable("wtfi.page.calculating"), x, y + 2, 0xFF707070, false);
			return;
		}
		int cy = y;
		for (Element e : this.children()) {
			int h = e.height(width);
			e.extract(g, ctx, x, cy, width, mouseX, mouseY);
			cy += h;
		}
	}

	@Override
	public boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
		int cy = y;
		for (Element e : this.children()) {
			int h = e.height(width);
			if (mouseY >= cy && mouseY < cy + h) return e.click(ctx, x, cy, width, mouseX, mouseY, button);
			cy += h;
		}
		return false;
	}
}
