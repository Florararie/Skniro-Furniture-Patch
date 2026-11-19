package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one furniture block with item display elements, one per model the blockstate asks for.
 *
 * <p>The block stays a real block on the server; this holder is attached to it, so it moves with
 * pistons and disappears with the block. When the state changes - a door opening, a lamp turning on -
 * Polymer notifies the holder and the models are swapped.
 */
public class FurnitureBlockModel extends ElementHolder {
    private final List<ItemDisplayElement> elements = new ArrayList<>();
    private BlockState state;

    public FurnitureBlockModel(BlockState state) {
        this.state = state;
        this.rebuild();
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);

        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            var current = this.blockState();
            if (current != this.state) {
                this.state = current;
                this.rebuild();
            }
        }
    }

    private BlockState blockState() {
        return this.getAttachment() instanceof BlockAwareAttachment attachment
                ? attachment.getBlockState()
                : Blocks.AIR.defaultBlockState();
    }

    private void rebuild() {
        var parts = FurnitureModels.get(this.state);

        while (this.elements.size() > parts.size()) {
            this.removeElement(this.elements.removeLast());
        }

        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            boolean created = i >= this.elements.size();
            ItemDisplayElement element;

            if (created) {
                element = createElement();
                this.elements.add(element);
            } else {
                element = this.elements.get(i);
            }

            element.setItem(part.stack());
            element.setLeftRotation(part.rotation());

            if (created) {
                this.addElement(element);
            } else {
                element.tick();
            }
        }
    }

    private static ItemDisplayElement createElement() {
        var element = new ItemDisplayElement();
        element.setItemDisplayContext(ItemDisplayContext.NONE);
        element.setViewRange(2f);
        element.setDisplaySize(2, 2);
        element.setTeleportDuration(0);
        // Item models are drawn facing the opposite way to block models, so the display starts
        // rotated and the blockstate's own y rotation is applied on top of it.
        element.setYaw(180);
        return element;
    }
}
