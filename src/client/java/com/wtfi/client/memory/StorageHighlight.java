package com.wtfi.client.memory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/** Outlines a remembered container in the world (through walls) for half a minute. */
public final class StorageHighlight {
	private static final long DURATION_MS = 30_000;
	private static StorageMemory.@Nullable Stored target;
	private static long until;

	private StorageHighlight() {
	}

	public static void show(StorageMemory.Stored stored) {
		target = stored;
		until = Util.getMillis() + DURATION_MS;
	}

	public static void clear() {
		target = null;
	}

	/** Called every client tick, inside the game's per-tick gizmo collection. */
	public static void tick(Minecraft minecraft) {
		StorageMemory.Stored stored = target;
		if (stored == null) return;
		if (Util.getMillis() > until || minecraft.level == null) {
			target = null;
			return;
		}
		if (!stored.dimension().equals(minecraft.level.dimension().identifier())) return;
		Gizmos.cuboid(stored.pos(), 0.02f, GizmoStyle.strokeAndFill(0xFFFFD84A, 2.5f, 0x38FFD84A)).setAlwaysOnTop();
	}

	/** One HUD line with distance and direction to the highlighted container, or null. */
	public static @Nullable Component hudLine(Minecraft minecraft) {
		StorageMemory.Stored stored = target;
		LocalPlayer player = minecraft.player;
		if (stored == null || player == null || minecraft.level == null) return null;
		if (!stored.dimension().equals(minecraft.level.dimension().identifier())) {
			return Component.translatable("wtfi.highlight.other_dimension");
		}
		int distance = (int)Math.sqrt(stored.pos().distSqr(player.blockPosition()));
		return Component.translatable("wtfi.highlight.line", stored.pos().getX(), stored.pos().getY(), stored.pos().getZ(), distance,
			com.wtfi.client.ui.page.Sources.direction(player.blockPosition(), stored.pos()));
	}
}
