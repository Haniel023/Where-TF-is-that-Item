package com.wtfi.client.ui;

import com.wtfi.client.WtfiKeys;
import com.wtfi.client.WtfiSession;
import com.wtfi.client.cheat.CheatGiver;
import com.wtfi.client.config.WtfiConfig;
import com.wtfi.client.config.WtfiConfigScreen;
import com.wtfi.client.index.Category;
import com.wtfi.client.index.IndexEntry;
import com.wtfi.client.index.LibraryView;
import com.wtfi.client.index.ModNames;
import com.wtfi.client.index.SortMode;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.mixin.AbstractContainerScreenAccessor;
import com.wtfi.client.ui.page.ItemPageScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The item library drawn to the right of any container screen. It isn't a widget of the
 * screen: it draws itself after the container background and takes input through Fabric's
 * screen events, so it never fights the container over focus or slot clicks.
 */
public final class LibraryOverlay {
	private static final int PAD = 4;
	private static final int SLOT = 18;
	private static final int BUTTON = 18;
	private static String lastSearch = "";
	private static int lastScrollRow;

	private final Minecraft minecraft;
	private final AbstractContainerScreen<?> screen;
	private final AbstractContainerScreenAccessor access;
	private final String screenKey;
	private final LibraryView view = new LibraryView();
	private final EditBox search;
	private final List<Button> buttons = new ArrayList<>();

	private long layoutKey = Long.MIN_VALUE;
	private boolean fits;
	private int px;
	private int py;
	private int pw;
	private int ph;
	private int gridX;
	private int gridY;
	private int cols;
	private int rows;
	private int searchY;
	private int scrollRow;
	private boolean draggingScrollbar;
	private @Nullable Popup popup;
	private @Nullable IndexEntry hovered;
	private int hintCount = -1;

