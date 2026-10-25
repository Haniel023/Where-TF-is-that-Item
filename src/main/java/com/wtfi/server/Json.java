package com.wtfi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import org.jspecify.annotations.Nullable;

/**
 * Small helpers for reading game objects back as their datapack JSON. Analysing the JSON form
 * is what lets WTFI understand modded loot entries, features and trades it has never seen,
 * as long as they follow vanilla's shapes.
 */
final class Json {
	private Json() {
	}

	static <T> @Nullable JsonElement encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
		try {
			return codec.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), value).result().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Arrays become their elements, a single value becomes a one-element list, null becomes empty. */
	static List<JsonElement> list(@Nullable JsonElement element) {
		if (element == null || element.isJsonNull()) return List.of();
		if (element.isJsonArray()) {
			List<JsonElement> out = new ArrayList<>();
			element.getAsJsonArray().forEach(out::add);
			return out;
		}
		return List.of(element);
	}

	static @Nullable String string(@Nullable JsonObject object, String key) {
		if (object == null) return null;
		JsonElement e = object.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	static @Nullable JsonObject object(@Nullable JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	static String type(JsonObject object) {
		String type = string(object, "type");
		if (type == null) return "";
		return type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
	}

	static float number(@Nullable JsonElement element, float fallback) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			return element.getAsFloat();
		}
		return fallback;
	}

	/**
	 * Reads a number provider (constant, uniform, binomial, weighted list...) as {min, max, mean}.
	 * Anything unknown counts as the fallback.
	 */
	static float[] range(@Nullable JsonElement element, float fallback) {
		if (element == null || element.isJsonNull()) return new float[] {fallback, fallback, fallback};
		if (element.isJsonPrimitive()) {
			JsonPrimitive p = element.getAsJsonPrimitive();
			if (p.isNumber()) {
				float v = p.getAsFloat();
				return new float[] {v, v, v};
			}
			return new float[] {fallback, fallback, fallback};
		}
		JsonObject o = object(element);
		if (o == null) return new float[] {fallback, fallback, fallback};
		switch (type(o)) {
			case "constant" -> {
				float v = number(o.get("value"), fallback);
				return new float[] {v, v, v};
			}
			case "uniform" -> {
				float min = range(first(o, "min", "min_inclusive"), 0)[2];
				float max = range(first(o, "max", "max_inclusive"), min)[2];
				return new float[] {min, max, (min + max) / 2f};
			}
			case "binomial" -> {
				float n = range(o.get("n"), 0)[2];
				float p = range(o.get("p"), 0)[2];
				return new float[] {0, n, n * p};
			}
			case "weighted_list" -> {
				float min = Float.MAX_VALUE;
				float max = -Float.MAX_VALUE;
				float total = 0;
				float sum = 0;
				for (JsonElement entry : list(o.get("distribution"))) {
					JsonObject e = object(entry);
					if (e == null) continue;
					float data = range(e.get("data"), 0)[2];
					float weight = number(e.get("weight"), 1);
					min = Math.min(min, data);
					max = Math.max(max, data);
					total += weight;
					sum += data * weight;
				}
				if (total <= 0) return new float[] {fallback, fallback, fallback};
				return new float[] {min, max, sum / total};
			}
			case "clamped" -> {
				float[] inner = range(o.get("source"), fallback);
				float lo = number(o.get("min_inclusive"), inner[0]);
				float hi = number(o.get("max_inclusive"), inner[1]);
				return new float[] {Math.max(lo, inner[0]), Math.min(hi, inner[1]), Math.max(lo, Math.min(hi, inner[2]))};
			}
			default -> {
				// Many providers keep a plain "value" or "min"/"max"; use them when present.
				if (o.has("value")) return range(o.get("value"), fallback);
				if (o.has("min") || o.has("max")) {
					float min = range(o.get("min"), fallback)[2];
					float max = range(o.get("max"), min)[2];
					return new float[] {min, max, (min + max) / 2f};
				}
				return new float[] {fallback, fallback, fallback};
			}
		}
	}

	static @Nullable JsonElement first(JsonObject o, String... keys) {
		for (String key : keys) {
			if (o.has(key)) return o.get(key);
		}
		return null;
	}

	/** Every string value anywhere inside {@code element}, skipping subtrees under the given keys. */
	static void strings(@Nullable JsonElement element, java.util.Set<String> skipKeys, java.util.function.Consumer<String> out) {
		if (element == null || element.isJsonNull()) return;
		if (element.isJsonPrimitive()) {
			if (element.getAsJsonPrimitive().isString()) out.accept(element.getAsString());
		} else if (element.isJsonArray()) {
			for (JsonElement e : element.getAsJsonArray()) strings(e, skipKeys, out);
		} else {
			for (var entry : element.getAsJsonObject().entrySet()) {
				if (!skipKeys.contains(entry.getKey())) strings(entry.getValue(), skipKeys, out);
			}
		}
	}

	static JsonArray array(List<JsonElement> elements) {
		JsonArray array = new JsonArray();
		elements.forEach(array::add);
		return array;
	}
}
