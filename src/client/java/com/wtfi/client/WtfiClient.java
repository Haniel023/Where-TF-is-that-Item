package com.wtfi.client;

import com.wtfi.Wtfi;
import com.wtfi.client.enchant.TableSimulator;
import com.wtfi.client.hud.Checklist;
import com.wtfi.client.hud.HuntTarget;
import com.wtfi.client.hud.WtfiHud;
import com.wtfi.client.memory.StorageHighlight;
import com.wtfi.client.memory.StorageMemory;
import com.wtfi.client.ui.LibraryOverlay;
import com.wtfi.net.WtfiNetworking.DatasetPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionResult;
import org.jspecify.annotations.Nullable;

public class WtfiClient implements ClientModInitializer {
	/** A dataset that arrived before the session was set up on join. */
	private static byte @Nullable [] pendingDataset;

	@Override
	public void onInitializeClient() {
		WtfiKeys.register();

		ClientPlayNetworking.registerGlobalReceiver(DatasetPayload.TYPE, (payload, context) -> {
			WtfiSession session = WtfiSession.get();
			if (session != null) {
				session.acceptDataset(payload.data());
			} else {
				pendingDataset = payload.data();
			}
		});

		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> client.execute(() -> {
			try {
				WtfiSession.start(client);
			} catch (RuntimeException e) {
				Wtfi.LOGGER.error("WTFI failed to start for this world", e);
				return;
			}
			byte[] pending = pendingDataset;
			pendingDataset = null;
			if (pending != null && WtfiSession.get() != null) WtfiSession.get().acceptDataset(pending);
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(() -> {
			WtfiSession.stop();
			Checklist.get().clear();
			HuntTarget.clear();
			StorageHighlight.clear();
			TableSimulator.clearCache();
			pendingDataset = null;
		}));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			WtfiSession session = WtfiSession.get();
			if (session == null) return;
			session.tick();
			StorageHighlight.tick(client);
			WorldLookup.tick(client);
			while (WtfiKeys.DISMISS_HUD.consumeClick()) {
				WtfiHud.dismissTop();
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ChatScreen) {
				// Chat frees the mouse: let it click the ✕ on the WTFI HUD box.
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !WtfiHud.click(event.x(), event.y()));
				return;
			}
			if (!(screen instanceof AbstractContainerScreen<?> container) || WtfiSession.get() == null) return;
			LibraryOverlay.attach(client, screen);
			StorageMemory storage = WtfiSession.get().storage();
			if (storage != null) {
				storage.onScreenOpened(client, container.getMenu());
				ScreenEvents.remove(screen).register(s -> {
					if (client.player != null) {
						storage.onScreenClosed(container.getMenu(), client.player.getInventory(), client.player.containerMenu == container.getMenu());
					}
				});
			}
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide()) {
				WtfiSession session = WtfiSession.get();
				if (session != null && session.storage() != null) {
					session.storage().onUseBlock(level.dimension().identifier(), hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
				}
			}
			return InteractionResult.PASS;
		});

		HudElementRegistry.addLast(Wtfi.id("hud"), WtfiHud::extract);
	}
}
