package eu.pb4.sknirofurniturepatch.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import eu.pb4.sknirofurniturepatch.impl.ui.KitchenSinkUi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Opens a server side GUI instead of sending a menu the client cannot resolve.
 *
 * <p>The mod's one custom menu is registered as a Polymer menu, so it never reaches the client's
 * registry - and opening it would send an unknown menu id, which the client answers with a network
 * protocol error. The menu is still created normally, which is what we want: its slots already
 * point at the block entity's inventory. The screen is then rebuilt with SGUI and the open screen
 * packet is cancelled.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V", shift = At.Shift.BEFORE), cancellable = true)
    private void openVirtualMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> callback, @Local AbstractContainerMenu menu) {
        var id = BuiltInRegistries.MENU.getKey(menu.getType());
        if (id == null || !id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
            return;
        }

        new KitchenSinkUi((ServerPlayer) (Object) this, menu, provider.getDisplayName());
        callback.setReturnValue(OptionalInt.empty());
    }
}
