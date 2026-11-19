package eu.pb4.sknirofurniturepatch.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.impl.BlockExtBlockMapper;
import eu.pb4.polymer.blocks.impl.PolymerBlocksInternal;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The invisible block a client is shown in place of a piece of furniture.
 *
 * <p>A client without the mod cannot be given furniture collision, so the stand-in has to be a
 * vanilla block whose own collision is close to the real shape, with its model hidden in the pack.
 * The stand-in is picked by comparing collision boxes: the shape that covers the real one with the
 * least extra space wins, and states that do not occlude neighbouring faces are preferred. That last
 * part matters - an occluding stand-in culls the face of the block underneath it, and since the
 * stand-in renders nothing the player looks straight through the world: the blue hole that used to
 * appear under beds, tables and cushions.
 *
 * <p>Beds get an ordinary stand-in like everything else. They used to borrow a real bed state so the
 * client could draw a sleeping player, but a real bed does not render from its blockstate in this
 * version - a block entity renderer draws it - so the borrowed state put a whole vanilla bed inside
 * every furniture bed. The lying pose is kept instead by moving the client's sleeping position to a
 * fake bed outside the world, see {@code LivingEntityMixin}.
 *
 * <p>Hiding the models is the fragile half. Polymer blurs every requested state into one rewritten
 * blockstate file per vanilla block, and with several Polymer mods in one pack a later writer can
 * replace that file without knowing about the states used here - the stand-in then renders as a note
 * block or an iron door. So the states are written back into the pack once more, as the last step of
 * the build. On top of that, Polymer's block pool maps every reserved state back to the default state
 * of the vanilla block it came from for clients it does not own the block for; an outer mapper undoes
 * that, so a stand-in can never surface as a visible iron door or player head.
 */
public final class FurnitureShapes {
    private record StandIn(String name, BlockState state, AABB box) {
    }

    private static final Map<BlockState, BlockState> STAND_INS = new IdentityHashMap<>();
    private static final Map<BlockState, String> NAMES = new IdentityHashMap<>();
    private static final List<StandIn> CANDIDATES = new ArrayList<>();
    private static final String EMPTY_MODEL = "polymer:block/empty";

    /** Pieces worth checking by name in the log, one line each. */
    private static final String[] SAMPLES = {"bed", "cushion", "end_table", "window", "tv", "divider",
            "draw", "desk_lamp", "ceiling_lamp", "plate", "chair", "sofa", "counter", "sliding_door",
            "wall_cabinet", "triple_cabinet", "four_grid"};

    private static BlockState none;
    private static BlockState full;
    private static BlockState floorPlate;
    private static BlockState panelLowZ;
    private static BlockState panelHighZ;
    private static BlockState panelLowX;
    private static BlockState panelHighX;
    private static boolean ready;

    private FurnitureShapes() {
    }

    public static void setup() {
        none = Blocks.STRUCTURE_VOID.defaultBlockState();
        full = Blocks.BARRIER.defaultBlockState();

        collectCandidates();
        registerUnmapping();

        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            for (var state : block.getStateDefinition().getPossibleStates()) {
                STAND_INS.put(state, pick(state));
            }
        }