	private LibraryOverlay(Minecraft minecraft, AbstractContainerScreen<?> screen) {
		this.minecraft = minecraft;
		this.screen = screen;
		this.access = (AbstractContainerScreenAccessor)screen;
		this.screenKey = screenKey(screen);
		this.search = new EditBox(minecraft.font, 0, 0, 80, 16, Component.translatable("wtfi.search"));
		this.search.setMaxLength(256);
		this.search.setHint(Component.translatable("wtfi.search.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.search.setValue(lastSearch);
		this.search.setResponder(text -> {
			lastSearch = text;
			this.scrollRow = 0;
			this.refresh(false);
		});
		this.scrollRow = lastScrollRow;
	}

	private static @Nullable LibraryOverlay active;

	/** The overlay on the screen that's open right now, if any. */
	public static @Nullable LibraryOverlay active() {
		return active;
	}

	/** Opens one of the panel's menus: "filter", "sort" or "mod". */
	public void openMenu(String which) {
		this.layout();
		switch (which) {
			case "filter" -> this.openFilterPopup();
			case "sort" -> this.openSortPopup();
			case "mod" -> this.openModPopup();
			default -> this.popup = null;
		}
	}

	/** Pre-fills the library search (e.g. "show me everything tagged #logs"). */
	public static void presetSearch(String text) {
		lastSearch = text;
		lastScrollRow = 0;
	}

	public static String screenKey(AbstractContainerScreen<?> screen) {
		if (screen instanceof CreativeModeInventoryScreen) return "creative";
		if (screen instanceof InventoryScreen) return "inventory";
		try {
			Identifier id = BuiltInRegistries.MENU.getKey(screen.getMenu().getType());
			return id != null ? id.toString() : screen.getClass().getSimpleName();
		} catch (UnsupportedOperationException e) {
			return screen.getClass().getSimpleName();
		}
	}

	/** Hooks the overlay onto a freshly initialised container screen. */
	public static void attach(Minecraft minecraft, Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> container)) return;
		LibraryOverlay overlay = new LibraryOverlay(minecraft, container);
		// In creative, the survival inventory swaps itself for the creative screen during init;
		// only the overlay of the screen actually shown counts as active.
		if (minecraft.gui.screen() == screen) active = overlay;
		ScreenEvents.afterBackground(screen).register((s, g, mx, my, a) -> {
			active = overlay;
			overlay.extract(g, mx, my, a);
		});
		// Menus go on top of everything the container draws (they may overlap it).
		ScreenEvents.afterExtract(screen).register((s, g, mx, my, a) -> overlay.extractPopup(g, mx, my));
		ScreenEvents.afterTick(screen).register(s -> overlay.tick());
		ScreenEvents.remove(screen).register(s -> {
			lastScrollRow = overlay.scrollRow;
			if (active == overlay) active = null;
		});
		ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !overlay.mouseClicked(e));
		ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !overlay.mouseReleased(e));
		ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !overlay.mouseDragged(e));
		ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> !overlay.mouseScrolled(mx, my, v));
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> !overlay.keyPressed(e));
		ScreenKeyboardEvents.allowCharType(screen).register((s, e) -> !overlay.charTyped(e));
	}

	private boolean visible() {
		WtfiConfig config = WtfiConfig.get();
		return config.panelVisible && config.isScreenEnabled(this.screenKey) && WtfiSession.get() != null && this.fits;
	}

	// ---- layout ----

	private void layout() {
		int left = this.access.wtfi$getLeftPos();
		int imageWidth = this.access.wtfi$getImageWidth();
		long key = ((long)left << 40) ^ ((long)imageWidth << 28) ^ ((long)this.screen.width << 14) ^ this.screen.height ^ ((long)WtfiConfig.get().maxColumns << 50);
		if (key == this.layoutKey) return;
		this.layoutKey = key;

		int areaLeft = left + imageWidth + 6;
		int areaRight = this.screen.width - 4;
		int inner = areaRight - areaLeft - 2 * PAD - VanillaGui.SCROLLER_WIDTH - 2;
		this.cols = Math.min(WtfiConfig.get().maxColumns, inner / SLOT);
		this.fits = this.cols >= 3 && this.screen.height >= 120;
		if (!this.fits) return;

		this.pw = this.cols * SLOT + VanillaGui.SCROLLER_WIDTH + 2 + 2 * PAD;
		this.px = areaRight - this.pw;
		this.py = 4;
		this.ph = this.screen.height - 8;

		this.buttons.clear();
		this.addButtons();
		int perRow = Math.max(1, (this.pw - 2 * PAD + 1) / (BUTTON + 1));
		for (int i = 0; i < this.buttons.size(); i++) {
			Button b = this.buttons.get(i);
			b.x = this.px + PAD + (i % perRow) * (BUTTON + 1);
			b.y = this.py + PAD + (i / perRow) * (BUTTON + 1);
		}
		int barRows = (this.buttons.size() + perRow - 1) / perRow;
		this.gridX = this.px + PAD;
		this.gridY = this.py + PAD + barRows * (BUTTON + 1) + 2;
		this.searchY = this.py + this.ph - PAD - 16;
		this.rows = Math.max(1, (this.searchY - 3 - this.gridY) / SLOT);
		this.search.setX(this.px + PAD);
		this.search.setY(this.searchY);
		this.search.setWidth(this.pw - 2 * PAD);
		this.popup = null;
		this.clampScroll();
	}

	private void addButtons() {
		WtfiConfig config = WtfiConfig.get();
		this.buttons.add(new Button(
			() -> new ItemStack(Items.HOPPER),
			() -> Component.translatable("wtfi.button.filter").append(config.filters.isEmpty() ? Component.empty() : Component.literal(" (" + config.filters.size() + ")")),
			() -> !config.filters.isEmpty(),
			this::openFilterPopup
		));
		this.buttons.add(new Button(
			() -> SortMode.byName(config.sort).icon(),
			() -> Component.translatable("wtfi.button.sort", SortMode.byName(config.sort).displayName()),
			() -> !config.sort.equals("CREATIVE") || config.sortDescending,
			this::openSortPopup
		));
		this.buttons.add(new Button(
			() -> new ItemStack(Items.BOOKSHELF),
			() -> Component.translatable("wtfi.button.mod", config.modFilter.isEmpty() ? Component.translatable("wtfi.mod.all") : Component.literal(ModNames.of(config.modFilter))),
			() -> !config.modFilter.isEmpty(),
			this::openModPopup
		));
		this.buttons.add(new Button(
			() -> new ItemStack(Items.COMMAND_BLOCK),
			() -> Component.translatable(config.cheatMode ? "wtfi.button.cheat.on" : "wtfi.button.cheat.off")
				.append(CheatGiver.canCheat(this.minecraft) ? Component.empty() : Component.literal("\n").append(Component.translatable("wtfi.cheat.no_permission").withStyle(ChatFormatting.RED))),
			() -> config.cheatMode,
			() -> {
				config.cheatMode = !config.cheatMode;
				config.save();
			}
		));
		this.buttons.add(new Button(
			() -> new ItemStack(Items.COMPARATOR),
			() -> Component.translatable("wtfi.button.settings"),
			() -> false,
			() -> this.minecraft.gui.setScreen(new WtfiConfigScreen(this.screen))
		));
	}

	// ---- data ----

	private void refresh(boolean force) {
		WtfiSession session = WtfiSession.get();
		if (session == null) return;
		this.view.update(session, this.search.getValue(), force);
		this.clampScroll();
	}

	private void tick() {
		WtfiSession session = WtfiSession.get();
		if (session == null || !this.visible()) return;
		session.index().checkLanguage(this.minecraft);
		this.view.tick(session, this.search.getValue());
		// Cheap when nothing changed: the view compares a short state key.
		this.view.update(session, this.search.getValue(), false);
		this.clampScroll();
	}

	private int maxScroll() {
		int total = this.view.results().length;
		int totalRows = (total + this.cols - 1) / Math.max(1, this.cols);
		return Math.max(0, totalRows - this.rows);
	}

	private void clampScroll() {
		this.scrollRow = Math.max(0, Math.min(this.maxScroll(), this.scrollRow));
	}

	// ---- drawing ----

	private void extract(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		this.layout();
		this.hovered = null;
		if (!this.visible()) return;
		WtfiSession session = WtfiSession.get();
		if (this.view.results().length == 0 && this.view.query().isEmpty()) this.refresh(false);
		Font font = this.minecraft.font;

		VanillaGui.panel(g, this.px, this.py, this.pw, this.ph);
		for (Button button : this.buttons) {
			boolean hover = button.contains(mouseX, mouseY) && this.popup == null;
			VanillaGui.iconButton(g, button.x, button.y, BUTTON, button.icon.get(), hover, true);
			if (button.active.getAsBoolean()) g.outline(button.x, button.y, BUTTON, BUTTON, 0xFFFFD84A);
			if (hover) g.setTooltipForNextFrame(font, font.split(button.tooltip.get(), 200), mouseX, mouseY);
		}

		int[] results = this.view.results();
		var entries = session.index().entries();
		boolean highlightNew = WtfiConfig.get().highlightNewItems;
		int hoveredIndex = -1;
		if (this.popup == null && VanillaGui.inside(mouseX, mouseY, this.gridX, this.gridY, this.cols * SLOT, this.rows * SLOT)) {
			int c = (mouseX - this.gridX) / SLOT;
			int r = (mouseY - this.gridY) / SLOT;
			hoveredIndex = (this.scrollRow + r) * this.cols + c;
			if (hoveredIndex >= results.length) hoveredIndex = -1;
		}
		for (int r = 0; r < this.rows; r++) {
			for (int c = 0; c < this.cols; c++) {
				VanillaGui.slot(g, this.gridX + c * SLOT, this.gridY + r * SLOT);
			}
		}
		if (hoveredIndex >= 0) {
			int slot = hoveredIndex - this.scrollRow * this.cols;
			VanillaGui.slotHighlightBack(g, this.gridX + (slot % this.cols) * SLOT + 1, this.gridY + (slot / this.cols) * SLOT + 1);
		}
		int first = this.scrollRow * this.cols;
		int last = Math.min(results.length, first + this.rows * this.cols);
		for (int i = first; i < last; i++) {
			IndexEntry entry = entries[results[i]];
			int slot = i - first;
			int x = this.gridX + (slot % this.cols) * SLOT + 1;
			int y = this.gridY + (slot / this.cols) * SLOT + 1;
			g.item(entry.stack, x, y);
			if (highlightNew && session.seen().isNew(entry.stack.getItem())) g.fill(x + 13, y, x + 16, y + 3, 0xFFFFD84A);
		}
		if (hoveredIndex >= 0) {
			int slot = hoveredIndex - first;
			VanillaGui.slotHighlightFront(g, this.gridX + (slot % this.cols) * SLOT + 1, this.gridY + (slot / this.cols) * SLOT + 1);
			this.hovered = entries[results[hoveredIndex]];
			this.itemTooltip(g, this.hovered, mouseX, mouseY);
		}
		if (results.length == 0) {
			Component message = Component.translatable(this.view.waitingForTooltips() ? "wtfi.library.indexing" : "wtfi.library.empty");
			g.textWithWordWrap(font, message, this.gridX + 2, this.gridY + 4, this.cols * SLOT - 4, VanillaGui.TEXT_DARK, false);
		}

		int sx = this.gridX + this.cols * SLOT + 2;
		int max = this.maxScroll();
		VanillaGui.scrollbar(g, sx, this.gridY, this.rows * SLOT, max == 0 ? 0 : this.scrollRow / (float)max, max > 0);

		if (results.length != this.hintCount) {
			this.hintCount = results.length;
			this.search.setHint(Component.translatable("wtfi.search.hint_count", results.length).withStyle(ChatFormatting.DARK_GRAY));
		}
		this.search.extractRenderState(g, mouseX, mouseY, partial);
	}

	private void extractPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (this.popup == null || !this.visible()) return;
		g.nextStratum();
		this.popup.extract(g, this.minecraft.font, mouseX, mouseY);
	}

	private void itemTooltip(GuiGraphicsExtractor g, IndexEntry entry, int mouseX, int mouseY) {
		WtfiSession session = WtfiSession.get();
		List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, entry.stack));
		Knowledge knowledge = session.knowledge();
		if (knowledge != null) {
			switch (knowledge.obtainability(entry.stack.getItem())) {
				case CREATIVE_ONLY -> lines.add(Component.translatable("wtfi.badge.creative_only").withStyle(ChatFormatting.LIGHT_PURPLE));
				case UNKNOWN -> lines.add(Component.translatable("wtfi.badge.unknown_source").withStyle(ChatFormatting.GRAY));
				case SURVIVAL -> {
					Knowledge.Renewability r = knowledge.renewability(entry.stack.getItem());
					if (r.known() && !r.renewable()) lines.add(Component.translatable("wtfi.badge.non_renewable").withStyle(ChatFormatting.RED));
				}
			}
		}
		if (session.seen().isNew(entry.stack.getItem())) lines.add(Component.translatable("wtfi.badge.new").withStyle(ChatFormatting.YELLOW));
		if (WtfiConfig.get().showModName) lines.add(Component.literal(entry.modName).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
		MutableComponent hint = WtfiConfig.get().cheatMode
			? Component.translatable("wtfi.hint.cheat")
			: Component.translatable("wtfi.hint.click");
		lines.add(hint.withStyle(ChatFormatting.DARK_GRAY));
		g.setTooltipForNextFrame(this.minecraft.font, lines, entry.stack.getTooltipImage(), mouseX, mouseY);
		session.seen().markSeen(entry.stack.getItem());
	}

	// ---- popups ----

	private void openFilterPopup() {
		WtfiConfig config = WtfiConfig.get();
		List<Popup.Row> rows = new ArrayList<>();
		rows.add(new Popup.Row(new ItemStack(Items.BARRIER), Component.translatable("wtfi.filter.clear"), null, () -> {
			config.filters.clear();
			this.saveAndRefresh();
		}));
		for (Category category : Category.values()) {
			rows.add(new Popup.Row(category.icon(), category.displayName(), () -> config.filters.contains(category.name()), () -> {
				if (!config.filters.remove(category.name())) config.filters.add(category.name());
				this.saveAndRefresh();
			}));
		}
		WtfiSession session = WtfiSession.get();
		if (session != null && session.seen().anyNew()) {
			rows.add(new Popup.Row(new ItemStack(Items.MILK_BUCKET), Component.translatable("wtfi.filter.mark_seen"), null, () -> {
				session.seen().markAllSeen();
				this.saveAndRefresh();
			}));
		}
		this.openPopup(rows);
	}

	private void openSortPopup() {
		WtfiConfig config = WtfiConfig.get();
		List<Popup.Row> rows = new ArrayList<>();
		for (SortMode mode : SortMode.values()) {
			rows.add(new Popup.Row(mode.icon(), mode.displayName(), () -> config.sort.equals(mode.name()), () -> {
				if (config.sort.equals(mode.name())) {
					config.sortDescending = !config.sortDescending;
				} else {
					config.sort = mode.name();
					config.sortDescending = false;
				}
				this.saveAndRefresh();
			}));
		}
		rows.add(new Popup.Row(new ItemStack(Items.REPEATER), Component.translatable("wtfi.sort.reverse"), () -> config.sortDescending, () -> {
			config.sortDescending = !config.sortDescending;
			this.saveAndRefresh();
		}));
		this.openPopup(rows);
	}

	private void openModPopup() {
		WtfiConfig config = WtfiConfig.get();
		WtfiSession session = WtfiSession.get();
		if (session == null) return;
		List<Popup.Row> rows = new ArrayList<>();
		rows.add(new Popup.Row(new ItemStack(Items.BOOKSHELF), Component.translatable("wtfi.mod.all"), () -> config.modFilter.isEmpty(), () -> {
			config.modFilter = "";
			this.saveAndRefresh();
		}));
		for (String namespace : session.index().namespaces()) {
			ItemStack icon = session.index().entries().length == 0 ? ItemStack.EMPTY : firstOf(session, namespace);
			rows.add(new Popup.Row(icon, Component.literal(ModNames.of(namespace)), () -> config.modFilter.equals(namespace), () -> {
				config.modFilter = config.modFilter.equals(namespace) ? "" : namespace;
				this.saveAndRefresh();
			}));
		}
		this.openPopup(rows);
	}

	private static ItemStack firstOf(WtfiSession session, String namespace) {
		for (IndexEntry entry : session.index().entries()) {
			if (entry.namespace().equals(namespace)) return entry.stack;
		}
		return ItemStack.EMPTY;
	}

	private void openPopup(List<Popup.Row> rows) {
		int y = this.gridY + 2;
		// Menus may be wider than a narrow panel: grow leftwards over the screen if needed.
		Popup sized = new Popup(rows, 0, y, 200, this.py + this.ph - PAD, this.minecraft.font);
		int x = Math.max(8, Math.min(this.px + PAD, this.screen.width - 12 - sized.width));
		this.popup = new Popup(rows, x, y, 200, this.py + this.ph - PAD, this.minecraft.font);
	}

	private void saveAndRefresh() {
		WtfiConfig.get().save();
		this.scrollRow = 0;
		this.refresh(true);
	}

	// ---- input ----

	private boolean inPanel(double mx, double my) {
		return this.visible() && VanillaGui.inside(mx, my, this.px, this.py, this.pw, this.ph);
	}

	/** Returns true when the overlay used the click (the screen then never sees it). */
	private boolean mouseClicked(MouseButtonEvent event) {
		this.layout();
		double mx = event.x();
		double my = event.y();
		if (!this.visible()) return false;
		if (this.popup != null) {
			if (this.popup.contains(mx, my)) {
				this.popup.click(mx, my);
				return true;
			}
			this.popup = null;
			return this.inPanel(mx, my);
		}
		boolean inSearch = this.search.isMouseOver(mx, my);
		if (!inSearch && this.search.isFocused()) this.search.setFocused(false);
		if (!this.inPanel(mx, my)) return false;

		if (inSearch) {
			this.search.setFocused(true);
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) this.search.setValue("");
			this.search.mouseClicked(event, false);
			return true;
		}
		for (Button button : this.buttons) {
			if (button.contains(mx, my)) {
				net.minecraft.client.gui.components.AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
				button.onClick.run();
				return true;
			}
		}
		int sx = this.gridX + this.cols * SLOT + 2;
		if (VanillaGui.inside(mx, my, sx, this.gridY, VanillaGui.SCROLLER_WIDTH, this.rows * SLOT)) {
			this.draggingScrollbar = true;
			this.dragScrollbar(my);
			return true;
		}
		if (this.hovered != null) {
			this.clickEntry(this.hovered, event);
			return true;
		}
		return true;
	}

	private void clickEntry(IndexEntry entry, MouseButtonEvent event) {
		boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		if (WtfiConfig.get().cheatMode && !right) {
			CheatGiver.give(this.minecraft, entry.stack, event.hasShiftDown());
			return;
		}
		ItemPageScreen.open(this.minecraft, entry.stack, right && !WtfiConfig.get().cheatMode ? ItemPageScreen.Tab.USES : ItemPageScreen.Tab.OBTAIN);
	}

	private boolean mouseReleased(MouseButtonEvent event) {
		if (this.draggingScrollbar) {
			this.draggingScrollbar = false;
			return true;
		}
		return this.inPanel(event.x(), event.y());
	}

	private boolean mouseDragged(MouseButtonEvent event) {
		if (this.draggingScrollbar) {
			this.dragScrollbar(event.y());
			return true;
		}
		return false;
	}

	private void dragScrollbar(double my) {
		float progress = (float)((my - this.gridY - 7) / (this.rows * SLOT - 15));
		this.scrollRow = Math.round(Math.max(0, Math.min(1, progress)) * this.maxScroll());
	}

	private boolean mouseScrolled(double mx, double my, double amount) {
		if (this.popup != null && this.popup.contains(mx, my)) {
			this.popup.scroll(amount);
			return true;
		}
		if (!this.inPanel(mx, my)) return false;
		this.scrollRow -= (int)Math.signum(amount) * (this.minecraft.hasShiftDown() ? this.rows : 1);
		this.clampScroll();
		return true;
	}

	private boolean keyPressed(KeyEvent event) {
		if (this.search.isFocused() && this.visible()) {
			if (event.isEscape() || event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				this.search.setFocused(false);
				return true;
			}
			this.search.keyPressed(event);
			return true;
		}
		if (WtfiKeys.TOGGLE_PANEL.matches(event)) {
			WtfiConfig config = WtfiConfig.get();
			config.panelVisible = !config.panelVisible;
			config.save();
			this.popup = null;
			return true;
		}
		if (this.visible() && event.key() == InputConstants.KEY_F && event.hasControlDown()) {
			this.search.setFocused(true);
			return true;
		}
		boolean recipes = WtfiKeys.SHOW_RECIPES.matches(event);
		boolean uses = WtfiKeys.SHOW_USES.matches(event);
		if (recipes || uses) {
			ItemStack target = this.hoveredStack();
			if (!target.isEmpty()) {
				ItemPageScreen.open(this.minecraft, target, uses ? ItemPageScreen.Tab.USES : ItemPageScreen.Tab.OBTAIN);
				return true;
			}
		}
		return false;
	}

	/** The stack under the mouse: a library entry, or an item in one of the container's slots. */
	private ItemStack hoveredStack() {
		if (this.hovered != null) return this.hovered.stack;
		Slot slot = this.access.wtfi$getHoveredSlot();
		return slot != null && slot.hasItem() ? slot.getItem().copyWithCount(1) : ItemStack.EMPTY;
	}

	private boolean charTyped(CharacterEvent event) {
		if (this.search.isFocused() && this.visible()) {
			this.search.charTyped(event);
			return true;
		}
		return false;
	}

	private static final class Button {
		final Supplier<ItemStack> icon;
		final Supplier<Component> tooltip;
		final BooleanSupplier active;
		final Runnable onClick;
		int x;
		int y;

		Button(Supplier<ItemStack> icon, Supplier<Component> tooltip, BooleanSupplier active, Runnable onClick) {
			this.icon = icon;
			this.tooltip = tooltip;
			this.active = active;
			this.onClick = onClick;
		}

		boolean contains(double mx, double my) {
			return VanillaGui.inside(mx, my, this.x, this.y, BUTTON, BUTTON);
		}
	}
}
