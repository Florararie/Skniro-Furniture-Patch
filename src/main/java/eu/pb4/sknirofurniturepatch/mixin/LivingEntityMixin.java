package eu.pb4.sknirofurniturepatch.mixin;

import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes a furniture bed look like a bed to the client without giving it one.
 *
 * <p>The client works out how a sleeping player lies by looking up the block at the sleeping
 * position. Sending a real bed there is not possible here: a bed with the mod's model on top of it
 * means the vanilla bed renders inside the furniture, and a bed state cannot be hidden because beds
 * are drawn by a block entity renderer rather than by their blockstate.
 *
 * <p>So the bed the client looks at is moved out of the way instead. The real position is kept on the
 * server, and the client is told the player is sleeping at the bottom (or top) of the world, where a
 * bed of the right facing is placed for exactly as long as the player sleeps. Nothing renders there,
 * and the pose is unchanged. This is the same trick PolyDecorations uses for its sleeping bags.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    @Unique
    private BlockPos skniro$realSleepPos;

    public LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @ModifyVariable(method = "setSleepingPos", at = @At("HEAD"), argsOnly = true)
    private BlockPos skniro$fakeSleepPos(BlockPos pos) {
        var state = this.level().getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock bed)
                || !BuiltInRegistries.BLOCK.getKey(bed).getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
            this.skniro$realSleepPos = null;
            return pos;
        }

        this.skniro$realSleepPos = pos;
        var fakePos = this.skniro$fakePos(pos);

        for (var player : ((ServerLevel) this.level()).getChunkSource().chunkMap
                .getPlayersCloseForSpawning(this.chunkPosition())) {
            player.connection.send(new ClientboundBlockUpdatePacket(fakePos,
                    Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, state.getValue(BedBlock.FACING))));
        }

        return fakePos;
    }

    @Inject(method = "getSleepingPos", at = @At("HEAD"), cancellable = true)
    private void skniro$realSleepPos(CallbackInfoReturnable<Optional<BlockPos>> callback) {
        if (this.skniro$realSleepPos != null) {
            callback.setReturnValue(Optional.of(this.skniro$realSleepPos));
        }
    }

    @Inject(method = "clearSleepingPos", at = @At("HEAD"))
    private void skniro$clearFakeBed(CallbackInfo callback) {
        if (this.skniro$realSleepPos == null) {
            return;
        }

        var fakePos = this.skniro$fakePos(this.skniro$realSleepPos);
        for (var player : ((ServerLevel) this.level()).getChunkSource().chunkMap
                .getPlayersCloseForSpawning(this.chunkPosition())) {
            player.connection.send(new ClientboundBlockUpdatePacket(this.level(), fakePos));
        }

        this.skniro$realSleepPos = null;
    }

    @Unique
    private BlockPos skniro$fakePos(BlockPos pos) {
        return pos.atY(pos.getY() > 0 ? this.level().getMinY() : this.level().getMaxY() - 1);
    }
}
