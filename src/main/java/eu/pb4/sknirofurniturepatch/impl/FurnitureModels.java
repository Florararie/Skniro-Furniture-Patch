package eu.pb4.sknirofurniturepatch.impl;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.BlockStateAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateMultiPartDefinition;
import eu.pb4.sknirofurniturepatch.SkniroFurniturePatch;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Quaternionf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which models a furniture block draws, per block state.
 *
 * <p>The mod's blockstate files are read straight out of its jar instead of being copied into this
 * patch, so nothing has to be regenerated when the mod changes or adds furniture. They are plain
 * {@code variants} maps with an optional y rotation (and four multipart files for the bamboo
 * fences), which keeps the parsing small.
 */
public final class FurnitureModels {
    /** One model to draw, and how far it has to be rotated to match the blockstate. */
    public record Part(Identifier model, Quaternionf rotation) {
        /**
         * Item components are not bound during mod initialisation, so the stack an element draws is
         * created the first time a placed block asks for it rather than while parsing.
         */
        public ItemStack stack() {
            return STACKS.computeIfAbsent(this.model, FurnitureModels::stackFor);
        }
    }

    private static final Map<BlockState, List<Part>> MODELS = new IdentityHashMap<>();
    private static final Map<Identifier, ItemStack> STACKS = new HashMap<>();

    private FurnitureModels() {
    }

    public static void setup() {
        var container = FabricLoader.getInstance().getModContainer(SkniroFurniturePatch.TARGET_MOD_ID).orElse(null);
        if (container == null) {
            SkniroFurniturePatch.LOGGER.error("Skniro's Furniture is missing, so no models can be loaded");
            return;
        }

        int blocks = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }
            if (load(container, block)) {
                blocks++;
            }
        }

        // A state without a model would be furniture that silently renders as nothing, so it is
        // worth saying out loud if a mod update brings blockstates this parser does not understand.
        int missing = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(SkniroFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }
            for (var state : block.getStateDefinition().getPossibleStates()) {
                if (!MODELS.containsKey(state)) {
                    missing++;
                }
            }
        }

        SkniroFurniturePatch.LOGGER.info("Loaded models for {} furniture blocks ({} states, {} without a model).",
                blocks, MODELS.size(), missing);
    }

    public static List<Part> get(BlockState state) {
        return MODELS.getOrDefault(state, List.of());
    }

    private static boolean load(ModContainer container, Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        var path = "assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json";
        var file = container.findPath(path);
        if (file.isEmpty()) {
            return false;
        }

        try (var reader = Files.newBufferedReader(file.get(), StandardCharsets.UTF_8)) {
            var asset = BlockStateAsset.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                    .getOrThrow().getFirst();
            var states = block.getStateDefinition().getPossibleStates();

            asset.variants().ifPresent(variants -> {
                for (var entry : variants.entrySet()) {
                    var predicate = parseKey(block, entry.getKey());
                    if (predicate == null) {
                        continue;
                    }
                    var parts = toParts(entry.getValue());
                    for (var state : states) {
                        if (predicate.test(state)) {
                            MODELS.put(state, parts);
                        }
                    }
                }
            });

            asset.multipart().ifPresent(parts -> {
                for (var part : parts) {
                    var predicate = part.when()
                            .map(condition -> parseCondition(block, condition))
                            .orElse(state -> true);
                    var models = toParts(part.apply());
                    if (models.isEmpty()) {
                        continue;
                    }
                    for (var state : states) {
                        if (predicate.test(state)) {
                            MODELS.computeIfAbsent(state, key -> new ArrayList<>()).addAll(models);
                        }
                    }
                }
            });

            return true;
        } catch (Throwable throwable) {
            SkniroFurniturePatch.LOGGER.warn("Could not read models for {}", id, throwable);
            return false;
        }
    }

    private static List<Part> toParts(List<StateModelVariant> variants) {
        var parts = new ArrayList<Part>(variants.size());
        for (var variant : variants) {
            parts.add(new Part(variant.model(), new Quaternionf()
                    .rotateY(-Mth.DEG_TO_RAD * variant.y())
                    .rotateX(Mth.DEG_TO_RAD * variant.x())));
        }
        return List.copyOf(parts);
    }

    private static ItemStack stackFor(Identifier model) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, ResourcePackExtras.bridgeModel(model));
        return stack;
    }

    /** Turns a {@code "facing=north,half=lower"} variant key into a state predicate. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate<BlockState> parseKey(Block block, String key) {
        var predicate = BlockStatePredicate.forBlock(block);
        if (key.isEmpty()) {
            return predicate;
        }

        for (var pair : key.split(",")) {
            var split = pair.split("=", 2);
            if (split.length != 2) {
                return null;
            }
            var property = (Property) block.getStateDefinition().getProperty(split[0]);
            if (property == null) {
                return null;
            }
            predicate.where(property, value -> property.getName((Comparable) value).equals(split[1]));
        }

        return predicate;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate<BlockState> parseCondition(Block block, StateMultiPartDefinition.Condition condition) {
        if (condition instanceof StateMultiPartDefinition.KeyValueCondition keyValue) {
            Predicate<BlockState> predicate = state -> true;

            for (var test : keyValue.tests().entrySet()) {
                var property = (Property) block.getStateDefinition().getProperty(test.getKey());
                if (property == null) {
                    continue;
                }
                var terms = test.getValue().entries();
                predicate = predicate.and(state -> {
                    var value = property.getName((Comparable) state.getValue(property));
                    for (var term : terms) {
                        if (term.negated() != term.value().equals(value)) {
                            return true;
                        }
                    }
                    return false;
                });
            }

            return predicate;
        }

        if (condition instanceof StateMultiPartDefinition.CombinedCondition combined) {
            var predicates = combined.terms().stream()
                    .map(term -> parseCondition(block, term))
                    .toList();

            return switch (combined.operation()) {
                case AND -> state -> predicates.stream().allMatch(predicate -> predicate.test(state));
                case OR -> state -> predicates.stream().anyMatch(predicate -> predicate.test(state));
            };
        }

        return state -> false;
    }
}
