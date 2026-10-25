package com.wtfi.client.ui.page;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.hud.Checklist;
import com.wtfi.client.knowledge.RecipeView;
import com.wtfi.client.memory.StorageHighlight;
import com.wtfi.client.memory.StorageMemory;
import com.wtfi.client.ui.VanillaGui;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Draws one recipe the way its station looks (3x3 grid, furnace, stonecutter, smithing table,
 * brewing stand, or a generic row for modded types), plus what the player is missing and where
 * their storage has it.
 */
public final class RecipeElement implements Element {
	private static final int CAPTION = 12;
	private final RecipeView recipe;
	private final boolean showMissing;
	private long missingAt;
	private List<Missing> missing = List.of();

	public RecipeElement(RecipeView recipe, boolean showMissing) {
		this.recipe = recipe;
		this.showMissing = showMissing;
	}

	private int bodyHeight() {
		return switch (this.recipe.layout()) {
			case "shaped" -> Math.max(26, Math.max(1, this.recipe.info().height()) * 18);
			case "shapeless", "generic" -> Math.max(26, ((Math.max(1, this.recipe.inputs().size()) + 2) / 3) * 18);
			default -> 26;
		};
	}

	@Override
	public int height(int width) {
		int h = CAPTION + this.bodyHeight() + 4;
		if (this.hasExtraLine()) h += 10;
		h += this.missing.size() * 10;
		return h;
	}

	private boolean hasExtraLine() {
		return this.recipe.layout().equals("furnace");
	}

	@Override
	public void extract(GuiGraphicsExtractor g, PageContext ctx, int x, int y, int width, int mouseX, int mouseY) {
		// caption: station (+ "shapeless") + pin button
		Component caption = this.recipe.layout().equals("shapeless")
			? Component.empty().append(this.stationName(ctx)).append(" · ").append(Component.translatable("wtfi.recipe.shapeless"))
			: this.stationName(ctx);
		g.text(ctx.font(), caption, x, y + 2, 0xFF555555, false);
		boolean pinHover = VanillaGui.inside(mouseX, mouseY, x + width - 12, y, 12, 11);
		g.text(ctx.font(), Component.literal("+"), x + width - 9, y + 2, pinHover ? VanillaGui.TEXT_GOLD : 0xFF555555, false);
		if (pinHover) ctx.tooltip(List.of(Component.translatable("wtfi.page.pin_recipe")));

		int by = y + CAPTION;
		List<List<ItemStack>> inputs = this.recipe.inputs();
		int outX;
		switch (this.recipe.layout()) {
			case "shaped" -> {
				int w = Math.max(1, this.recipe.info().width());
				for (int i = 0; i < inputs.size(); i++) {
					this.slot(g, ctx, x + (i % w) * 18, by + (i / w) * 18, inputs.get(i), mouseX, mouseY);
				}
				outX = x + Math.max(3, w) * 18 + 4;
			}
			case "shapeless", "generic" -> {
				for (int i = 0; i < inputs.size(); i++) {
					this.slot(g, ctx, x + (i % 3) * 18, by + (i / 3) * 18, inputs.get(i), mouseX, mouseY);
				}
				outX = x + 3 * 18 + 4;
			}
			case "smithing", "brewing" -> {
				for (int i = 0; i < inputs.size(); i++) this.slot(g, ctx, x + i * 18, by + 4, inputs.get(i), mouseX, mouseY);
				outX = x + Math.max(3, inputs.size()) * 18 + 4;
			}
			default -> {
				// furnace, stonecutter: one input
				if (!inputs.isEmpty()) this.slot(g, ctx, x + 18, by + 4, inputs.getFirst(), mouseX, mouseY);
				outX = x + 3 * 18 + 4;
			}
		}
		int bodyMid = by + this.bodyHeight() / 2;
		VanillaGui.arrow(g, outX, bodyMid - 7);
		int bigX = outX + 26;
		int bigY = bodyMid - 13;
		VanillaGui.bigSlot(g, bigX, bigY);
		ItemStack out = ctx.pick(this.recipe.outputs());
		g.item(out, bigX + 5, bigY + 5);
		g.itemDecorations(ctx.font(), out, bigX + 5, bigY + 5);
		if (VanillaGui.inside(mouseX, mouseY, bigX, bigY, 26, 26)) {
			VanillaGui.slotHighlightFront(g, bigX + 5, bigY + 5);
			ctx.itemTooltip(out, null);
		}
		// station icon to the right of the result
		if (!this.recipe.station().isEmpty() && bigX + 30 + 18 <= x + width) {
			ctx.slot(g, bigX + 32, bigMidSlot(bodyMid), this.recipe.station(), mouseX, mouseY, null);
		}

		int ly = by + this.bodyHeight() + 2;
		if (this.hasExtraLine()) {
			float seconds = this.recipe.info().time() / 20f;
			Component line = Component.translatable("wtfi.recipe.cook", String.format(Locale.ROOT, "%.1f", seconds), String.format(Locale.ROOT, "%.1f", this.recipe.info().xp()));
			g.text(ctx.font(), line, x, ly, 0xFF555555, false);
			ly += 10;
		}
		if (this.showMissing) {
			this.updateMissing(ctx);
			for (Missing m : this.missing) {
				boolean hover = m.found != null && VanillaGui.inside(mouseX, mouseY, x, ly, width, 10);
				g.text(ctx.font(), m.text, x, ly, hover ? VanillaGui.TEXT_BLUE : m.found != null ? 0xFF2E5E2E : 0xFF8B2E2E, false);
				if (hover) ctx.tooltip(List.of(Component.translatable("wtfi.storage.click_highlight")));
				ly += 10;
			}
		}
	}

