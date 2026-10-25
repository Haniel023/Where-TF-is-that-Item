package com.wtfi.test;

import com.wtfi.client.WtfiSession;
import com.wtfi.client.cheat.CheatGiver;
import com.wtfi.client.config.WtfiConfig;
import com.wtfi.client.config.WtfiConfigScreen;
import com.wtfi.client.enchant.AnvilCalc;
import com.wtfi.client.enchant.AnvilPlanner;
import com.wtfi.client.enchant.TableSimulator;
import com.wtfi.client.hud.Checklist;
import com.wtfi.client.hud.HuntTarget;
import com.wtfi.client.index.ItemIndex;
import com.wtfi.client.knowledge.Knowledge;
import com.wtfi.client.knowledge.RecipeView;
import com.wtfi.client.ui.LibraryOverlay;
import com.wtfi.client.ui.page.ItemPageScreen;
import com.wtfi.data.WtfiDataset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Drives the real game: checks the dataset makes sense, exercises every page and HUD feature,
 * and saves screenshots under build/run/clientGameTest/screenshots for a human to review.
 */
public class WtfiClientGameTest implements FabricClientGameTest {
	private final List<String> report = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try {
			this.singleplayer(ctx);
			this.dedicated(ctx);
			this.vanillaFallback(ctx);
		} finally {
			this.writeReport();
		}
	}

	/** Pretend the server has no WTFI: the client must fall back to the bundled vanilla data. */
	private void vanillaFallback(ClientGameTestContext ctx) {
		System.setProperty("wtfi.forceBundled", "true");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			ctx.waitFor(mc -> WtfiSession.get() != null && WtfiSession.get().knowledge() != null, 20 * 120);
			ctx.runOnClient(mc -> {
				Knowledge k = WtfiSession.get().knowledge();
				this.log("vanilla fallback: fromServer=" + k.fromServer + " recipes=" + k.recipes.size() + " loot=" + k.data.loot().size()
					+ " worldgen=" + k.data.worldgen().size() + " beacon=" + k.obtainability(Items.BEACON));
				this.check("fallback uses bundled data", !k.fromServer && k.recipes.size() > 1000);
			});
			this.page(ctx, new ItemStack(Items.BEACON), ItemPageScreen.Tab.OBTAIN, "wtfi_60_fallback_beacon");
		} finally {
			System.clearProperty("wtfi.forceBundled");
		}
	}

	private void singleplayer(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				s.setSeed("wtfi");
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			long start = System.currentTimeMillis();
			ctx.waitFor(mc -> WtfiSession.get() != null && WtfiSession.get().knowledge() != null, 20 * 180);
			this.log("singleplayer knowledge ready after " + (System.currentTimeMillis() - start) + " ms");
			ctx.runOnClient(this::dataChecks);
			ctx.runOnClient(this::enchantChecks);

			// Library panel next to the inventory.
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitTicks(10);
			ctx.takeScreenshot("wtfi_01_inventory");

			ctx.runOnClient(mc -> LibraryOverlay.presetSearch("diamond"));
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitTicks(5);
			ctx.takeScreenshot("wtfi_02_search_diamond");
			ctx.runOnClient(mc -> LibraryOverlay.presetSearch("@minecraft #logs"));
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitTicks(5);
			ctx.takeScreenshot("wtfi_03_search_tag");
			ctx.runOnClient(mc -> LibraryOverlay.presetSearch(""));

			// Filter / sort / mod menus.
			for (String menu : List.of("filter", "sort", "mod")) {
				ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
				ctx.waitTicks(3);
				ctx.runOnClient(mc -> {
					if (LibraryOverlay.active() != null) LibraryOverlay.active().openMenu(menu);
				});
				ctx.waitTicks(3);
				ctx.takeScreenshot("wtfi_04_menu_" + menu);
			}

			// Live filters: craftable now (with planks in the inventory) and creative only, sorted by attack damage.
			ctx.runOnClient(mc -> {
				mc.player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
				mc.player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
			});
			ctx.waitTicks(25);
			ctx.runOnClient(mc -> {
				var session = WtfiSession.get();
				this.log("craftable now with planks+cobblestone: " + session.craftable().size() + " items, crafting table=" + session.craftable().contains(Items.CRAFTING_TABLE)
					+ ", furnace=" + session.craftable().contains(Items.FURNACE));
				this.check("craftable includes crafting table", session.craftable().contains(Items.CRAFTING_TABLE));
				var config = WtfiConfig.get();
				config.filters.clear();
				config.filters.add("CRAFTABLE_NOW");
				var view = new com.wtfi.client.index.LibraryView();
				view.update(session, "", true);
				this.log("library with Craftable now filter: " + view.results().length);
				config.filters.clear();
				config.filters.add("CREATIVE_ONLY");
				view.update(session, "", true);
				this.log("library with Creative only filter: " + view.results().length);
				config.filters.clear();
				config.filters.add("WEAPONS");
				config.sort = "ATTACK_DAMAGE";
				view.update(session, "", true);
				StringBuilder top = new StringBuilder();
				for (int i = 0; i < Math.min(5, view.results().length); i++) top.append(session.index().get(view.results()[i]).idLower).append(' ');
				this.log("weapons sorted by attack damage: " + top);
				config.filters.add("NON_RENEWABLE");
			});
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitTicks(5);
			ctx.takeScreenshot("wtfi_05_filter_weapons_nonrenewable");
			ctx.runOnClient(mc -> {
				var config = WtfiConfig.get();
				config.filters.clear();
				config.sort = "CREATIVE";
				config.save();
			});

			// Item pages, every tab.
			for (ItemPageScreen.Tab tab : List.of(ItemPageScreen.Tab.OVERVIEW, ItemPageScreen.Tab.OBTAIN, ItemPageScreen.Tab.WHERE, ItemPageScreen.Tab.USES)) {
				ctx.runOnClient(mc -> {
					mc.gui.setScreen(null);
					ItemPageScreen.open(mc, new ItemStack(Items.DIAMOND), tab);
				});
				ctx.waitTicks(5);
				ctx.takeScreenshot("wtfi_10_diamond_" + tab.name().toLowerCase());
			}
			this.page(ctx, new ItemStack(Items.IRON_INGOT), ItemPageScreen.Tab.OBTAIN, "wtfi_11_iron_obtain");
			this.page(ctx, new ItemStack(Items.BEACON), ItemPageScreen.Tab.OBTAIN, "wtfi_12_beacon_obtain");
			this.page(ctx, new ItemStack(Items.ELYTRA), ItemPageScreen.Tab.OBTAIN, "wtfi_13_elytra_obtain");
			this.page(ctx, new ItemStack(Items.COMMAND_BLOCK), ItemPageScreen.Tab.OBTAIN, "wtfi_14_command_block");
			this.page(ctx, PotionContents.createItemStack(Items.POTION, Potions.STRONG_SWIFTNESS), ItemPageScreen.Tab.OBTAIN, "wtfi_15_potion_brewing");
			this.page(ctx, new ItemStack(Items.ZOMBIE_SPAWN_EGG), ItemPageScreen.Tab.WHERE, "wtfi_16_zombie_where");
			this.page(ctx, new ItemStack(Items.ROTTEN_FLESH), ItemPageScreen.Tab.WHERE, "wtfi_17_rotten_flesh_where");
			this.page(ctx, new ItemStack(Items.IRON_AXE), ItemPageScreen.Tab.USES, "wtfi_18_axe_uses");

			// Enchanting: hold a diamond sword, carry some books, open the planner.
			ctx.runOnClient(mc -> {
				var player = mc.player;
				var registries = mc.getConnection().registryAccess();
				player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND_SWORD));
				player.getInventory().setItem(10, book(registries, Enchantments.SHARPNESS, 5));
				player.getInventory().setItem(11, book(registries, Enchantments.UNBREAKING, 3));
				player.getInventory().setItem(12, book(registries, Enchantments.LOOTING, 3));
				player.getInventory().setItem(13, book(registries, Enchantments.MENDING, 1));
				player.getInventory().setItem(14, book(registries, Enchantments.FIRE_ASPECT, 2));
			});
			this.page(ctx, new ItemStack(Items.DIAMOND_SWORD), ItemPageScreen.Tab.ENCHANT, "wtfi_20_sword_enchant", 80);
			ctx.runOnClient(mc -> mc.player.getInventory().setItem(mc.player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND_SWORD)));
			ItemStack mendingBook = ctx.computeOnClient(mc -> book(mc.getConnection().registryAccess(), Enchantments.MENDING, 1));
			this.page(ctx, mendingBook, ItemPageScreen.Tab.ENCHANT, "wtfi_21_mending_book", 60);
			this.page(ctx, mendingBook, ItemPageScreen.Tab.OBTAIN, "wtfi_22_mending_obtain");
			ItemStack sharpness = ctx.computeOnClient(mc -> book(mc.getConnection().registryAccess(), Enchantments.SHARPNESS, 5));
			this.page(ctx, sharpness, ItemPageScreen.Tab.ENCHANT, "wtfi_23_sharpness_book", 60);
			this.page(ctx, new ItemStack(Items.DIAMOND_SWORD), ItemPageScreen.Tab.OVERVIEW, "wtfi_24_sword_overview");

			// Storage memory: a chest with diamonds, opened and closed by the player.
			BlockPos chest = ctx.computeOnClient(mc -> mc.player.blockPosition().east(2));
			// Clear vines/grass around the spot so the click reaches the chest.
			sp.getServer().runCommand("fill " + (chest.getX() - 3) + " " + chest.getY() + " " + (chest.getZ() - 2) + " " + (chest.getX() + 2) + " " + (chest.getY() + 3) + " " + (chest.getZ() + 2) + " minecraft:air");
			sp.getServer().runCommand("setblock " + chest.getX() + " " + (chest.getY() - 1) + " " + chest.getZ() + " minecraft:stone");
			sp.getServer().runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " minecraft:chest");
			sp.getServer().runCommand("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.0 with minecraft:diamond 23");
			ctx.setScreen(() -> null);
			ctx.waitTicks(10);
			ctx.getInput().lookAt(chest);
			ctx.waitTicks(5);
			ctx.getInput().pressKey(options -> options.keyUse);
			ctx.waitTicks(20);
			ctx.takeScreenshot("wtfi_30_chest_open");
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				int remembered = WtfiSession.get().storage() == null ? -1 : WtfiSession.get().storage().count(Items.DIAMOND);
				this.log("storage remembers diamonds: " + remembered);
				this.check("storage memory remembers the chest", remembered == 23);
			});
			this.page(ctx, new ItemStack(Items.DIAMOND), ItemPageScreen.Tab.WHERE, "wtfi_31_diamond_where_storage");

			// In-world lookup on the chest.
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			ctx.getInput().lookAt(chest);
			ctx.waitTicks(3);
			ctx.getInput().pressKey(com.wtfi.client.WtfiKeys.LOOKUP_WORLD);
			ctx.waitTicks(10);
			ctx.takeScreenshot("wtfi_32_world_lookup");

			// Hunt mode + checklist HUD + storage highlight.
			ctx.runOnClient(mc -> {
				mc.gui.setScreen(null);
				HuntTarget.toggle(new ItemStack(Items.DIAMOND));
				Knowledge k = WtfiSession.get().knowledge();
				List<RecipeView> beacon = k.producedBy(Items.BEACON);
				if (!beacon.isEmpty()) Checklist.get().pin(beacon.getFirst());
				var storage = WtfiSession.get().storage();
				if (storage != null) {
					var found = storage.find(new ItemStack(Items.DIAMOND), mc.level.dimension().identifier(), mc.player.blockPosition());
					if (!found.isEmpty()) com.wtfi.client.memory.StorageHighlight.show(found.getFirst().stored());
				}
			});
			ctx.getInput().lookAt(mc(ctx).blockPosition().west(10));
			ctx.waitTicks(30);
			ctx.takeScreenshot("wtfi_33_hud");
			// Step back so the outlined chest is in view.
			ctx.runOnClient(mc -> {
				mc.player.getAbilities().flying = true;
				mc.player.onUpdateAbilities();
			});
			sp.getServer().runCommand("setblock " + (chest.getX() - 1) + " " + (chest.getY() + 1) + " " + chest.getZ() + " minecraft:air");
			sp.getServer().runCommand("tp @a " + (chest.getX() - 5) + ".5 " + (chest.getY() + 3) + " " + chest.getZ() + ".5");
			ctx.waitTicks(20);
			ctx.getInput().lookAt(chest);
			ctx.waitTicks(10);
			ctx.takeScreenshot("wtfi_34_highlight");
			this.page(ctx, new ItemStack(Items.DIAMOND), ItemPageScreen.Tab.WHERE, "wtfi_35_diamond_where_ore");
			ctx.runOnClient(mc -> {
				mc.gui.setScreen(null);
				ItemPageScreen.open(mc, new ItemStack(Items.DIAMOND), ItemPageScreen.Tab.WHERE);
				if (mc.gui.screen() != null) mc.gui.screen().mouseScrolled(0, 0, 0, -6);
			});
			ctx.waitTicks(3);
			ctx.takeScreenshot("wtfi_36_diamond_where_scrolled");

			// Closing HUD sections: click a ✕ with chat open, then the close key.
			ctx.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
			ctx.waitTicks(3);
			int[] firstClose = ctx.computeOnClient(mc -> {
				var buttons = com.wtfi.client.hud.WtfiHud.closeButtons();
				return buttons.isEmpty() ? null : buttons.getFirst();
			});
			if (firstClose != null) {
				double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
				ctx.getInput().setCursorPos((firstClose[0] + 3) * scale, (firstClose[1] + 3) * scale);
				ctx.waitTicks(2);
				ctx.takeScreenshot("wtfi_37_hud_close_hover");
				ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
				ctx.waitTicks(2);
			}
			ctx.runOnClient(mc -> this.log("after clicking first ✕: highlight=" + (com.wtfi.client.memory.StorageHighlight.hudLine(mc) != null)
				+ " hunt=" + !HuntTarget.target().isEmpty() + " checklists=" + Checklist.get().goals().size()));
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);
			ctx.getInput().pressKey(com.wtfi.client.WtfiKeys.DISMISS_HUD);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				this.log("after close key: hunt=" + !HuntTarget.target().isEmpty() + " checklists=" + Checklist.get().goals().size());
				this.check("close key removes hunt", HuntTarget.target().isEmpty());
			});
			ctx.getInput().pressKey(com.wtfi.client.WtfiKeys.DISMISS_HUD);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> this.check("close key removes checklist", Checklist.get().goals().isEmpty()));
			ctx.takeScreenshot("wtfi_38_hud_closed");

			// Cheat mode gives items.
			ctx.runOnClient(mc -> {
				WtfiConfig.get().cheatMode = true;
				mc.player.getInventory().clearContent();
				CheatGiver.give(mc, new ItemStack(Items.NETHERITE_INGOT), true);
			});
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> {
				int count = WtfiSession.inventoryCounts(mc.player.getInventory()).getOrDefault(Items.NETHERITE_INGOT, 0);
				this.log("cheat mode gave netherite ingots: " + count + " (expected 64)");
				WtfiConfig.get().cheatMode = false;
			});

			ctx.setScreen(() -> new WtfiConfigScreen(null));
			ctx.waitTicks(5);
			ctx.takeScreenshot("wtfi_40_config");
			ctx.setScreen(() -> null);

			this.gallery(ctx, sp, chest);
		}
	}

	/** Clean 1080p shots for the Modrinth gallery (no toasts, no chat). */
	private void gallery(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
		sp.getServer().runCommand("gamemode survival @a");
		sp.getServer().runCommand("time set day");
		sp.getServer().runCommand("weather clear");
		ctx.runOnClient(mc -> {
			var registries = mc.getConnection().registryAccess();
			var inv = mc.player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.IRON_PICKAXE));
			inv.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(2, new ItemStack(Items.TORCH, 48));
			inv.setItem(3, new ItemStack(Items.BREAD, 12));
			inv.setItem(4, new ItemStack(Items.OAK_LOG, 32));
			inv.setItem(5, new ItemStack(Items.COBBLESTONE, 64));
			inv.setItem(9, new ItemStack(Items.IRON_SWORD));
			inv.setItem(10, book(registries, Enchantments.SHARPNESS, 5));
			inv.setItem(11, book(registries, Enchantments.UNBREAKING, 3));
			inv.setItem(12, book(registries, Enchantments.LOOTING, 3));
			inv.setItem(13, book(registries, Enchantments.MENDING, 1));
			inv.setItem(14, book(registries, Enchantments.SWEEPING_EDGE, 3));
			inv.setItem(15, new ItemStack(Items.STICK, 20));
			inv.setItem(18, new ItemStack(Items.REDSTONE, 30));
			inv.setItem(19, new ItemStack(Items.RAW_IRON, 9));
			inv.setItem(20, new ItemStack(Items.COAL, 17));
			inv.setSelectedSlot(1);
			LibraryOverlay.presetSearch("");
		});
		ctx.waitTicks(10);

		ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		this.galleryShot(ctx, "01_library");
		ctx.runOnClient(mc -> LibraryOverlay.presetSearch("diamond"));
		ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		this.galleryShot(ctx, "02_search");
		ctx.runOnClient(mc -> {
			LibraryOverlay.presetSearch("");
			if (LibraryOverlay.active() != null) LibraryOverlay.active().openMenu("filter");
		});
		this.galleryShot(ctx, "03_filters");
		ctx.setScreen(() -> null);

		this.galleryPage(ctx, new ItemStack(Items.DIAMOND_PICKAXE), ItemPageScreen.Tab.OBTAIN, 0, "04_how_to_get");
		this.galleryPage(ctx, new ItemStack(Items.DIAMOND), ItemPageScreen.Tab.WHERE, 6, "05_where_to_find");
		this.galleryPage(ctx, new ItemStack(Items.DIAMOND_SWORD), ItemPageScreen.Tab.ENCHANT, 0, "06_enchanting_odds");
		this.galleryPage(ctx, new ItemStack(Items.DIAMOND_SWORD), ItemPageScreen.Tab.ENCHANT, 40, "07_anvil_planner");
		this.galleryPage(ctx, PotionContents.createItemStack(Items.POTION, Potions.STRONG_SWIFTNESS), ItemPageScreen.Tab.OBTAIN, 2, "08_brewing_chain");
		this.galleryPage(ctx, new ItemStack(Items.ZOMBIE_SPAWN_EGG), ItemPageScreen.Tab.OVERVIEW, 0, "09_mob_lookup");

		ctx.runOnClient(mc -> {
			mc.gui.setScreen(null);
			HuntTarget.toggle(new ItemStack(Items.DIAMOND));
			List<RecipeView> pickaxe = WtfiSession.get().knowledge().producedBy(Items.DIAMOND_PICKAXE);
			if (!pickaxe.isEmpty()) Checklist.get().pin(pickaxe.getFirst());
			var storage = WtfiSession.get().storage();
			if (storage != null) {
				var found = storage.find(new ItemStack(Items.DIAMOND), mc.level.dimension().identifier(), mc.player.blockPosition());
				if (!found.isEmpty()) com.wtfi.client.memory.StorageHighlight.show(found.getFirst().stored());
			}
		});
		sp.getServer().runCommand("tp @a " + (chest.getX() - 6) + ".5 " + (chest.getY() + 1) + " " + chest.getZ() + ".5");
		ctx.waitTicks(30);
		ctx.getInput().lookAt(chest);
		ctx.waitTicks(10);
		this.galleryShot(ctx, "10_hud_hunt_checklist_storage");
	}

	private void galleryPage(ClientGameTestContext ctx, ItemStack stack, ItemPageScreen.Tab tab, int scrollNotches, String name) {
		ctx.runOnClient(mc -> {
			mc.gui.setScreen(null);
			ItemPageScreen.open(mc, stack, tab);
		});
		ctx.waitTicks(40);
		if (scrollNotches > 0) {
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() != null) mc.gui.screen().mouseScrolled(0, 0, 0, -scrollNotches);
			});
			ctx.waitTicks(2);
		}
		this.galleryShot(ctx, name);
	}

	private void galleryShot(ClientGameTestContext ctx, String name) {
		ctx.runOnClient(mc -> {
			mc.gui.toastManager().clear();
			mc.gui.hud.getChat().clearMessages(false);
		});
		ctx.waitTicks(3);
		Path dir = FabricLoader.getInstance().getGameDir().resolve("gallery");
		ctx.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of(name)
			.disableCounterPrefix().withDestinationDir(dir));
	}

	private void dedicated(ClientGameTestContext ctx) {
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
			var connection = server.connect()) {
			connection.waitForChunksRender();
			long start = System.currentTimeMillis();
			ctx.waitFor(mc -> WtfiSession.get() != null && WtfiSession.get().knowledge() != null, 20 * 180);
			ctx.runOnClient(mc -> {
				Knowledge k = WtfiSession.get().knowledge();
				this.log("dedicated server: knowledge after " + (System.currentTimeMillis() - start) + " ms, fromServer=" + k.fromServer + ", recipes=" + k.recipes.size());
			});
			this.page(ctx, new ItemStack(Items.DIAMOND_PICKAXE), ItemPageScreen.Tab.OBTAIN, "wtfi_50_dedicated_pickaxe");
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitTicks(10);
			ctx.takeScreenshot("wtfi_51_dedicated_inventory");
			ctx.setScreen(() -> null);
		}
	}

	private static net.minecraft.client.player.LocalPlayer mc(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player);
	}

	private void page(ClientGameTestContext ctx, ItemStack stack, ItemPageScreen.Tab tab, String name) {
		this.page(ctx, stack, tab, name, 6);
	}

	private void page(ClientGameTestContext ctx, ItemStack stack, ItemPageScreen.Tab tab, String name, int wait) {
		ctx.runOnClient(mc -> {
			mc.gui.setScreen(null);
			ItemPageScreen.open(mc, stack, tab);
		});
		ctx.waitTicks(wait);
		ctx.takeScreenshot(name);
	}

	private static ItemStack book(net.minecraft.core.HolderLookup.Provider registries, ResourceKey<Enchantment> key, int level) {
		Holder<Enchantment> holder = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		return EnchantmentHelper.createBook(new EnchantmentInstance(holder, level));
	}

	private void dataChecks(Minecraft mc) {
		WtfiSession session = WtfiSession.get();
		ItemIndex index = session.index();
		Knowledge k = session.knowledge();
		WtfiDataset d = k.data;
		this.log("index entries=" + index.size() + " namespaces=" + index.namespaces());
		this.log("dataset recipes=" + d.recipes().size() + " loot=" + d.loot().size() + " trades=" + d.trades().size() + " worldgen=" + d.worldgen().size()
			+ " structures=" + d.structures().size() + " spawns=" + d.spawns().size() + " advancements=" + d.advancements().size() + " props=" + d.itemProps().size()
			+ " transforms=" + d.transforms().size() + " fromServer=" + k.fromServer);
		this.check("recipes > 1000", d.recipes().size() > 1000);
		this.check("loot > 1000", d.loot().size() > 1000);
		this.check("trades > 100", d.trades().size() > 100);
		this.check("worldgen > 100", d.worldgen().size() > 100);
		this.check("advancements > 50", d.advancements().size() > 50);
		this.check("transforms > 20", d.transforms().size() > 20);

		for (var item : List.of(Items.DIAMOND, Items.ELYTRA, Items.COMMAND_BLOCK, Items.BEDROCK, Items.PETRIFIED_OAK_SLAB, Items.WHEAT, Items.NETHERITE_INGOT,
			Items.SPONGE, Items.DRAGON_EGG, Items.COBBLESTONE, Items.OAK_LOG, Items.EMERALD, Items.NAME_TAG, Items.TOTEM_OF_UNDYING, Items.GOAT_HORN,
			Items.SPAWNER, Items.BUDDING_AMETHYST, Items.STONE, Items.GLASS, Items.TRIDENT, Items.HEART_OF_THE_SEA, Items.ENCHANTED_GOLDEN_APPLE,
			Items.MUSIC_DISC_13, Items.ZOMBIE_SPAWN_EGG, Items.REINFORCED_DEEPSLATE, Items.MUD, Items.ANCIENT_DEBRIS)) {
			var r = k.renewability(item);
			this.log(String.format("  %-28s %-14s renewable=%s (%s) recipes=%d loot=%d trades=%d gen=%d",
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath(), k.obtainability(item), r.renewable(), r.reason(),
				k.producedBy(item).size(), k.loot(new ItemStack(item)).size(), k.tradesGiving(new ItemStack(item)).size(), k.generation(item).size()));
		}
		this.check("beacon survival", k.obtainability(Items.BEACON) == Knowledge.Obtainability.SURVIVAL);
		this.check("nether star survival", k.obtainability(Items.NETHER_STAR) == Knowledge.Obtainability.SURVIVAL);
		this.check("bee nest survival", k.obtainability(Items.BEE_NEST) == Knowledge.Obtainability.SURVIVAL);
		this.check("mangrove propagule survival", k.obtainability(Items.MANGROVE_PROPAGULE) == Knowledge.Obtainability.SURVIVAL);
		this.check("budding amethyst creative only", k.obtainability(Items.BUDDING_AMETHYST) == Knowledge.Obtainability.CREATIVE_ONLY);
		this.check("diamond generates via ore", !k.generationSources(Items.DIAMOND).isEmpty());
		var creativeOnly = new ArrayList<String>();
		for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (k.obtainability(item) == Knowledge.Obtainability.CREATIVE_ONLY && !item.toString().contains("spawn_egg")) {
				creativeOnly.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath());
			}
		}
		this.log("creative-only (excluding spawn eggs): " + creativeOnly.size() + " " + creativeOnly);
		this.check("diamond survival", k.obtainability(Items.DIAMOND) == Knowledge.Obtainability.SURVIVAL);
		this.check("command block creative only", k.obtainability(Items.COMMAND_BLOCK) == Knowledge.Obtainability.CREATIVE_ONLY);
		this.check("bedrock creative only", k.obtainability(Items.BEDROCK) == Knowledge.Obtainability.CREATIVE_ONLY);
		this.check("petrified oak slab creative only", k.obtainability(Items.PETRIFIED_OAK_SLAB) == Knowledge.Obtainability.CREATIVE_ONLY);
		this.check("elytra survival", k.obtainability(Items.ELYTRA) == Knowledge.Obtainability.SURVIVAL);
		this.check("wheat renewable", k.renewability(Items.WHEAT).renewable());
		this.check("ancient debris non-renewable", !k.renewability(Items.ANCIENT_DEBRIS).renewable());
		var diamondGen = k.generation(Items.DIAMOND_ORE);
		this.log("diamond ore gen: " + diamondGen.stream().map(g -> g.featureType() + " " + g.minY() + ".." + g.maxY() + " peak " + g.peakY() + " biomes " + g.biomes().size()).toList());
		this.check("diamond ore generates with heights", diamondGen.stream().anyMatch(WtfiDataset.GenInfo::hasHeight));
		this.log("diamond loot sources: " + k.loot(new ItemStack(Items.DIAMOND)).stream().map(l -> l.table().getPath() + "=" + l.chance()).limit(12).toList());
		this.log("emerald trades: " + k.tradesGiving(new ItemStack(Items.EMERALD)).size() + ", librarian book trades: " + k.tradesGiving(new ItemStack(Items.ENCHANTED_BOOK)).size());
		this.log("brewing recipes: " + k.recipes.stream().filter(r -> r.layout().equals("brewing")).count());
		this.log("advancements for diamond: " + k.advancements(Items.DIAMOND).stream().map(a -> a.id().getPath()).toList());
		this.log("axe transforms: " + k.data.transforms().stream().filter(t -> t.transformer().getPath().equals("axe")).count());
		var props = k.props(Items.COAL);
		this.log("coal props: " + props);
		this.log("composter wheat seeds: " + k.props(Items.WHEAT_SEEDS));
		this.log("craftable now: " + session.craftable().size());
		Identifier zombie = Identifier.withDefaultNamespace("zombie");
		this.log("zombie spawns in " + k.spawns(zombie).stream().mapToInt(s -> s.biomes().size()).sum() + " biome entries");
	}

	private void enchantChecks(Minecraft mc) {
		var registries = mc.getConnection().registryAccess();
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		ItemStack sharp5 = book(registries, Enchantments.SHARPNESS, 5);
		AnvilCalc.Result r = AnvilCalc.combine(sword, sharp5, false);
		this.log("anvil sword + sharpness V book: valid=" + r.valid() + " cost=" + r.cost() + " (vanilla: 5)");
		this.check("anvil sharpness V book costs 5", r.valid() && r.cost() == 5);
		ItemStack sharp4a = book(registries, Enchantments.SHARPNESS, 4);
		ItemStack sharp4b = book(registries, Enchantments.SHARPNESS, 4);
		AnvilCalc.Result merged = AnvilCalc.combine(sharp4a, sharp4b, false);
		this.log("anvil sharpness IV + IV books: cost=" + merged.cost() + " result=" + merged.result().get(DataComponents.STORED_ENCHANTMENTS));
		List<ItemStack> books = List.of(book(registries, Enchantments.SHARPNESS, 5), book(registries, Enchantments.UNBREAKING, 3), book(registries, Enchantments.LOOTING, 3),
			book(registries, Enchantments.MENDING, 1), book(registries, Enchantments.FIRE_ASPECT, 2), book(registries, Enchantments.SWEEPING_EDGE, 3));
		long t0 = System.currentTimeMillis();
		AnvilPlanner.Plan plan = AnvilPlanner.plan(sword, books).join();
		this.log("planner 6 books: " + (plan == null ? "none" : plan.totalLevels() + " levels, max step " + plan.maxStep() + ", " + plan.steps().size() + " steps") + " in " + (System.currentTimeMillis() - t0) + " ms");
		this.check("planner finds a plan", plan != null && plan.maxStep() < 40);
		t0 = System.currentTimeMillis();
		TableSimulator.Odds odds = TableSimulator.odds(sword, registries).join();
		var sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
		this.log("table odds sword sharpness(any) @30=" + odds.at(sharpness, 1, 30) + " sharpness V best level=" + odds.bestLevel(sharpness, 5)
			+ " in " + (System.currentTimeMillis() - t0) + " ms");
		this.check("table can give sharpness", odds.at(sharpness, 1, 30) > 0.1f);
	}

	private void check(String name, boolean ok) {
		this.log((ok ? "PASS " : "FAIL ") + name);
	}

	private void log(String line) {
		this.report.add(line);
		com.wtfi.Wtfi.LOGGER.info("[wtfi-test] {}", line);
	}

	private void writeReport() {
		try {
			Path out = FabricLoader.getInstance().getGameDir().resolve("wtfi-test-report.txt");
			Files.write(out, this.report);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
