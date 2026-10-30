package com.meakaandre.mapmod;

import com.meakaandre.mapmod.map.MapServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The map mod: Satisfactory's compass strip and a painted fullscreen map with fog of war, filters, pins
 * and markers, for any modpack. Other mods plug in through {@link com.meakaandre.mapmod.api.MapModApi}.
 */
public class MapMod implements ModInitializer {
    /** The mod id: the namespace of everything the mod registers, its lang keys and its save folders. */
    public static final String ID = "mapmod";
    /**
     * The mod's display name, as gradle.properties sets it (mod_name, written into fabric.mod.json at build time): the one
     * place the name is kept, so a rename touches nothing else.
     */
    public static final String NAME = FabricLoader.getInstance().getModContainer(ID).map(c -> c.getMetadata().getName()).orElse(ID);
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitialize() {
        MapServer.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }
}
