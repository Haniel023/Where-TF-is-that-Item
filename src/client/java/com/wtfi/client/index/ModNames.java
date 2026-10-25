package com.wtfi.client.index;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/** Display names for namespaces, taken from the owning mod's metadata. */
public final class ModNames {
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private ModNames() {
	}

	public static String of(String namespace) {
		return CACHE.computeIfAbsent(namespace, ns -> {
			if (ns.equals("minecraft")) {
				return "Minecraft";
			}
			return FabricLoader.getInstance()
				.getModContainer(ns)
				.map(c -> c.getMetadata().getName())
				.orElseGet(() -> prettify(ns));
		});
	}

	private static String prettify(String namespace) {
		StringBuilder out = new StringBuilder();
		for (String part : namespace.split("[_\\-.]")) {
			if (part.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
		}
		return out.isEmpty() ? namespace : out.toString();
	}
}
