package eu.pb4.sknirofurniturepatch.mixin;

import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swings the player's arm when they place furniture.
 *
 * <p>Placing a block normally animates the hand on the client, which predicts the placement and
 * swings when its own attempt succeeds. A client without the mod holds paper where the mod's item
 * was, and paper has nothing to place, so that prediction never succeeds and the arm stays still -
 * even though the server places the furniture from the very same interaction packet.
 *
 * <p>So the swing has to come from the server instead. {@code swing} is what a vanilla client calls
 * on a placement: it broadcasts the animation to everyone tracking the player and resets their attack
 * strength. It deliberately skips the player themselves, because vanilla expects their client to
 * have animated already, so the animation packet is also sent to their own connection.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void skniro$swingWhenPlacing(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                         BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> callback) {
        var result = callback.getReturnValue();
        if (result == null || !result.consumesAction() || stack.isEmpty()) {
            return;
        }

        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
            return;
        }

        player.swing(hand);
        player.connection.send(new ClientboundAnimatePacket(player, hand == InteractionHand.MAIN_HAND ? 0 : 3));
    }
}
