package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Stand-in for the invisible entities the mod spawns so players can sit on chairs, sofas and cushions.
 *
 * <p>They exist only to be ridden, so the client is given an empty block display: it renders nothing,
 * but the seat still exists on the client and the player is drawn sitting on it. Without an overlay an
 * unmodded client would not know the entity type at all, and the rider would be left standing.
 *
 * <p>A block display has no height, and the rider is drawn at the top of the seat entity. The mod's
 * own seat entities are half a block tall, so the display has to be lifted by that much or the player
 * sits on the floor in front of the chair.
 *
 * <p>Cushions are the exception: they are a 3.5px pad rather than a chair height seat, so the same
 * lift leaves the player floating well above one.
 */
public final class PolyFurnitureSeat implements PolymerEntity {
    private static final double SEAT_HEIGHT = 0.5;
    private static final double CUSHION_HEIGHT = 0.25;

    private final Entity entity;
    private boolean lifted;

    public PolyFurnitureSeat(Entity entity) {
        this.entity = entity;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.BLOCK_DISPLAY;
    }

    @Override
    public void onBeforeSpawnPacket(ServerPlayer player, java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> packetConsumer) {
        if (this.lifted) {
            return;
        }

        // Raising the seat once, before the first spawn packet is built, keeps every later position
        // packet and the server side rider position in agreement with what the client is told.
        this.lifted = true;
        var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(this.entity.getType()).getPath();
        var height = type.contains("cushion") ? CUSHION_HEIGHT : SEAT_HEIGHT;
        this.entity.setPos(this.entity.getX(), this.entity.getY() + height, this.entity.getZ());
        this.entity.setDeltaMovement(Vec3.ZERO);
    }
}
