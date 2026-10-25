package com.wtfi.client.ui.page;

import com.wtfi.client.WtfiKeys;
import com.wtfi.client.WtfiSession;
import com.wtfi.client.cheat.CheatGiver;
import com.wtfi.client.config.WtfiConfig;
import com.wtfi.client.hud.HuntTarget;
import com.wtfi.client.index.ModNames;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.ui.VanillaGui;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Everything about one item, in vanilla-style tabs: overview, how to get it, where to find it,
 * what it's used for, and (for enchantable things) the enchanting planner.
 */
public final class ItemPageScreen extends Screen implements PageContext {
	private static final int HEADER = 38;
	/** Roughly the size of a large vanilla menu; content scrolls inside it. */
	private static final int MAX_WIDTH = 300;
	private static final int MAX_HEIGHT = 222;
	private static final int TAB_W = 26;
	private static final int TAB_H = 32;

	public enum Tab {
		OVERVIEW(Items.BOOK),
		OBTAIN(Items.CRAFTING_TABLE),
		WHERE(Items.COMPASS),
		USES(Items.STICK),
		ENCHANT(Items.ENCHANTING_TABLE);

		private final net.minecraft.world.item.Item icon;

		Tab(net.minecraft.world.item.Item icon) {
			this.icon = icon;
		}

		public ItemStack icon() {
			return new ItemStack(this.icon);
		}

		public Component title() {
			return Component.translatable("wtfi.tab." + this.name().toLowerCase());
		}
	}

	private record History(ItemStack stack, Tab tab, double scroll) {
	}

	private final @Nullable Screen parent;
	private final Deque<History> history = new ArrayDeque<>();
	private ItemStack stack;
	private Tab tab;
	private List<Element> elements = List.of();
	private @Nullable Knowledge builtWith;
	private double scroll;
	private boolean draggingScrollbar;
	private ItemStack hoveredStack = ItemStack.EMPTY;
	private int px;
	private int py;
	private int pw;
	private int ph;

	private ItemPageScreen(@Nullable Screen parent, ItemStack stack, Tab tab) {
		super(stack.getHoverName());
		this.parent = parent;
		this.stack = stack.copyWithCount(1);
		this.tab = tab;
	}

	public static void open(Minecraft minecraft, ItemStack stack, Tab tab) {
		if (stack.isEmpty() || WtfiSession.get() == null) return;
		Screen current = minecraft.gui.screen();
		if (current instanceof ItemPageScreen page) {
			page.navigate(stack, tab);
		} else {
			minecraft.gui.setScreen(new ItemPageScreen(current, stack, tab));
		}
	}

	// ---- layout ----

	@Override
	protected void init() {
		this.pw = Math.min(this.width - 16, MAX_WIDTH);
		this.ph = Math.min(this.height - TAB_H - 8, MAX_HEIGHT);
		this.px = (this.width - this.pw) / 2;
		// Centre panel + tabs (the tabs overlap the panel's top edge by 4px).
		int total = this.ph + TAB_H - 4;
		this.py = (this.height - total) / 2 + TAB_H - 4;
		this.rebuild();
	}

	private List<Tab> tabs() {
		List<Tab> tabs = new ArrayList<>(List.of(Tab.OVERVIEW, Tab.OBTAIN, Tab.WHERE, Tab.USES));
		if (EnchantTab.relevant(this.stack)) tabs.add(Tab.ENCHANT);
		return tabs;
	}

	private int contentX() {
		return this.px + 8;
	}

	private int contentY() {
		return this.py + HEADER + 4;
	}

	private int contentWidth() {
		return this.pw - 16 - VanillaGui.SCROLLER_WIDTH - 2;
	}

	private int contentHeight() {
		return this.ph - HEADER - 12;
	}

	private int totalHeight() {
		int w = this.contentWidth();
		int h = 0;
		for (Element e : this.elements) h += e.height(w);
		return h;
	}

	private double maxScroll() {
		return Math.max(0, this.totalHeight() - this.contentHeight());
	}

	@Override
	public void rebuild() {
		if (!this.tabs().contains(this.tab)) this.tab = Tab.OVERVIEW;
		this.builtWith = this.knowledge();
		this.elements = switch (this.tab) {
			case OVERVIEW -> OverviewTab.build(this);
			case OBTAIN -> ObtainTab.build(this);
			case WHERE -> WhereTab.build(this);
			case USES -> UsesTab.build(this);
			case ENCHANT -> EnchantTab.build(this);
		};
		this.scroll = Math.min(this.scroll, this.maxScroll());
	}

	@Override
	public void tick() {
		if (this.knowledge() != this.builtWith) this.rebuild();
	}

	// ---- navigation ----

