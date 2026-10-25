package com.wtfi;

import com.wtfi.net.WtfiNetworking;
import com.wtfi.server.DatasetService;
import com.wtfi.server.GiveHandler;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Wtfi implements ModInitializer {
	public static final String MOD_ID = "wtfi";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		WtfiNetworking.register();
		DatasetService.register();
		GiveHandler.register();
	}
}
