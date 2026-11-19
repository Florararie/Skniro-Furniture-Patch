package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Client-side stand-in for one piece of furniture.
 *
 * <p>The block itself stays real on the server. The client is sent an invisible vanilla state whose
 * collision matches the furniture's own shape - a thin panel for a window, nothing at all for a
 * plate - while a display element attached to the block draws the mod's model. The stand-in states
 * are chosen per block state by {@link FurnitureShapes}.
 */
public final class FurnitureBlockOverlay implements PolymerBlock, BlockWithElementHolder {
    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return FurnitureShapes.standIn(state);
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialState) {
        return new FurnitureBlockModel(initialState);
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        // Only used for the breaking particles, which have to come from a block the client knows.
        return Blocks.OAK_PLANKS.defaultBlockState();
    }
}
