package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Puts a Polymer overlay on every block Skniro's Furniture adds.
 *
 * <p>Blocks are found by namespace rather than by referencing the mod's classes, so furniture that
 * ships later is picked up without touching this patch.
 */
public final class FurnitureBlocks {
    private FurnitureBlocks() {
    }

    public static void setup() {
        // Collision stand-ins have to exist before the first block state is mapped to one.
        FurnitureShapes.setup();
        // Which blocks draw their contents has to be known before the first chunk is loaded.
        FurnitureContainerModel.setup();

        int blocks = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            var overlay = new FurnitureBlockOverlay();
            PolymerBlock.registerOverlay(block, overlay);
            BlockWithElementHolder.registerOverlay(block, overlay);
            blocks++;
        }

        // The mod's block entities cannot exist on a client without the mod, and the display
        // elements replace everything their renderers drew.
        int blockEntities = 0;
        for (var type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolymerBlockUtils.registerBlockEntity(type);
            blockEntities++;
        }

        // Everything but the kitchen sink opens a plain vanilla chest menu, so those containers
        // already work for a client without the mod. The sink's own menu cannot be recreated, and
        // it has to be kept out of registry sync or the client would receive an id it cannot map.
        // Its screen is rebuilt as a server side GUI by ServerPlayerMixin.
        int menus = 0;
        for (var type : BuiltInRegistries.MENU) {
            var id = BuiltInRegistries.MENU.getKey(type);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolymerMenuUtils.registerType(type);
            menus++;
        }

        // Chair, sofa and cushion seats are invisible entities that only exist to be ridden.
        int seats = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            registerSeat(type);
            seats++;
        }

        int containers = 0;
        for (var type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)
                    && FurnitureContainerModel.handles(type)) {
                containers++;
            }
        }

        SkniroFurniturePatch.LOGGER.info("Virtualised {} furniture blocks, {} block entity types, {} seats and {} menus.",
                blocks, blockEntities, seats, menus);
        SkniroFurniturePatch.LOGGER.info("Draws the contents of {} furniture container types.", containers);
    }

    private static <T extends Entity> void registerSeat(EntityType<T> type) {
        PolymerEntityUtils.registerOverlay(type, PolyFurnitureSeat::new);
    }
}
