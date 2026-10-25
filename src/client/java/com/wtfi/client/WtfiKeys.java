package com.wtfi.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wtfi.Wtfi;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class WtfiKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Wtfi.id("keys"));
	public static final KeyMapping TOGGLE_PANEL = key("toggle_panel", InputConstants.KEY_O);
	public static final KeyMapping SHOW_RECIPES = key("show_recipes", InputConstants.KEY_R);
	public static final KeyMapping SHOW_USES = key("show_uses", InputConstants.KEY_U);
	public static final KeyMapping LOOKUP_WORLD = key("lookup_world", InputConstants.KEY_H);
	public static final KeyMapping DISMISS_HUD = key("dismiss_hud", InputConstants.KEY_J);

	private WtfiKeys() {
	}

	private static KeyMapping key(String name, int key) {
		return new KeyMapping("key.wtfi." + name, InputConstants.Type.KEYBOARD, key, CATEGORY);
	}

	static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE_PANEL);
		KeyMappingHelper.registerKeyMapping(SHOW_RECIPES);
		KeyMappingHelper.registerKeyMapping(SHOW_USES);
		KeyMappingHelper.registerKeyMapping(LOOKUP_WORLD);
		KeyMappingHelper.registerKeyMapping(DISMISS_HUD);
	}
}
