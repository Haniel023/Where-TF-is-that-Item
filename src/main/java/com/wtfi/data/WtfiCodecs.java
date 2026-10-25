package com.wtfi.data;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class WtfiCodecs {
	public static final Codec<TagKey<Item>> ITEM_TAG = TagKey.codec(Registries.ITEM);

	private WtfiCodecs() {
	}

	/**
	 * A list codec that drops elements it can't decode instead of failing the whole list. The
	 * client may be missing an item the server has (or vice versa); one bad entry shouldn't cost
	 * every recipe.
	 */
	public static <T> Codec<List<T>> lenientList(Codec<T> element) {
		return new Codec<>() {
			@Override
			public <O> DataResult<Pair<List<T>, O>> decode(DynamicOps<O> ops, O input) {
				return ops.getList(input).map(stream -> {
					List<T> out = new ArrayList<>();
					stream.accept(value -> element.parse(ops, value).result().ifPresent(out::add));
					return Pair.of(out, input);
				});
			}

			@Override
			public <O> DataResult<O> encode(List<T> list, DynamicOps<O> ops, O prefix) {
				ListBuilder<O> builder = ops.listBuilder();
				for (T value : list) {
					DataResult<O> encoded = element.encodeStart(ops, value);
					if (encoded.isSuccess()) builder.add(encoded);
				}
				return builder.build(prefix);
			}

			@Override
			public String toString() {
				return "LenientList[" + element + "]";
			}
		};
	}
}
