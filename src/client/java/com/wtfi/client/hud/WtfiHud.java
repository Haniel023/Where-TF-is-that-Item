package com.wtfi.client.hud;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.config.WtfiConfig;
import com.wtfi.client.memory.StorageHighlight;
import com.wtfi.client.ui.VanillaGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The in-game corner box: storage highlight distance, hunt mode hints and pinned checklists.
 * Each section has a ✕: click it with chat open, or press the "close" key to drop the top one.
 * Draws nothing (and computes nothing) when no feature is active.
 */
public final class WtfiHud {
	private static final int CELL = 42;
	private static final int PER_ROW = 4;
	private static final int CLOSE = 10;
	/** Where the ✕ buttons were drawn last frame, for clicks from the chat screen. */
	private static final List<CloseTarget> TARGETS = new ArrayList<>();

	private record CloseTarget(int x, int y, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= this.x - 2 && mx < this.x + CLOSE && my >= this.y - 2 && my < this.y + CLOSE;
		}
	}

	private WtfiHud() {
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		TARGETS.clear();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || WtfiSession.get() == null) return;
		WtfiConfig config = WtfiConfig.get();
		Component highlight = StorageHighlight.hudLine(minecraft);
		boolean hunt = config.huntHud && !HuntTarget.target().isEmpty();
		List<Checklist.Goal> goals = config.checklistHud ? Checklist.get().goals() : List.of();
		if (highlight == null && !hunt && goals.isEmpty()) return;

		Font font = minecraft.font;
		List<Row> rows = new ArrayList<>();
		if (highlight != null) rows.add(Row.text(highlight, StorageHighlight::clear));
		if (hunt) {
			rows.add(Row.header(HuntTarget.target(), Component.translatable("wtfi.hunt.title", HuntTarget.target().getHoverName()), HuntTarget::clear));
			for (Component line : HuntTarget.lines(minecraft)) rows.add(Row.text(line, null));
		}
		if (!goals.isEmpty()) {
			Map<Item, Integer> inventory = WtfiSession.inventoryCounts(minecraft.player.getInventory());
			for (Checklist.Goal goal : List.copyOf(goals)) {
				Item output = goal.output().getItem();
				rows.add(Row.header(goal.output(), Component.translatable("wtfi.checklist.title", goal.output().getHoverName()), () -> Checklist.get().unpin(output)));
				List<Checklist.Need> needs = goal.needs();
				for (int i = 0; i < needs.size(); i += PER_ROW) {
					rows.add(Row.needs(needs.subList(i, Math.min(needs.size(), i + PER_ROW)), inventory));
				}
			}
		}

		int width = 0;
		int height = 0;
		for (Row row : rows) {
			width = Math.max(width, row.width(font));
			height += row.height();
		}
		// The tooltip frame extends a few pixels past the content; keep it fully on screen.
		int x = config.hudCorner == 0 ? 12 : g.guiWidth() - width - 12;
		int y = 12;

		// With chat open the mouse is free, so the ✕ buttons become clickable.
		boolean interactive = minecraft.gui.screen() instanceof ChatScreen;
		double mx = interactive ? minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()) : -1;
		double my = interactive ? minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()) : -1;

		VanillaGui.darkBox(g, x, y, width, height);
		int ry = y;
		for (Row row : rows) {
			row.extract(g, font, x, ry);
			if (row.close != null) {
				int cx = x + width - CLOSE + 2;
				int cy = ry + (row.height() - 8) / 2;
				CloseTarget target = new CloseTarget(cx, cy, row.close);
				TARGETS.add(target);
				boolean hover = interactive && target.hit(mx, my);
				g.text(font, "✕", cx, cy, hover ? 0xFFFF5555 : 0xFF8B8B8B);
				if (hover) g.setTooltipForNextFrame(font, Component.translatable("wtfi.hud.close"), (int)mx, (int)my);
			}
			ry += row.height();
		}
	}

	/** Positions (GUI coordinates, top-left) of the ✕ buttons drawn last frame. */
	public static List<int[]> closeButtons() {
		List<int[]> out = new ArrayList<>();
		for (CloseTarget target : TARGETS) out.add(new int[] {target.x, target.y});
		return out;
	}

	/** A click from the chat screen. Returns true if it closed a section. */
	public static boolean click(double mx, double my) {
		for (CloseTarget target : List.copyOf(TARGETS)) {
			if (target.hit(mx, my)) {
				target.action.run();
				return true;
			}
		}
		return false;
	}

	/** The "close" key: removes the top section (highlight, then hunt, then the newest checklist). */
	public static boolean dismissTop() {
		Minecraft minecraft = Minecraft.getInstance();
		if (StorageHighlight.hudLine(minecraft) != null) {
			StorageHighlight.clear();
			return true;
		}
		if (!HuntTarget.target().isEmpty()) {
			HuntTarget.clear();
			return true;
		}
		List<Checklist.Goal> goals = Checklist.get().goals();
		if (!goals.isEmpty()) {
			Checklist.get().unpin(goals.getFirst().output().getItem());
			return true;
		}
		return false;
	}

	private record Row(ItemStack icon, Component text, List<Checklist.Need> needs, Map<Item, Integer> inventory, int kind, @Nullable Runnable close) {
		static Row text(Component text, @Nullable Runnable close) {
			return new Row(ItemStack.EMPTY, text, List.of(), Map.of(), 0, close);
		}

		static Row header(ItemStack icon, Component text, Runnable close) {
			return new Row(icon, text, List.of(), Map.of(), 1, close);
		}

		static Row needs(List<Checklist.Need> needs, Map<Item, Integer> inventory) {
			return new Row(ItemStack.EMPTY, Component.empty(), needs, inventory, 2, null);
		}

		int height() {
			return this.kind == 0 ? 10 : 18;
		}

		int width(Font font) {
			int base = switch (this.kind) {
				case 0 -> font.width(this.text);
				case 1 -> 18 + font.width(this.text);
				default -> this.needs.size() * CELL;
			};
			return this.close != null ? base + CLOSE + 4 : base;
		}

		void extract(GuiGraphicsExtractor g, Font font, int x, int y) {
			switch (this.kind) {
				case 0 -> g.text(font, this.text, x, y + 1, 0xFFFFFFFF);
				case 1 -> {
					g.item(this.icon, x, y + 1);
					g.text(font, this.text, x + 18, y + 5, 0xFFFFD84A);
				}
				default -> {
					int cx = x;
					for (Checklist.Need need : this.needs) {
						int have = Checklist.have(need, this.inventory);
						boolean done = have >= need.count();
						g.item(need.icon(), cx, y + 1);
						String count = Math.min(have, 999) + "/" + need.count();
						g.text(font, count, cx + 17, y + 5, done ? 0xFF55FF55 : 0xFFFFFFFF);
						cx += CELL;
					}
				}
			}
		}
	}
}
