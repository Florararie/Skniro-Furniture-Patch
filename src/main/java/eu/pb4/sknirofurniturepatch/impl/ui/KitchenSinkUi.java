package eu.pb4.sknirofurniturepatch.impl.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The kitchen sink's screen, as a plain chest GUI.
 *
 * <p>The mod's screen cannot be opened on a client without the mod, so the menu it creates is
 * replaced with this one. The slots are the mod's real slots - the same {@code Slot} objects its
 * menu added - so the input and result slots keep their rules and the block entity still crafts
 * while the GUI is open.
 *
 * <p>Progress is read through the menu's own {@code getScaledProgress()} so this patch does not need
 * the mod as a compile time dependency; without that method the bar simply stays empty.
 */
public class KitchenSinkUi extends SimpleGui {
    /** Row of the second chest line: the input slot, three progress slots, then the result. */
    private static final int INPUT_SLOT = 10;
    private static final int[] PROGRESS_SLOTS = {12, 13, 14};
    private static final int OUTPUT_SLOT = 16;
    private static final int ARROW_WIDTH = 27;

    private final AbstractContainerMenu menu;
    private final @Nullable Method progressMethod;
    private int shownProgress = -1;

    public KitchenSinkUi(ServerPlayer player, AbstractContainerMenu menu, Component title) {
        super(MenuType.GENERIC_9x2, player, false);
        this.menu = menu;
        this.progressMethod = findProgressMethod(menu);
        this.setTitle(title);

        if (!menu.slots.isEmpty()) {
            this.setSlot(INPUT_SLOT, menu.slots.get(0));
        }
        if (menu.slots.size() > 1) {
            this.setSlot(OUTPUT_SLOT, menu.slots.get(1));
        }

        this.updateProgress();
        this.open();
    }

    @Override
    public void onTick() {
        super.onTick();
        this.updateProgress();
    }

    private void updateProgress() {
        int scaled = 0;
        if (this.progressMethod != null) {
            try {
                scaled = (int) this.progressMethod.invoke(this.menu);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        if (scaled == this.shownProgress) {
            return;
        }

        this.shownProgress = scaled;
        int filled = (int) Math.round(scaled * (double) PROGRESS_SLOTS.length / ARROW_WIDTH);

        for (int i = 0; i < PROGRESS_SLOTS.length; i++) {
            var item = i < filled ? Items.LIME_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE;
            this.setSlot(PROGRESS_SLOTS[i], new GuiElementBuilder(item)
                    .setName(Component.empty())
                    .hideTooltip()
                    .build());
        }
    }

    private static @Nullable Method findProgressMethod(AbstractContainerMenu menu) {
        try {
            return menu.getClass().getMethod("getScaledProgress");
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }
}
