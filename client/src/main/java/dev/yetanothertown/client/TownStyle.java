package dev.yetanothertown.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 统一石板面板、像素边框和保留原版焦点与旁白的按钮。 */
final class TownStyle {
    static final int BACKGROUND = 0xFF171615;
    static final int SURFACE = 0xFF24211E;
    static final int RAISED = 0xFF302B25;
    static final int LINE = 0xFF51483A;
    static final int TEXT = 0xFFF0E4D1;
    static final int MUTED = 0xFFAAA091;
    static final int ACCENT = 0xFFD6A45D;
    static final int SUCCESS = 0xFF9BB58B;
    static final int DANGER = 0xFFDF9481;
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

    static void background(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        for (int row = 0; row < height; row += 32) {
            for (int column = 0; column < width; column += 64) {
                int offset = row % 64 == 0 ? 0 : -32;
                int x = column + offset;
                graphics.fill(x + 1, row + 1, x + 63, row + 31, 0xFF1B1917);
                graphics.fill(x + 2, row + 2, x + 62, row + 3, 0xFF201D19);
                graphics.fill(x + 47, row + 23, x + 50, row + 24, 0xFF242019);
            }
        }
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean accented) {
        if (width < 3 || height < 3) return;
        graphics.fill(x + 2, y + 3, x + width + 2, y + height + 3, 0x88000000);
        graphics.fill(x, y, x + width, y + height, accented ? 0xFF6F5635 : LINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, SURFACE);
        graphics.fill(x + 2, y + 2, x + width - 2, y + 3, accented ? 0xFF3D3325 : 0xFF383129);
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
        return new TownButton(x, y, Math.max(18, width), label, action, primary);
    }

    private static final class TownButton extends Button {
        private final boolean primary;
        TownButton(int x, int y, int width, String label, Runnable action, boolean primary) {
            super(x, y, width, 20, Component.literal(label), ignored -> action.run(), DEFAULT_NARRATION);
            this.primary = primary;
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            boolean highlighted = active && isHoveredOrFocused();
            int edge = !active ? 0xFF37332D : highlighted ? ACCENT : primary ? 0xFF947348 : LINE;
            int fill = !active ? SURFACE : highlighted ? 0xFF4A3B29 : primary ? 0xFF403323 : RAISED;
            graphics.fill(x, y, x + width, y + height, edge);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
            graphics.fill(x + 2, y + 2, x + width - 2, y + 3, highlighted ? 0xFF79603C : 0xFF4A4033);
            graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, 0xFF191613);
            if (isFocused()) graphics.outline(x + 2, y + 2, width - 4, height - 4, ACCENT);
            var font = Minecraft.getInstance().font;
            String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(8, width - 12));
            graphics.text(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2, active ? TEXT : MUTED, false);
        }
    }
}
