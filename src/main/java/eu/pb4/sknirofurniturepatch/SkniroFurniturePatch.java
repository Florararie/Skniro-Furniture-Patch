package eu.pb4.sknirofurniturepatch;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.sknirofurniturepatch.impl.FurnitureBlocks;
import eu.pb4.sknirofurniturepatch.impl.FurnitureItems;
import eu.pb4.sknirofurniturepatch.impl.FurnitureModels;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes Skniro's Furniture work for clients that do not have it.
 *
 * <p>The mod is almost entirely blocks: a few thousand pieces of furniture, each one a plain block
 * with one JSON model per state and, for the interactive ones, a block entity. It ships every model,
 * texture and translation it uses, so the client only has to be told which model to draw.
 *
 * <p>That is done the way PolyDecorations does it: the real block is replaced on the client with an
 * invisible vanilla state of matching size, and an item display element bound to the block draws the
 * mod's own model for the block's current state. Item displays can use any model id, which is exactly
 * what a furniture mod needs, and the element is updated whenever the block state changes - doors
 * opening, lamps switching on, cabinets opening and so on.
 */
public final class SkniroFurniturePatch implements ModInitializer {
    public static final String MOD_ID = "skniro_furniture_patch";
    public static final String TARGET_MOD_ID = "skniro_furniture";
    public static final Logger LOGGER = LoggerFactory.getLogger("skniro-furniture-patch");

    @Override
    public void onInitialize() {
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.addModAssets(TARGET_MOD_ID);

        // Item display elements render block models as items, which needs item-space copies of the
        // mod's block models. This asks Polymer to generate one for every model in that folder.
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Identifier.fromNamespaceAndPath(TARGET_MOD_ID, "block"));

        FurnitureModels.setup();
        FurnitureBlocks.setup();
        FurnitureItems.setup();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