	@Override
	public void navigate(ItemStack target, Tab tab) {
		if (target.isEmpty()) return;
		this.history.push(new History(this.stack, this.tab, this.scroll));
		if (this.history.size() > 64) this.history.removeLast();
		this.stack = target.copyWithCount(1);
		this.tab = tab;
		this.scroll = 0;
		this.session().seen().markSeen(this.stack.getItem());
		this.rebuild();
	}

	private void back() {
		History previous = this.history.poll();
		if (previous == null) {
			this.onClose();
			return;
		}
		this.stack = previous.stack;
		this.tab = previous.tab;
		this.rebuild();
		this.scroll = Math.min(previous.scroll, this.maxScroll());
	}

	@Override
	public void onClose() {
		Screen target = this.parent;
		// Only go back to a container if the server still has it open for us.
		if (target instanceof AbstractContainerScreen<?> container && this.minecraft.player != null
			&& container.getMenu() != this.minecraft.player.containerMenu) {
			target = null;
		}
		this.minecraft.gui.setScreen(target);
	}

	// ---- drawing ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		this.hoveredStack = ItemStack.EMPTY;
		this.pendingTooltip = null;
		this.pendingImageStack = null;
		List<Tab> tabs = this.tabs();
		for (int i = 0; i < tabs.size(); i++) {
			if (tabs.get(i) != this.tab) this.drawTab(g, tabs.get(i), i, false, mouseX, mouseY);
		}
		VanillaGui.panel(g, this.px, this.py, this.pw, this.ph);
		for (int i = 0; i < tabs.size(); i++) {
			if (tabs.get(i) == this.tab) this.drawTab(g, tabs.get(i), i, true, mouseX, mouseY);
		}
		this.drawHeader(g, mouseX, mouseY);

		int cx = this.contentX();
		int cy = this.contentY();
		int cw = this.contentWidth();
		int ch = this.contentHeight();
		g.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
		int y = cy - (int)this.scroll;
		boolean mouseInContent = VanillaGui.inside(mouseX, mouseY, cx - 2, cy, cw + 4, ch);
		int mx = mouseInContent ? mouseX : -10000;
		int my = mouseInContent ? mouseY : -10000;
		for (Element element : this.elements) {
			int h = element.height(cw);
			if (y + h >= cy && y <= cy + ch) element.extract(g, this, cx, y, cw, mx, my);
			y += h;
		}
		g.disableScissor();

		double max = this.maxScroll();
		VanillaGui.scrollbar(g, cx + cw + 2, cy, ch, max <= 0 ? 0 : (float)(this.scroll / max), max > 0);