	private static int bigMidSlot(int bodyMid) {
		return bodyMid - 9;
	}

	private void slot(GuiGraphicsExtractor g, PageContext ctx, int x, int y, List<ItemStack> options, int mouseX, int mouseY) {
		ctx.slot(g, x, y, options, mouseX, mouseY, null);
	}

	private Component stationName(PageContext ctx) {
		if (!this.recipe.station().isEmpty()) {
			return this.recipe.station().getFirst().getHoverName();
		}
		String path = this.recipe.info().type().getPath().replace('_', ' ');
		return Component.literal(Character.toUpperCase(path.charAt(0)) + path.substring(1));
	}

	/** What the player lacks for one craft, and the nearest remembered container holding it. */
	private void updateMissing(PageContext ctx) {
		long now = Util.getMillis();
		if (now - this.missingAt < 500) return;
		this.missingAt = now;
		var player = ctx.minecraft().player;
		if (player == null) return;
		Map<Item, Integer> left = new IdentityHashMap<>(WtfiSession.inventoryCounts(player.getInventory()));
		Map<Item, Integer> lacking = new IdentityHashMap<>();
		Map<Item, ItemStack> example = new IdentityHashMap<>();
		for (List<ItemStack> slot : this.recipe.inputs()) {
			if (slot.isEmpty()) continue;
			boolean got = false;
			for (ItemStack option : slot) {
				Integer have = left.get(option.getItem());
				int need = Math.max(1, option.getCount());
				if (have != null && have >= need) {
					left.put(option.getItem(), have - need);
					got = true;
					break;
				}
			}
			if (!got) {
				ItemStack first = slot.getFirst();
				lacking.merge(first.getItem(), Math.max(1, first.getCount()), Integer::sum);
				example.putIfAbsent(first.getItem(), first);
			}
		}
		List<Missing> out = new ArrayList<>();
		StorageMemory storage = ctx.session().storage();
		for (var entry : lacking.entrySet()) {
			ItemStack stack = example.get(entry.getKey());
			StorageMemory.Found found = null;
			if (storage != null) {
				var hits = storage.find(stack, player.level().dimension().identifier(), player.blockPosition());
				if (!hits.isEmpty()) found = hits.getFirst();
			}
			Component text;
			if (found != null) {
				var pos = found.stored().pos();
				text = Component.translatable("wtfi.recipe.missing_stored", entry.getValue(), stack.getHoverName(), found.count(),
					Component.translatable("wtfi.storage.at", pos.getX(), pos.getY(), pos.getZ()));
			} else {
				text = Component.translatable("wtfi.recipe.missing", entry.getValue(), stack.getHoverName());
			}
			out.add(new Missing(text, found));
		}
		this.missing = out;
	}

