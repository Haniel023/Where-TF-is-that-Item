package com.wtfi.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;

/** Dataset <-> gzipped NBT bytes. Ids are written as strings, so the bytes work across registry layouts. */
public final class DatasetIO {
	private DatasetIO() {
	}

	public static byte[] encode(WtfiDataset dataset, HolderLookup.Provider registries) throws IOException {
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		Tag tag = WtfiDataset.CODEC.encodeStart(ops, dataset).getOrThrow(IOException::new);
		if (!(tag instanceof CompoundTag compound)) {
			throw new IOException("Dataset did not encode to a compound tag");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
		NbtIo.writeCompressed(compound, out);
		return out.toByteArray();
	}

	public static WtfiDataset decode(byte[] bytes, HolderLookup.Provider registries) throws IOException {
		return decode(new ByteArrayInputStream(bytes), registries);
	}

	public static WtfiDataset decode(InputStream in, HolderLookup.Provider registries) throws IOException {
		CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		WtfiDataset dataset = WtfiDataset.CODEC.parse(ops, tag).getOrThrow(IOException::new);
		if (dataset.format() != WtfiDataset.FORMAT) {
			throw new IOException("Unsupported WTFI dataset format " + dataset.format());
		}
		return dataset;
	}
}
