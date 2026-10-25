package com.wtfi.client.config;

import com.wtfi.client.ui.VanillaGui;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Settings, built from vanilla buttons. Reached from the library's settings button or Mod Menu. */
public final class WtfiConfigScreen extends Screen {
	private final @Nullable Screen parent;
	private final List<String> screenKeys = new ArrayList<>();

	public WtfiConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("wtfi.config.title"));
		this.parent = parent;
		if (parent instanceof AbstractContainerScreen<?> container) {
			this.screenKeys.add(com.wtfi.client.ui.LibraryOverlay.screenKey(container));
		}
		for (String key : WtfiConfig.get().screens.keySet()) {
			if (!this.screenKeys.contains(key)) this.screenKeys.add(key);
		}
		for (String key : List.of("inventory", "creative", "minecraft:crafting", "minecraft:furnace", "minecraft:generic_9x3", "minecraft:generic_9x6", "minecraft:anvil", "minecraft:enchantment")) {
			if (!this.screenKeys.contains(key)) this.screenKeys.add(key);
		}
	}

	@Override
	protected void init() {
		WtfiConfig config = WtfiConfig.get();
		int columnWidth = 150;
		int left = this.width / 2 - columnWidth - 5;
		int right = this.width / 2 + 5;
		int y = 32;

		this.toggle(left, y, "panel", config.panelVisible, v -> config.panelVisible = v);
		this.toggle(right, y, "cheat", config.cheatMode, v -> config.cheatMode = v);
		y += 24;
		this.toggle(left, y, "mod_name", config.showModName, v -> config.showModName = v);
		this.toggle(right, y, "new_items", config.highlightNewItems, v -> config.highlightNewItems = v);
		y += 24;
		this.toggle(left, y, "storage", config.storageMemory, v -> config.storageMemory = v);
		this.toggle(right, y, "hunt_hud", config.huntHud, v -> config.huntHud = v);
		y += 24;
		this.toggle(left, y, "checklist_hud", config.checklistHud, v -> config.checklistHud = v);
		this.addRenderableWidget(CycleButton.<Integer>builder(v -> Component.translatable(v == 0 ? "wtfi.config.corner.left" : "wtfi.config.corner.right"), config.hudCorner)
			.withValues(0, 1)
			.create(right, y, columnWidth, 20, Component.translatable("wtfi.config.hud_corner"), (b, v) -> {
				config.hudCorner = v;
				config.save();
			}));
		y += 24;
		this.addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)), config.maxColumns)
			.withValues(4, 6, 8, 9, 10, 12, 14, 16, 20)
			.create(left, y, columnWidth, 20, Component.translatable("wtfi.config.columns"), (b, v) -> {
				config.maxColumns = v;
				config.save();
			}));
		y += 32;

		int col = 0;
		for (String key : this.screenKeys) {
			int x = col % 2 == 0 ? left : right;
			this.addRenderableWidget(CycleButton.onOffBuilder(config.isScreenEnabled(key))
				.create(x, y, columnWidth, 20, Component.literal(prettyScreen(key)), (b, v) -> config.setScreenEnabled(key, v)));
			if (col % 2 == 1) y += 24;
			col++;
			if (y > this.height - 56) break;
		}
		if (col % 2 == 1) y += 24;

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
			.bounds(this.width / 2 - 100, this.height - 28, 200, 20)
			.build());
	}

	private void toggle(int x, int y, String key, boolean value, Consumer<Boolean> setter) {
		this.addRenderableWidget(CycleButton.onOffBuilder(value)
			.create(x, y, 150, 20, Component.translatable("wtfi.config." + key), (b, v) -> {
				setter.accept(v);
				WtfiConfig.get().save();
			}));
	}

	private static String prettyScreen(String key) {
		String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
		return "Panel: " + Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, VanillaGui.TEXT_LIGHT);
	}

	@Override
	public void onClose() {
		WtfiConfig.get().save();
		this.minecraft.gui.setScreen(this.parent);
	}
}
