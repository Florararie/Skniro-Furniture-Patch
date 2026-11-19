package eu.pb4.sknirofurniturepatch.impl.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shows one Skniro's Furniture item to a client that does not have the mod.
 *
 * <p>The client gets paper whose {@code minecraft:item_model} component names the item's own
 * definition from the mod's assets, so the inventory, the hand and item frames all draw the real
 * furniture model and read the real translation key.
 */
public record PolyFurnitureItem(Item item, Identifier model) implements PolymerItem {
    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return this.model;
    }
}
