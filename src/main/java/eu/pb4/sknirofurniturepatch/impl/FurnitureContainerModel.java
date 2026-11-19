package eu.pb4.sknirofurniturepatch.impl;

import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A furniture block that also draws the items it holds.
 *
 * <p>The mod renders those with block entity renderers: a plate shows whatever is on it, a sink shows
 * its left and right slots, an oven shows what is inside and the cabinets show every slot. None of
 * that exists on a client without the mod, and its block entities cannot either, so the items are
 * drawn with item display elements bound to the same block as the furniture model, refreshed every
 * tick from the real block entity's inventory.
 *
 * <p>Positions and rotations are copied from the mod's renderers, converted from their block corner
 * frame into this holder's frame, which sits at the block centre. A renderer placement
 * {@code translate(pos) … mulPose(yaw)} rotates about the block centre, so a slot lands at
 * {@code rotate(centre -> pos)} and the same yaw becomes the display's own rotation. Item scale is
 * uniform, which lets the renderer's scale-then-translate order fold into one translation.
 *
 * <p>Nothing here may ask the level for a block entity while the holder is being created. Polymer
 * builds holders from the chunk constructor, and {@code Level#getBlockEntity} waits for its chunk to
 * finish loading - the very chunk that is being built, so the server never gets past it. That is why
 * the kind of item holder a block is comes from the registries instead of from its block entity, and
 * why the inventory is only read through {@link #blockEntity()}, which never waits.
 */
public final class FurnitureContainerModel extends FurnitureBlockModel {
    private enum Kind { NONE, PLATE, SINK, OVEN, TRIPLE_CABINET, FOUR_GRID_CABINET }

    /** One displayed item: where it sits in the holder, how it is turned and how big it is. */
    private record Slot(Vector3fc position, Quaternionfc rotation, float scale, ItemDisplayContext context) {
    }

    /** Which kind of contents each of the mod's blocks holds, resolved once at startup. */
    private static final Map<Block, Kind> KINDS = new IdentityHashMap<>();

    private static final String PLATE = "plate_block_entity";
    private static final String SINK = "kitchen_sink_block_entity";
    private static final String OVEN = "oven_block_entity";
    private static final String CABINET = "cabinet_block_entity";

    private final ServerLevel level;
    private final BlockPos origin;
    private final Kind kind;
    private final List<ItemDisplayElement> items = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<ItemStack> shown = new ArrayList<>();
    private BlockState contentsState;

    public FurnitureContainerModel(ServerLevel level, BlockPos pos, BlockState state) {
        super(state);
        this.level = level;
        this.origin = pos;
        this.kind = kindOf(state);
        this.contentsState = state;
        if (this.kind != Kind.NONE) {
            // The elements are made from the block state alone; the items they draw are filled in by
            // the first tick, once the block entity of a freshly loaded chunk exists.
            this.layout(state);
        }
    }

    /**
     * Works out which blocks hold which kind of contents. Blocks of the mod are paired with their
     * block entity types here, while no chunk is loading, so that a placed block can be classified
     * later without touching the level.
     */
    public static void setup() {
        for (var type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (id == null || !id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID) || !handles(type)) {
                continue;
            }

            for (var block : BuiltInRegistries.BLOCK) {
                var blockId = BuiltInRegistries.BLOCK.getKey(block);
                if (blockId.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)
                        && type.isValid(block.defaultBlockState())) {
                    KINDS.put(block, kindOf(type, blockId.getPath()));
                }
            }
        }

        SkniroFurniturePatch.LOGGER.info("Draws the contents of {} furniture blocks.", KINDS.size());
    }

    /** Whether contents of blocks with this block entity type are shown at all. */
    public static boolean handles(BlockEntityType<?> type) {
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (id == null) {
            return false;
        }

        return switch (id.getPath()) {
            case PLATE, SINK, OVEN, CABINET -> true;
            default -> false;
        };
    }

    /** Whether this block state holds anything worth drawing, decided without looking at the world. */
    public static boolean holdsItems(BlockState state) {
        return kindOf(state) != Kind.NONE;
    }

    private static Kind kindOf(BlockState state) {
        return KINDS.getOrDefault(state.getBlock(), Kind.NONE);
    }

    private static Kind kindOf(BlockEntityType<?> type, String blockPath) {
        return switch (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath()) {
            case PLATE -> Kind.PLATE;
            case SINK -> Kind.SINK;
            case OVEN -> Kind.OVEN;
            // Both cabinet kinds share one block entity type; only the block tells them apart.
            default -> blockPath.contains("four_grid") ? Kind.FOUR_GRID_CABINET : Kind.TRIPLE_CABINET;
        };
    }

    @Override
    public void notifyUpdate(eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);

        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            var current = this.blockState();
            if (current != this.contentsState) {
                this.contentsState = current;
                this.layout(current);
            }
        }
    }

    @Override
    protected void onTick() {
        if (this.kind != Kind.NONE) {
            this.refreshContents();
        }
    }

    private void refreshContents() {
        if (!(this.blockEntity() instanceof Container container)) {
            return;
        }

        for (int i = 0; i < this.items.size(); i++) {
            this.update(i, this.itemAt(container, i));
        }
    }

    /**
     * The block entity, or null while its chunk is not loaded.
     *
     * <p>The loaded chunk is asked for directly: {@code Level#getBlockEntity} would load it, which
     * blocks on the server thread and deadlocks when it is called from that chunk's own
     * constructor. This lookup only ever returns something that is already there.
     */
    private @Nullable BlockEntity blockEntity() {
        var pos = this.position();
        if (!(this.level.getChunkSource() instanceof ServerChunkCache chunks)) {
            return null;
        }

        var chunk = chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private ItemStack itemAt(Container container, int slot) {
        return switch (this.kind) {
            case PLATE -> container.getItem(0);
            // The sink's own renderer shows one slot; both are drawn here so the item and the
            // filled bucket are visible side by side, in the order the screen lists them.
            case SINK -> slot < container.getContainerSize() ? container.getItem(slot) : ItemStack.EMPTY;
            // The oven shows its input while cooking and the finished item when it is idle.
            case OVEN -> container.getItem(0).isEmpty() ? container.getItem(2) : container.getItem(0);
            case TRIPLE_CABINET, FOUR_GRID_CABINET ->
                    slot < container.getContainerSize() ? container.getItem(slot) : ItemStack.EMPTY;
            case NONE -> ItemStack.EMPTY;
        };
    }

    private void update(int index, ItemStack stack) {
        if (ItemStack.matches(this.shown.get(index), stack)) {
            return;
        }

        var copy = stack.copy();
        this.shown.set(index, copy);
        this.items.get(index).setItem(copy);
    }

    private BlockPos position() {
        return this.getAttachment() instanceof BlockAwareAttachment attachment
                ? attachment.getBlockPos()
                : this.origin;
    }

    private void layout(BlockState state) {
        var facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;

        this.slots.clear();
        switch (this.kind) {
            case PLATE -> this.slots.add(plate(facing));
            // Looking at the sink, the input bucket sits on the left and the filled one on the right.
            case SINK -> {
                this.slots.add(this.sink(facing, 1));
                this.slots.add(this.sink(facing, -1));
            }
            case OVEN -> this.slots.add(oven(facing));
            // Triple cabinets hold nine items in three rows of three, four grid cabinets four in two
            // rows of two; the mod's own placement, offsets included.
            case TRIPLE_CABINET -> this.cabinet(facing, 3, 3, 0.46f, 0.80f, 0.70f, 0.26f, 1.19f);
            case FOUR_GRID_CABINET -> this.cabinet(facing, 2, 2, 0.68f, 0.70f, 0.70f, 0.40f, 1.05f);
            case NONE -> {
            }
        }

        while (this.items.size() > this.slots.size()) {
            this.removeElement(this.items.removeLast());
            this.shown.removeLast();
        }

        for (int i = 0; i < this.slots.size(); i++) {
            var slot = this.slots.get(i);
            ItemDisplayElement element;
            boolean created = i >= this.items.size();

            if (created) {
                element = new ItemDisplayElement();
                element.setViewRange(4f);
                element.setDisplaySize(2, 2);
                element.setTeleportDuration(0);
                element.setItemDisplayContext(slot.context());
                this.items.add(element);
                this.shown.add(ItemStack.EMPTY);
            } else {
                element = this.items.get(i);
            }

            element.setTranslation(slot.position());
            element.setLeftRotation(slot.rotation());
            element.setScale(new Vector3f(slot.scale()));

            if (created) {
                this.addElement(element);
            } else {
                element.tick();
            }
        }
    }

    /** An item laid flat on the plate, half a pixel above its surface. */
    private static Slot plate(Direction facing) {
        var rotation = new Quaternionf().rotateX(Mth.HALF_PI);
        switch (facing) {
            case SOUTH -> rotation.mul(new Quaternionf().rotateY(Mth.PI));
            case WEST -> rotation.mul(new Quaternionf().rotateZ(Mth.HALF_PI));
            case EAST -> rotation.mul(new Quaternionf().rotateZ(Mth.PI));
            default -> {
            }
        }

        return new Slot(new Vector3f(0, 0.01f - 0.5f, 0), rotation, 0.35f, ItemDisplayContext.GUI);
    }

    /**
     * An item standing on the sink top, either side of the tap. The mod draws a single stack in the
     * middle; the two screen slots are spread apart from that same point so both stay on the basin,
     * the input on the left and the filled bucket on the right as seen by a player in front of it.
     * The basin opening is twelve pixels wide, so the pair is drawn slightly smaller and further
     * apart than the mod's single stack: at that one item's size the two icons overlap and read as a
     * single pile in the middle instead of a bucket on each side.
     */
    private static Slot sink(Direction facing, int side) {
        float x = 0.5f;
        float z = 0.65f;
        float yaw = 0;

        switch (facing) {
            case SOUTH -> {
                z = 0.35f;
                yaw = 180;
            }
            case WEST -> {
                x = 0.65f;
                yaw = 90;
            }
            case EAST -> {
                x = 0.35f;
                yaw = 270;
            }
            default -> {
            }
        }

        var rotation = new Quaternionf().rotateY(yaw * Mth.DEG_TO_RAD);
        var spread = new Vector3f(side * 0.19f, 0, 0).rotate(rotation);
        var position = new Vector3f(x - 0.5f, 1.0f - 0.5f, z - 0.5f).add(spread);
        return new Slot(position, rotation, 0.4f, ItemDisplayContext.GUI);
    }

    /** An item behind the oven door, on the shelf the mod puts it on. */
    private static Slot oven(Direction facing) {
        float x = 0.5f;
        float z = 0.15f;
        float yaw = 0;

        switch (facing) {
            case SOUTH -> {
                z = 0.85f;
                yaw = 180;
            }
            case WEST -> {
                x = 0.15f;
                z = 0.5f;
                yaw = 90;
            }
            case EAST -> {
                x = 0.85f;
                z = 0.5f;
                yaw = -90;
            }
            default -> {
            }
        }

        var rotation = new Quaternionf().rotateY(yaw * Mth.DEG_TO_RAD);
        // The renderer scales by 0.4 and then shifts by (0, 0.3, 0.3) in that scaled frame.
        var shelf = new Vector3f(0, 0.12f, 0.12f).rotate(rotation);
        var position = new Vector3f(x - 0.5f, 0.4f - 0.5f, z - 0.5f).add(shelf);
        return new Slot(position, rotation, 0.4f, ItemDisplayContext.FIXED);
    }

    private void cabinet(Direction facing, int rows, int columns,
                         float x, float y, float z, float scale, float spacing) {
        var rotation = new Quaternionf().rotateY(yaw(facing) * Mth.DEG_TO_RAD);
        var centre = new Vector3f(0.5f);

        for (int i = 0; i < rows * columns; i++) {
            int row = i / columns;
            float column = (i % columns) * 1.1f;

            // The renderer scales the whole grid and then walks it in that scaled frame.
            var point = new Vector3f(x, y, z).add(
                    scale * (column - 1.0f) * spacing,
                    -scale * (row * 1.2f),
                    0);

            this.slots.add(new Slot(point.sub(centre, new Vector3f()).rotate(rotation),
                    rotation, scale, ItemDisplayContext.FIXED));
        }
    }

    private static float yaw(Direction facing) {
        return switch (facing) {
            case SOUTH -> 180;
            case WEST -> 90;
            case EAST -> 270;
            default -> 0;
        };
    }
}
