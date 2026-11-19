package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.impl.InternalServerRegistry;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import eu.pb4.sknirofurniturepatch.impl.item.PolyFurnitureItem;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Makes the mod's items and creative tabs usable without the mod.
 *
 * <p>The tabs are inserted into Polymer's tab registry directly. Its public helper refuses any id
 * the vanilla registry already contains, which a mod's own tab always is, and a tab Polymer does not
 * know is never sent to the client - only its contents are, which the client then drops.
 */
public final class FurnitureItems {
    private FurnitureItems() {
    }

    public static void setup() {
        int items = 0;
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolymerItem.registerOverlay(item, new PolyFurnitureItem(item, id));
            items++;
        }

        int tabs = 0;
        for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            var id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id == null || !id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            InternalServerRegistry.ITEM_GROUPS.set(id, tab);
            SkniroFurniturePatch.LOGGER.info("Serving creative tab {} to Polymer clients", id);
            tabs++;
        }

        SkniroFurniturePatch.LOGGER.info("Virtualised {} items and {} creative tabs.", items, tabs);
    }
}
