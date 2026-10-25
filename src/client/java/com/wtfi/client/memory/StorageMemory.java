package com.wtfi.client.memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wtfi.Wtfi;
import com.wtfi.data.WtfiCodecs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * "Where did I put it?": remembers what was inside every container the player opened in this
 * world, keyed by position. Purely client side; saved per world/server under .minecraft/wtfi.
 */
public final class StorageMemory {
	private static final long OPEN_WINDOW_MS = 3000;
	private final Path file;
	private final HolderLookup.Provider registries;
	private final Map<String, Stored> containers = new LinkedHashMap<>();
	private int version;
	private boolean dirty;

	private @Nullable PendingOpen pending;
	private @Nullable Target openTarget;
	private @Nullable AbstractContainerMenu openMenu;

	private StorageMemory(Path file, HolderLookup.Provider registries) {
		this.file = file;
		this.registries = registries;
	}

	public static StorageMemory open(Minecraft minecraft) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("wtfi").resolve("storage");
		StorageMemory memory = new StorageMemory(dir.resolve(worldKey(minecraft) + ".dat"), minecraft.getConnection().registryAccess());
		memory.load();
		return memory;
	}

	private static String worldKey(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "sp_" + sanitize(world.getFileName() != null ? world.getFileName().toString() : "world");
		}
		ServerData data = minecraft.getCurrentServer();
		return "mp_" + sanitize(data != null ? data.ip : "unknown");
	}

	private static String sanitize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	// ---- tracking ----

	/** The player right-clicked a block; a container screen opening right after belongs to it. */
	public void onUseBlock(Identifier dimension, BlockPos pos, BlockState state) {
		this.pending = new PendingOpen(dimension, pos.immutable(), state, Util.getMillis());
	}

	public void onScreenOpened(Minecraft minecraft, AbstractContainerMenu menu) {
		// The same menu again (window resized, or back from an item page): keep tracking it.
		if (this.openTarget != null && this.openMenu == menu) return;
		this.openTarget = null;
		this.openMenu = menu;
		PendingOpen open = this.pending;
		this.pending = null;
		if (open == null || Util.getMillis() - open.time > OPEN_WINDOW_MS || minecraft.level == null) return;
		BlockState state = open.state;
		String kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (state.getBlock() instanceof EnderChestBlock) {
			this.openTarget = new Target("ender_chest", open.dimension, open.pos, kind);
			return;
		}
		BlockEntity blockEntity = minecraft.level.getBlockEntity(open.pos);
		if (!(blockEntity instanceof Container)) return;
		BlockPos pos = open.pos;
		if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			Direction toOther = ChestBlock.getConnectedDirection(state);
			BlockPos other = pos.relative(toOther);
			if (other.asLong() < pos.asLong()) pos = other;
		}
		this.openTarget = new Target(key(open.dimension, pos), open.dimension, pos, kind);
	}

	/**
	 * Snapshot the container's slots (not the player's inventory) when its screen goes away.
	 * If the menu is still open (we only switched to an item page), keep tracking it.
	 */
	public void onScreenClosed(AbstractContainerMenu menu, Inventory playerInventory, boolean stillOpen) {
		Target target = this.openTarget;
		if (menu != this.openMenu) return;
		if (!stillOpen) {
			this.openTarget = null;
			this.openMenu = null;
		}
		if (target == null) return;
		List<ItemStack> contents = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) contents.add(stack.copy());
		}
		if (contents.isEmpty()) {
			this.containers.remove(target.key);
		} else {
			this.containers.put(target.key, new Stored(target.kind, target.dimension, target.pos, Util.getEpochMillis(), contents));
		}
		this.version++;
		this.dirty = true;
	}

	/** Forget containers that were broken: called occasionally for loaded positions. */
	public void validate(Minecraft minecraft) {
		if (minecraft.level == null) return;
		Identifier dimension = minecraft.level.dimension().identifier();
		boolean removed = this.containers.values().removeIf(stored ->
			!stored.kind.equals("minecraft:ender_chest")
				&& stored.dimension.equals(dimension)
				&& minecraft.level.isLoaded(stored.pos)
				&& !(minecraft.level.getBlockEntity(stored.pos) instanceof Container)
		);
		if (removed) {
			this.version++;
			this.dirty = true;
		}
	}

	private static String key(Identifier dimension, BlockPos pos) {
		return dimension + "@" + pos.asLong();
	}

	// ---- queries ----

	public int version() {
		return this.version;
	}

	public int count(Item item) {
		int total = 0;
		for (Stored stored : this.containers.values()) {
			for (ItemStack stack : stored.contents) if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	public boolean contains(Item item) {
		for (Stored stored : this.containers.values()) {
			for (ItemStack stack : stored.contents) if (stack.is(item)) return true;
		}
		return false;
	}

	/** Containers holding this item, nearest first when the player is in the same dimension. */
	public List<Found> find(ItemStack wanted, @Nullable Identifier dimension, @Nullable BlockPos near) {
		List<Found> out = new ArrayList<>();
		for (Stored stored : this.containers.values()) {
			int count = 0;
			for (ItemStack stack : stored.contents) {
				if (stack.is(wanted.getItem()) && (wanted.getComponentsPatch().isEmpty() || ItemStack.isSameItemSameComponents(stack, wanted))) {
					count += stack.getCount();
				}
			}
			if (count > 0) out.add(new Found(stored, count));
		}
		if (near != null && dimension != null) {
			out.sort(Comparator.comparingDouble(f -> f.stored.dimension.equals(dimension) ? f.stored.pos.distSqr(near) : Double.MAX_VALUE));
		}
		return out;
	}

	// ---- persistence ----

	private void load() {
		if (!Files.exists(this.file)) return;
		try {
			CompoundTag tag = NbtIo.readCompressed(this.file, NbtAccounter.unlimitedHeap());
			RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, this.registries);
			Stored.LIST_CODEC.parse(ops, tag.get("containers")).result().ifPresent(list -> {
				for (Stored stored : list) {
					String key = stored.kind.equals("minecraft:ender_chest") ? "ender_chest" : key(stored.dimension, stored.pos);
					this.containers.put(key, stored);
				}
			});
		} catch (IOException | RuntimeException e) {
			Wtfi.LOGGER.warn("Could not read storage memory {}", this.file, e);
		}
	}

	public void save() {
		if (!this.dirty) return;
		this.dirty = false;
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, this.registries);
		Tag list = Stored.LIST_CODEC.encodeStart(ops, List.copyOf(this.containers.values())).result().orElse(null);
		if (list == null) return;
		CompoundTag root = new CompoundTag();
		root.put("containers", list);
		Path target = this.file;
		Util.ioPool().execute(() -> {
			try {
				Files.createDirectories(target.getParent());
				NbtIo.writeCompressed(root, target);
			} catch (IOException e) {
				Wtfi.LOGGER.warn("Could not save storage memory {}", target, e);
			}
		});
	}

	public record Stored(String kind, Identifier dimension, BlockPos pos, long seenAt, List<ItemStack> contents) {
		static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("kind").forGetter(Stored::kind),
			Identifier.CODEC.fieldOf("dimension").forGetter(Stored::dimension),
			BlockPos.CODEC.fieldOf("pos").forGetter(Stored::pos),
			Codec.LONG.fieldOf("seen").forGetter(Stored::seenAt),
			WtfiCodecs.lenientList(ItemStack.CODEC).fieldOf("items").forGetter(Stored::contents)
		).apply(i, Stored::new));
		static final Codec<List<Stored>> LIST_CODEC = WtfiCodecs.lenientList(CODEC);

		public boolean isEnderChest() {
			return this.kind.equals("minecraft:ender_chest");
		}
	}

	public record Found(Stored stored, int count) {
	}

	private record PendingOpen(Identifier dimension, BlockPos pos, BlockState state, long time) {
	}

	private record Target(String key, Identifier dimension, BlockPos pos, String kind) {
	}
}