        ready = true;
        logSummary();
        logSamples();

        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(FurnitureShapes::registerHideStep);
    }

    private static void collectCandidates() {
        // A full invisible cube and the empty state first: the fallbacks for anything no other
        // candidate fits.
        add("barrier", full);

        // Traps are the workhorse: a closed one is a 3px floor or ceiling plate, an open one is a
        // 3px vertical panel on one of the four sides, and none of them cull neighbouring faces.
        add("3px floor", floorPlate = reserve(BlockModelType.TRAPDOOR_BOTTOM));
        add("ceiling plate", reserve(BlockModelType.TRAPDOOR_TOP));
        add("panel low z", panelLowZ = reserve(BlockModelType.TRAPDOOR_SOUTH));
        add("panel high z", panelHighZ = reserve(BlockModelType.TRAPDOOR_NORTH));
        add("panel low x", panelLowX = reserve(BlockModelType.TRAPDOOR_EAST));
        add("panel high x", panelHighX = reserve(BlockModelType.TRAPDOOR_WEST));

        // Half blocks, for beds, cushions, end tables and coffee chairs.
        add("half block", reserve(BlockModelType.SLAB_BOTTOM));
        add("upper half block", reserve(BlockModelType.SLAB_TOP));

        // Teddy bears and other small props only need a small footprint; nothing here may be a
        // player head, because a skull the client does not have hidden renders as Steve.
        add("thin post", reserve(BlockModelType.BARS_CENTER));

        // A campfire is a 7px plate that does not cull the block below it, which is what beds,
        // cushions and tables need - a slab of the same height would leave a hole in the ground.
        add("7px plate", reserve(BlockModelType.CAMPFIRE));

        // Wall shelves and scaffolding add shallow and partly open boxes for wall mounted furniture.
        add("shelf north", reserve(BlockModelType.SHELF_NORTH));
        add("shelf east", reserve(BlockModelType.SHELF_EAST));
        add("shelf south", reserve(BlockModelType.SHELF_SOUTH));
        add("shelf west", reserve(BlockModelType.SHELF_WEST));
        add("scaffold top", reserve(BlockModelType.SCAFFOLDING_TOP));
        add("scaffold bottom", reserve(BlockModelType.SCAFFOLDING_BOTTOM));

        // Snow layers cover every height between 2px and a full block in 2px steps, which is what
        // gives cushions and beds a stand-in at their real height without occluding anything.
        for (int layers = 1; layers <= SnowLayerBlock.MAX_HEIGHT; layers++) {
            add("snow " + layers + "px", Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
        }
    }

    /**
     * Polymer's block pool installs a mapper for every client that passes the state through its own
     * remap table: any borrowed vanilla state is replaced with the default state of the block it was
     * borrowed from, so the client would render a real iron door, player head or bed instead of the
     * hidden stand-in. That table cannot be patched reliably - removing entries is undone the next
     * time the pool is filled again - so furniture is resolved here directly and never reaches it.
     */
    private static void registerUnmapping() {
        BlockMapper.DEFAULT_MAPPER_EVENT.register((context, mapper) -> new BlockMapper() {
            @Override
            public BlockState toClientSideState(BlockState state, PacketContext packetContext) {
                if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace()
                        .equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                    return standIn(state);
                }

                return mapper.toClientSideState(state, packetContext);
            }

            @Override
            public String getMapperName() {
                return "skniro_furniture:stand_ins";
            }
        });
    }

    private static BlockState reserve(BlockModelType type) {
        return PolymerBlockResourceUtils.requestEmpty(type);
    }

    private static void add(String name, BlockState state) {
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return;
        }
        CANDIDATES.add(new StandIn(name, state, shape.bounds()));
        NAMES.put(state, name);
    }

    private static void logSummary() {
        Map<BlockState, Integer> counts = new LinkedHashMap<>();
        for (var standIn : STAND_INS.values()) {
            counts.merge(standIn, 1, Integer::sum);
        }

        SkniroFurniturePatch.LOGGER.info("Matched {} furniture states to {} collision stand-ins:",
                STAND_INS.size(), counts.size());
        for (var entry : counts.entrySet()) {
            var state = entry.getKey();
            SkniroFurniturePatch.LOGGER.info("  {} x{}: {} {} ({}{})", NAMES.getOrDefault(state, "custom"), entry.getValue(),
                    describe(state), bounds(state), BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    state.canOcclude() ? ", occludes" : "");
        }
    }

    private static void logSamples() {
        var seen = new java.util.HashSet<String>();
        for (var block : BuiltInRegistries.BLOCK) {
            var path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }
            for (var sample : SAMPLES) {
                if (path.contains(sample) && seen.add(sample)) {
                    logStandIn(sample, block.defaultBlockState());
                    if (sample.equals("window") || sample.equals("sliding_door")) {
                        for (var state : block.getStateDefinition().getPossibleStates()) {
                            if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) {
                                logStandIn(sample + " open", state);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void logStandIn(String label, BlockState state) {
        var standIn = STAND_INS.getOrDefault(state, pick(state));
        SkniroFurniturePatch.LOGGER.info("  {} {} -> {} {} ({}{})", label, bounds(state),
                NAMES.getOrDefault(standIn, "custom"), bounds(standIn),
                BuiltInRegistries.BLOCK.getKey(standIn.getBlock()), standIn.canOcclude() ? ", occludes" : "");
    }

    /**
     * Runs after every other Polymer block model writer, so a state that another writer dropped is
     * put back into its blockstate file here.
     */
    private static void registerHideStep(eu.pb4.polymer.resourcepack.api.ResourcePackBuilder builder) {
        if (builder instanceof DefaultRPBuilder defaultBuilder) {
            defaultBuilder.buildEvent.register(new Consumer<List<String>>() {
                @Override
                public void accept(List<String> credits) {
                    hideStandInStates(defaultBuilder);
                }
            });
        }
    }

    private static void hideStandInStates(DefaultRPBuilder builder) {
        // Barrier and structure void are invisible on their own and are used by real builds.
        Map<Block, List<BlockState>> byBlock = new LinkedHashMap<>();
        for (var state : new LinkedHashSet<>(STAND_INS.values())) {
            if (state.is(Blocks.BARRIER) || state.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            byBlock.computeIfAbsent(state.getBlock(), block -> new ArrayList<>()).add(state);
        }

        int hidden = 0;
        for (var entry : byBlock.entrySet()) {
            var blockId = BuiltInRegistries.BLOCK.getKey(entry.getKey());
            var path = "assets/" + blockId.getNamespace() + "/blockstates/" + blockId.getPath() + ".json";

            JsonObject root;
            try {
                var current = builder.getDataOrSource(path);
                root = current != null
                        ? JsonParser.parseString(new String(current, StandardCharsets.UTF_8)).getAsJsonObject()
                        : new JsonObject();
            } catch (Exception exception) {
                root = new JsonObject();
            }

            JsonObject variants;
            if (root.has("variants") && root.get("variants").isJsonObject()) {
                variants = root.getAsJsonObject("variants");
            } else if (root.has("multipart")) {
                SkniroFurniturePatch.LOGGER.warn("Cannot hide {} states: {} uses multipart", entry.getValue().size(), blockId);
                continue;
            } else {
                variants = new JsonObject();
                root.add("variants", variants);
            }

            for (var state : entry.getValue()) {
                var model = new JsonObject();
                model.addProperty("model", EMPTY_MODEL);
                var models = new JsonArray();
                models.add(model);
                variants.add(PolymerBlocksInternal.generateStateName(state), models);
                hidden++;
            }

            // An empty key matches every state of the block, so it has to be written last: the
            // listed states then claim theirs first and only the rest fall back. Polymer writes
            // beds with it first, which made every hidden bed half render as a full vanilla bed.
            if (variants.has("")) {
                var fallback = variants.get("");
                var ordered = new JsonObject();
                for (var key : variants.keySet()) {
                    if (!key.isEmpty()) {
                        ordered.add(key, variants.get(key));
                    }
                }
                ordered.add("", fallback);
                root.add("variants", ordered);
            }

            builder.addData(path, DefaultRPBuilder.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }

        SkniroFurniturePatch.LOGGER.info("Hid {} collision stand-in states across {} vanilla blockstates",
                hidden, byBlock.size());
    }

    public static BlockState standIn(BlockState state) {
        if (!ready) {
            return Blocks.BARRIER.defaultBlockState();
        }

        var known = STAND_INS.get(state);
        return known != null ? known : pick(state);
    }

    private static BlockState pick(BlockState state) {
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return none;
        }

        var target = shape.bounds();

        // Desk lamps are a small box in the middle of the block with nothing worth keeping solid,
        // and an invisible box you bump into over a lamp is worse than walking through it. Ceiling
        // lamps are left to the matching below: they are a flat plate under the ceiling, which a
        // ceiling trapdoor covers exactly.
        //
        // Plates keep a floor trapdoor instead of being emptied out. A plate is an 8x8 pedestal one
        // pixel tall that holds an item, so an empty stand-in leaves nothing to aim at: the client
        // has no shape to hit and the plate cannot be clicked. The trapdoor gives it a floor plate
        // to reach for and only costs two pixels of collision above the plate itself.
        var path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.contains("desk_lamp") || path.contains("pressure_plate")) {
            return none;
        }
        if (path.contains("plate")) {
            return floorPlate;
        }

        // An open sliding door slides into the neighbouring block, so its shape starts or ends
        // outside its own cube. No vanilla state can stand in for that without putting a wall in
        // the doorway the door was just opened to clear.
        if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)
                && (target.minX < -0.001 || target.minY < -0.001 || target.minZ < -0.001
                || target.maxX > 1.001 || target.maxY > 1.001 || target.maxZ > 1.001)) {
            return none;
        }

        // An open sliding door keeps the unshaped cube of a plain block, which would be an invisible
        // wall in the doorway. Windows are the opposite: their open state is a thin panel on the far
        // side, so it is matched like any other panel below.
        if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)
                && target.maxX - target.minX > 0.99
                && target.maxY - target.minY > 0.99
                && target.maxZ - target.minZ > 0.99) {
            return none;
        }

        var panel = panel(target);
        if (panel != null) {
            return panel;
        }

        StandIn best = null;
        double bestScore = Double.MAX_VALUE;
        for (var candidate : CANDIDATES) {
            var score = score(candidate.box(), target, candidate.state().canOcclude());
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best != null ? best.state() : full;
    }

    /**
     * Windows, dividers, drawers, televisions and wall cabinets are panels: full size in two
     * directions and only a few pixels thick in the third. For those the nearest side wins outright,
     * because a thin panel two pixels off is still where the player reaches for it while a full block
     * would be an invisible wall the thickness of a room divider. Cabinets are up to six pixels deep,
     * so the limit is generous enough to cover them; the stand-in itself is the three pixel trapdoor
     * panel on that side.
     */
    private static @org.jspecify.annotations.Nullable BlockState panel(AABB target) {
        double widthX = (target.maxX - target.minX) * 16;
        double widthY = (target.maxY - target.minY) * 16;
        double widthZ = (target.maxZ - target.minZ) * 16;

        if (widthZ <= 8 && widthX >= 15 && widthY >= 15) {
            return target.minZ + target.maxZ < 1.0 ? panelLowZ : panelHighZ;
        }
        if (widthX <= 8 && widthZ >= 15 && widthY >= 15) {
            return target.minX + target.maxX < 1.0 ? panelLowX : panelHighX;
        }
        return null;
    }

    /**
     * Missing part of the real shape costs the most, because the client would let the player walk
     * into space the server still considers solid. Added space costs less, and an occluding state
     * costs a little: it culls the face of the block it touches, and since the stand-in itself draws
     * nothing the player would look straight through the world.
     */
    private static double score(AABB box, AABB target, boolean occludes) {
        double dx = overlap(box.minX, box.maxX, target.minX, target.maxX);
        double dy = overlap(box.minY, box.maxY, target.minY, target.maxY);
        double dz = overlap(box.minZ, box.maxZ, target.minZ, target.maxZ);
        double shared = dx * dy * dz;

        double targetVolume = volume(target);
        double uncovered = Math.max(0.0, targetVolume - shared) / Math.max(targetVolume, 0.000001);
        double extra = Math.max(0.0, volume(box) - shared);

        return uncovered * 2.0 + extra + (occludes ? 1.5 : 0.0);
    }

    private static double overlap(double minA, double maxA, double minB, double maxB) {
        return Math.max(0.0, Math.min(maxA, maxB) - Math.max(minA, minB));
    }

    private static double volume(AABB box) {
        return Math.max(0.0, box.maxX - box.minX) * Math.max(0.0, box.maxY - box.minY) * Math.max(0.0, box.maxZ - box.minZ);
    }

    private static String describe(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var name = PolymerBlocksInternal.generateStateName(state);
        return name.isEmpty() ? id.toString() : id + "[" + name + "]";
    }

    private static String bounds(BlockState state) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return "none";
        }

        var box = shape.bounds();
        return String.format("[%.2f-%.2f x %.2f-%.2f x %.2f-%.2f]",
                box.minX * 16, box.maxX * 16, box.minY * 16, box.maxY * 16, box.minZ * 16, box.maxZ * 16);
    }
}