		if (this.pendingTooltip != null) {
			var image = this.pendingImageStack != null ? this.pendingImageStack.getTooltipImage() : java.util.Optional.<net.minecraft.world.inventory.tooltip.TooltipComponent>empty();
			g.setTooltipForNextFrame(this.font, this.pendingTooltip, image, mouseX, mouseY);
		}
	}

	private void drawTab(GuiGraphicsExtractor g, Tab tab, int index, boolean selected, int mouseX, int mouseY) {
		int x = this.px + 4 + index * (TAB_W + 2);
		int y = this.py - TAB_H + (selected ? 4 : 4);
		int column = Math.min(7, index + 1);
		Identifier sprite = Identifier.withDefaultNamespace("container/creative_inventory/tab_top_" + (selected ? "selected_" : "unselected_") + column);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, TAB_W, TAB_H);
		g.item(tab.icon(), x + 5, y + 9);
		if (VanillaGui.inside(mouseX, mouseY, x, y, TAB_W, TAB_H - 4)) this.tooltip(List.of(tab.title()));
	}

	private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Font font = this.font;
		int x = this.px + 7;
		int y = this.py + 6;
		// The item in a result-style slot, at normal size like everywhere else in the game.
		VanillaGui.bigSlot(g, x, y);
		g.item(this.stack, x + 5, y + 5);
		if (VanillaGui.inside(mouseX, mouseY, x, y, 26, 26)) this.itemTooltip(this.stack, null);

		int tx = x + 31;
		int maxText = this.px + this.pw - 8 - this.headerButtonsWidth() - tx;
		g.text(font, ellipsize(font, title(this.stack), maxText), tx, y + 1, 0xFF202020, false);
		Identifier id = BuiltInRegistries.ITEM.getKey(this.stack.getItem());
		String modName = ModNames.of(id.getNamespace());
		g.text(font, Component.literal(modName).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC), tx, y + 11, 0xFF3F3FBF, false);
		int idX = tx + font.width(modName) + 5;
		g.text(font, ellipsize(font, id.toString(), maxText - (idX - tx)), idX, y + 11, 0xFF8B8B8B, false);

		int bx = tx;
		for (Component badge : this.badges()) {
			int w = font.width(badge);
			g.text(font, badge, bx, y + 21, 0xFF404040, false);
			bx += w + 8;
		}

		// header buttons, right aligned: back, give, hunt, close
		int hx = this.px + this.pw - 8;
		for (HeaderButton button : this.headerButtons()) {
			int w = font.width(button.label) + 10;
			hx -= w;
			boolean hover = VanillaGui.inside(mouseX, mouseY, hx, y, w, 14);
			VanillaGui.textButton(g, font, hx, y, w, 14, button.label, hover, button.enabled);
			if (hover) this.tooltip(List.of(button.tooltip));
			hx -= 3;
		}
	}

	private List<Component> badges() {
		List<Component> out = new ArrayList<>();
		Knowledge k = this.knowledge();
		if (k == null) {
			out.add(Component.translatable("wtfi.page.loading").withStyle(ChatFormatting.GRAY));
			return out;
		}
		switch (k.obtainability(this.stack.getItem())) {
			case CREATIVE_ONLY -> out.add(Component.translatable("wtfi.badge.creative_only").withStyle(ChatFormatting.DARK_PURPLE));
			case UNKNOWN -> out.add(Component.translatable("wtfi.badge.unknown_source").withStyle(ChatFormatting.DARK_GRAY));
			case SURVIVAL -> {
				out.add(Component.translatable("wtfi.badge.survival").withStyle(ChatFormatting.DARK_GREEN));
				Knowledge.Renewability r = k.renewability(this.stack.getItem());
				if (r.known()) {
					out.add(Component.translatable(r.renewable() ? "wtfi.badge.renewable" : "wtfi.badge.non_renewable")
						.withStyle(r.renewable() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
				}
			}
		}
		if (this.session().seen().isNew(this.stack.getItem())) out.add(Component.translatable("wtfi.badge.new").withStyle(ChatFormatting.GOLD));
		return out;
	}

	private record HeaderButton(Component label, Component tooltip, boolean enabled, Runnable action) {
	}

	private List<HeaderButton> headerButtons() {
		List<HeaderButton> out = new ArrayList<>();
		out.add(new HeaderButton(Component.literal("✕"), Component.translatable("wtfi.page.close"), true, this::onClose));
		if (WtfiConfig.get().cheatMode || CheatGiver.canCheat(this.minecraft)) {
			out.add(new HeaderButton(Component.translatable("wtfi.page.give"), Component.translatable("wtfi.page.give.tooltip"), CheatGiver.canCheat(this.minecraft),
				() -> CheatGiver.give(this.minecraft, this.stack, this.minecraft.hasShiftDown())));
		}
		boolean hunting = HuntTarget.isTarget(this.stack);
		out.add(new HeaderButton(Component.translatable(hunting ? "wtfi.page.unhunt" : "wtfi.page.hunt"), Component.translatable("wtfi.page.hunt.tooltip"), true,
			() -> HuntTarget.toggle(this.stack)));
		if (com.wtfi.client.hud.Checklist.get().isPinned(this.stack.getItem())) {
			out.add(new HeaderButton(Component.translatable("wtfi.page.unpin"), Component.translatable("wtfi.page.unpin.tooltip"), true,
				() -> com.wtfi.client.hud.Checklist.get().unpin(this.stack.getItem())));
		}
		if (!this.history.isEmpty()) {
			out.add(new HeaderButton(Component.literal("◀"), Component.translatable("wtfi.page.back"), true, this::back));
		}
		return out;
	}

	private int headerButtonsWidth() {
		int w = 0;
		for (HeaderButton b : this.headerButtons()) w += this.font.width(b.label) + 13;
		return w;
	}

	// ---- PageContext ----

	@Override
	public Minecraft minecraft() {
		return this.minecraft;
	}

	@Override
	public Font font() {
		return this.font;
	}

	@Override
	public WtfiSession session() {
		return WtfiSession.get();
	}

	@Override
	public @Nullable Knowledge knowledge() {
		WtfiSession session = WtfiSession.get();
		return session == null ? null : session.knowledge();
	}

	@Override
	public ItemStack pageStack() {
		return this.stack;
	}

	@Override
	public void tooltip(List<Component> lines) {
		// Set during drawing; the game shows it at the mouse after everything else.
		this.pendingTooltip = lines;
	}

	private List<Component> pendingTooltip;

	@Override
	public void itemTooltip(ItemStack stack, @Nullable Component extra) {
		List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, stack));
		if (extra != null) lines.add(extra);
		if (WtfiConfig.get().showModName) {
			lines.add(Component.literal(ModNames.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
		}
		this.pendingTooltip = lines;
		this.pendingImageStack = stack;
		this.hoveredStack = stack;
	}

	private @Nullable ItemStack pendingImageStack;

	@Override
	public ItemStack pick(List<ItemStack> options) {
		if (options.isEmpty()) return ItemStack.EMPTY;
		if (options.size() == 1) return options.getFirst();
		return options.get((int)((Util.getMillis() / 1000) % options.size()));
	}

	@Override
	public ItemStack slot(GuiGraphicsExtractor g, int x, int y, List<ItemStack> options, int mouseX, int mouseY, @Nullable Component extra) {
		VanillaGui.slot(g, x, y);
		ItemStack shown = this.pick(options);
		boolean hover = VanillaGui.inside(mouseX, mouseY, x, y, 18, 18);
		if (hover) VanillaGui.slotHighlightBack(g, x + 1, y + 1);
		if (!shown.isEmpty()) {
			g.item(shown, x + 1, y + 1);
			g.itemDecorations(this.font, shown, x + 1, y + 1);
		}
		if (hover) {
			VanillaGui.slotHighlightFront(g, x + 1, y + 1);
			if (!shown.isEmpty()) {
				Component more = extra;
				if (more == null && options.size() > 1) {
					more = Component.translatable("wtfi.page.alternatives", options.size()).withStyle(ChatFormatting.DARK_GRAY);
				}
				this.itemTooltip(shown, more);
				return shown;
			}
		}
		return ItemStack.EMPTY;
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		List<Tab> tabs = this.tabs();
		for (int i = 0; i < tabs.size(); i++) {
			int x = this.px + 4 + i * (TAB_W + 2);
			int y = this.py - TAB_H + 4;
			if (VanillaGui.inside(mx, my, x, y, TAB_W, TAB_H - 4)) {
				if (tabs.get(i) != this.tab) {
					this.history.push(new History(this.stack, this.tab, this.scroll));
					this.tab = tabs.get(i);
					this.scroll = 0;
					this.rebuild();
					net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
				}
				return true;
			}
		}
		int hx = this.px + this.pw - 8;
		int hy = this.py + 6;
		for (HeaderButton button : this.headerButtons()) {
			int w = this.font.width(button.label) + 10;
			hx -= w;
			if (VanillaGui.inside(mx, my, hx, hy, w, 14)) {
				if (button.enabled) {
					net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
					button.action.run();
				}
				return true;
			}
			hx -= 3;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_4) {
			this.back();
			return true;
		}

		int cx = this.contentX();
		int cy = this.contentY();
		int cw = this.contentWidth();
		int ch = this.contentHeight();
		if (VanillaGui.inside(mx, my, cx + cw + 2, cy, VanillaGui.SCROLLER_WIDTH, ch)) {
			this.draggingScrollbar = true;
			this.dragTo(my);
			return true;
		}
		if (VanillaGui.inside(mx, my, cx - 2, cy, cw + 4, ch)) {
			int y = cy - (int)this.scroll;
			for (Element element : List.copyOf(this.elements)) {
				int h = element.height(cw);
				if (my >= y && my < y + h) {
					return element.click(this, cx, y, cw, mx, my, event.button());
				}
				y += h;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.draggingScrollbar) {
			this.dragTo(event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	private void dragTo(double my) {
		double progress = (my - this.contentY() - 7) / (this.contentHeight() - 15);
		this.scroll = Math.max(0, Math.min(1, progress)) * this.maxScroll();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll = Math.max(0, Math.min(this.maxScroll(), this.scroll - scrollY * 20));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			this.back();
			return true;
		}
		if (!this.hoveredStack.isEmpty()) {
			if (WtfiKeys.SHOW_RECIPES.matches(event)) {
				this.navigate(this.hoveredStack, Tab.OBTAIN);
				return true;
			}
			if (WtfiKeys.SHOW_USES.matches(event)) {
				this.navigate(this.hoveredStack, Tab.USES);
				return true;
			}
		}
		if (this.minecraft.options.keyInventory.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Shorthand used by tab builders. */
	static MutableComponent t(String key, Object... args) {
		return Component.translatable(key, args);
	}

	static String ellipsize(Font font, String text, int width) {
		if (width <= 0) return "";
		if (font.width(text) <= width) return text;
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

	/** The page title: the item name, plus the enchantments for an enchanted book. */
	static String title(ItemStack stack) {
		String name = stack.getHoverName().getString();
		var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored == null || stored.isEmpty()) return name;
		StringBuilder out = new StringBuilder(name).append(": ");
		boolean first = true;
		for (var entry : stored.entrySet()) {
			if (!first) out.append(", ");
			first = false;
			out.append(net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
		return out.toString();
	}

	static boolean hasPotion(ItemStack stack) {
		var contents = stack.get(DataComponents.POTION_CONTENTS);
		return contents != null && contents.potion().isPresent();
	}

	static boolean hasStoredEnchantments(ItemStack stack) {
		var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		return stored != null && !stored.isEmpty();
	}
}
