package com.wtfi.server;

import com.wtfi.Wtfi;
import com.wtfi.data.DatasetIO;
import com.wtfi.data.WtfiDataset;
import com.wtfi.net.WtfiNetworking.DatasetPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the compressed dataset for the running server and sends it to every player with WTFI
 * installed: once when they join and again after /reload. Building runs off the server thread.
 */
public final class DatasetService {
	/** Set this system property to a file path to write the dataset there when the server starts. */
	public static final String EXPORT_PROPERTY = "wtfi.exportDataset";
	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicBoolean EXPORTED = new java.util.concurrent.atomic.AtomicBoolean();
	private static volatile byte @Nullable [] bytes;
	private static final Set<UUID> WAITING = new LinkedHashSet<>();

	private DatasetService() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(DatasetService::rebuild);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) rebuild(server);
		});
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
			if (joined) send(player);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			bytes = null;
			synchronized (WAITING) {
				WAITING.clear();
			}
		});
	}

	private static void rebuild(MinecraftServer server) {
		int generation = GENERATION.incrementAndGet();
		bytes = null;
		CompletableFuture.supplyAsync(() -> {
			try {
				WtfiDataset dataset = new DatasetBuilder(server).build();
				if (System.getProperty(EXPORT_PROPERTY) != null) {
					export(DatasetIO.encode(dataset.vanillaOnly(), server.reloadableRegistries().lookup()));
				}
				return DatasetIO.encode(dataset, server.reloadableRegistries().lookup());
			} catch (IOException | RuntimeException e) {
				Wtfi.LOGGER.error("WTFI could not build its dataset", e);
				return null;
			}
		}, Util.backgroundExecutor()).thenAcceptAsync(data -> {
			if (data == null || generation != GENERATION.get()) return;
			bytes = data;
			Wtfi.LOGGER.info("WTFI dataset ready ({} KB)", data.length / 1024);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				send(player);
			}
		}, server);
	}

	private static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, DatasetPayload.TYPE)) return;
		byte[] data = bytes;
		if (data == null) {
			// Still building: rebuild() sends to everyone online when it finishes.
			return;
		}
		ServerPlayNetworking.send(player, new DatasetPayload(data));
	}

	private static void export(byte[] data) {
		String target = System.getProperty(EXPORT_PROPERTY);
		if (target == null || target.isBlank()) return;
		// Only the first server of a run exports (tests may start a flat dedicated server later).
		if (!EXPORTED.compareAndSet(false, true)) return;
		try {
			Path path = Path.of(target);
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.write(path, data);
			Wtfi.LOGGER.info("WTFI dataset exported to {}", path.toAbsolutePath());
		} catch (IOException e) {
			Wtfi.LOGGER.error("WTFI could not export its dataset", e);
		}
	}
}
