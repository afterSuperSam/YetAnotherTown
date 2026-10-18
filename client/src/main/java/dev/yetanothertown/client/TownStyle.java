package dev.yetanothertown.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 统一原版式窗框、凹入面板和保留焦点与旁白的按钮。 */
final class TownStyle {
    static final int BACKGROUND = 0xA6000000;
    static final int SURFACE = 0xFF211C18;
    static final int RAISED = 0xFF332C23;
    static final int LINE = 0xFF554B3E;
    static final int TEXT = 0xFFF0E4D1;
    static final int MUTED = 0xFFAAA091;
    static final int ACCENT = 0xFFD6A45D;
    static final int SUCCESS = 0xFF9BB58B;
    static final int DANGER = 0xFFDF9481;
    static final int FRAME_TEXT = 0xFF302B24;
    static final int FRAME_MUTED = 0xFF5B5346;
    private static final int FRAME = 0xFFB5B0A3;
    record Window(int left, int top, int width, int height) {
        int right() { return left + width; }
        int bottom() { return top + height; }
    }
    private static final int[][] ICONS = {
        {0x18, 0x3C, 0x7E, 0xDB, 0x5A, 0x5A, 0x7E, 0},
        {0x7E, 0x42, 0x5A, 0x5A, 0x42, 0x5E, 0x72, 0},
        {0x7E, 0x42, 0x5A, 0x5A, 0x5A, 0x24, 0x18, 0},
        {0x18, 0x3C, 0x3C, 0x18, 0x7E, 0xFF, 0xFF, 0},
        {0x7E, 0x42, 0x56, 0x42, 0x56, 0x42, 0x7E, 0},
        {0x3C, 0x7E, 0xDB, 0xFF, 0xDB, 0x7E, 0x3C, 0},
        {0x7E, 0x42, 0x5A, 0x42, 0x7E, 0x18, 0x10, 0},
        {0x18, 0x3C, 0x7E, 0x5A, 0x5A, 0x5A, 0x7E, 0},
        {0x63, 0x77, 0x3E, 0x1C, 0x3E, 0x77, 0x63, 0}
    };

    private TownStyle() {}

    static Window bounds(int screenWidth, int screenHeight, int preferredWidth, int preferredHeight) {
        int horizontalMargin = screenWidth >= 480 ? Math.min(48, screenWidth / 12) : 12;
        int verticalMargin = screenHeight >= 270 ? Math.min(36, screenHeight / 12) : 12;
        int width = Math.min(preferredWidth, Math.max(1, screenWidth - horizontalMargin * 2));
        int height = Math.min(preferredHeight, Math.max(1, screenHeight - verticalMargin * 2));
        return new Window((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    static void background(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    static void window(GuiGraphicsExtractor graphics, Window window, int bodyTop, int bodyBottom) {
        int x = window.left(), y = window.top(), right = window.right(), bottom = window.bottom();
        graphics.fill(x + 4, y + 5, right + 4, bottom + 5, 0x66000000);
        graphics.fill(x, y, right, bottom, 0xFF171613);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, 0xFF625D53);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2, FRAME);
        graphics.fill(x + 2, y + 2, right - 2, y + 4, 0xFFE1DCCE);
        graphics.fill(x + 2, y + 4, x + 4, bottom - 4, 0xFFD2CDBF);
        graphics.fill(right - 4, y + 4, right - 2, bottom - 2, 0xFF777166);
        graphics.fill(x + 4, bottom - 4, right - 4, bottom - 2, 0xFF777166);
        graphics.fill(x + 6, bodyTop - 2, right - 6, bodyBottom + 2, 0xFF625D53);
        graphics.fill(x + 7, bodyTop - 1, right - 7, bodyBottom + 1, 0xFF12100E);
        graphics.fill(x + 8, bodyTop, right - 8, bodyBottom, SURFACE);
        graphics.fill(x + 8, bodyBottom + 1, right - 7, bodyBottom + 2, 0xFFD2CDBF);
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean accented) {
        if (width < 3 || height < 3) return;
        graphics.fill(x, y, x + width, y + height, accented ? 0xFF6F5635 : LINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, SURFACE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFF15110F);
        graphics.fill(x + 2, y + height - 3, x + width - 2, y + height - 2, 0xFF191715);
        if (accented) {
            graphics.fill(x + 6, y, x + 22, y + 1, ACCENT);
            graphics.fill(x + width - 22, y + height - 1, x + width - 6, y + height, ACCENT);
        }
    }

    static void icon(GuiGraphicsExtractor graphics, int type, int x, int y, int color) {
        int[] rows = ICONS[Math.floorMod(type, ICONS.length)];
        for (int row = 0; row < rows.length; row++) for (int column = 0; column < 8; column++)
            if ((rows[row] & (1 << (7 - column))) != 0) graphics.fill(x + column, y + row, x + column + 1, y + row + 1, color);
    }

    static Button button(String label, int x, int y, int width, Runnable action, boolean primary) {
        return new TownButton(x, y, Math.max(18, width), label, action, primary, false);
    }

    static Button tab(String label, int x, int y, int width, Runnable action, boolean selected) {
        return new TownButton(x, y, Math.max(18, width), label, action, selected, true);
    }

    private static final class TownButton extends Button {
        private final boolean primary;
        private final boolean tab;
        TownButton(int x, int y, int width, String label, Runnable action, boolean primary, boolean tab) {
            super(x, y, width, 20, Component.literal(label), ignored -> action.run(), DEFAULT_NARRATION);
            this.primary = primary;
            this.tab = tab;
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            boolean highlighted = active && isHoveredOrFocused();
            int edge = !active ? 0xFF49463F : highlighted ? 0xFFF0E4D1 : 0xFF24211C;
            int fill = !active ? 0xFF575249 : tab && primary ? FRAME : highlighted ? 0xFF999080 : primary ? 0xFF8E7650 : 0xFF777064;
            graphics.fill(x, y, x + width, y + height, edge);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, active ? 0xFFBAB2A3 : 0xFF6D665A);
            graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, 0xFF3C372E);
            if (tab && primary) graphics.fill(x + 3, y + height - 3, x + width - 3, y + height - 1, ACCENT);
            if (isFocused()) graphics.outline(x + 2, y + 2, width - 4, height - 4, ACCENT);
            var font = Minecraft.getInstance().font;
            String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(8, width - 12));
            graphics.text(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2, !active ? 0xFFB0A89B : tab && primary ? FRAME_TEXT : TEXT, false);
        }
    }
}