	@Override
	public boolean click(PageContext ctx, int x, int y, int width, double mouseX, double mouseY, int button) {
		if (VanillaGui.inside(mouseX, mouseY, x + width - 12, y, 12, 11)) {
			Checklist.get().pin(this.recipe);
			ctx.minecraft().player.sendOverlayMessage(Component.translatable("wtfi.checklist.pinned", this.recipe.firstOutput().getHoverName()).withStyle(ChatFormatting.GREEN));
			return true;
		}
		int by = y + CAPTION;
		ItemPageScreen.Tab tab = button == InputConstants.MOUSE_BUTTON_RIGHT ? ItemPageScreen.Tab.USES : ItemPageScreen.Tab.OBTAIN;
		List<List<ItemStack>> inputs = this.recipe.inputs();
		switch (this.recipe.layout()) {
			case "shaped" -> {
				int w = Math.max(1, this.recipe.info().width());
				for (int i = 0; i < inputs.size(); i++) {
					if (this.hit(ctx, inputs.get(i), x + (i % w) * 18, by + (i / w) * 18, mouseX, mouseY, tab)) return true;
				}
			}
			case "shapeless", "generic" -> {
				for (int i = 0; i < inputs.size(); i++) {
					if (this.hit(ctx, inputs.get(i), x + (i % 3) * 18, by + (i / 3) * 18, mouseX, mouseY, tab)) return true;
				}
			}
			case "smithing", "brewing" -> {
				for (int i = 0; i < inputs.size(); i++) {
					if (this.hit(ctx, inputs.get(i), x + i * 18, by + 4, mouseX, mouseY, tab)) return true;
				}
			}
			default -> {
				if (!inputs.isEmpty() && this.hit(ctx, inputs.getFirst(), x + 18, by + 4, mouseX, mouseY, tab)) return true;
			}
		}
		// result, station
		int outX = switch (this.recipe.layout()) {
			case "shaped" -> x + Math.max(3, this.recipe.info().width()) * 18 + 4;
			case "smithing", "brewing" -> x + Math.max(3, inputs.size()) * 18 + 4;
			default -> x + 3 * 18 + 4;
		};
		int bodyMid = by + this.bodyHeight() / 2;
		if (VanillaGui.inside(mouseX, mouseY, outX + 26, bodyMid - 13, 26, 26)) {
			ctx.navigate(ctx.pick(this.recipe.outputs()), tab);
			return true;
		}
		if (this.hit(ctx, this.recipe.station(), outX + 26 + 32, bigMidSlot(bodyMid), mouseX, mouseY, tab)) return true;
		// missing lines: highlight storage
		int ly = by + this.bodyHeight() + 2 + (this.hasExtraLine() ? 10 : 0);
		for (Missing m : this.missing) {
			if (m.found != null && VanillaGui.inside(mouseX, mouseY, x, ly, width, 10)) {
				StorageHighlight.show(m.found.stored());
				return true;
			}
			ly += 10;
		}
		return false;
	}

	private boolean hit(PageContext ctx, List<ItemStack> options, int sx, int sy, double mouseX, double mouseY, ItemPageScreen.Tab tab) {
		if (options.isEmpty() || !VanillaGui.inside(mouseX, mouseY, sx, sy, 18, 18)) return false;
		ctx.navigate(ctx.pick(options), tab);
		return true;
	}

	private record Missing(Component text, StorageMemory.@Nullable Found found) {
	}
}
