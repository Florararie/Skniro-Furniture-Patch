package eu.pb4.sknirofurniturepatch.mixin;

import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a player hear the furniture sound they caused.
 *
 * <p>The mod plays its block sounds with the interacting player as the excluded entity, e.g.
 * {@code world.playSound(player, pos, ...)} for a window opening or a plate being filled, which makes
 * the server send the sound to everyone around except that player. That is deliberate on the mod's
 * side: with the mod installed the client runs the same code and plays the sound locally, so passing
 * the player as the exception avoids hearing it twice. A client without the mod never runs that code
 * and the sound is simply lost for them - so here it is sent to them as well.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void skniro$playFurnitureSoundForSource(Entity except, double x, double y, double z,
                                                    Holder<SoundEvent> sound, SoundSource source,
                                                    float volume, float pitch, long seed, CallbackInfo callback) {
        if (!(except instanceof ServerPlayer player)) {
            return;
        }

        var state = player.level().getBlockState(BlockPos.containing(x, y, z));
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
            return;
        }

        player.connection.send(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
    }
}
