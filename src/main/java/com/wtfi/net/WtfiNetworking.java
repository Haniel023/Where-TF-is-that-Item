package com.wtfi.net;

import com.wtfi.Wtfi;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

public final class WtfiNetworking {
	/** Upper bound for the compressed dataset; a big modpack is a few MB at most. */
	public static final int MAX_DATASET_BYTES = 32 * 1024 * 1024;

	private WtfiNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().registerLarge(DatasetPayload.TYPE, DatasetPayload.CODEC, MAX_DATASET_BYTES + 1024);
		PayloadTypeRegistry.serverboundPlay().register(GivePayload.TYPE, GivePayload.CODEC);
	}

	/** Server -> client: the gzipped NBT dataset. */
	public record DatasetPayload(byte[] data) implements CustomPacketPayload {
		public static final Type<DatasetPayload> TYPE = new Type<>(Wtfi.id("dataset"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DatasetPayload> CODEC =
			ByteBufCodecs.byteArray(MAX_DATASET_BYTES).map(DatasetPayload::new, DatasetPayload::data).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cheat mode asks for a stack. The server checks permissions. */
	public record GivePayload(ItemStack stack) implements CustomPacketPayload {
		public static final Type<GivePayload> TYPE = new Type<>(Wtfi.id("give"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GivePayload> CODEC =
			ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC.map(GivePayload::new, GivePayload::stack);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
