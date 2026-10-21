package dev.yetanothertown.client;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 统一原版式窗框、凹入面板和保留焦点与旁白的按钮。 */
final class TownStyle {
    static final int BACKGROUND = 0xA6000000;
    static final int SURFACE = 0xFF414141;
    static final int RAISED = 0xCC252525;
    static final int LINE = 0xFF232323;
    static final int TEXT = 0xFFF0F0F0;
    static final int MUTED = 0xFFB3B3B3;
    static final int ACCENT = 0xFFD6A45D;
    static final int SUCCESS = 0xFF9BB58B;
    static final int DANGER = 0xFFDF9481;
    static final int FRAME_TEXT = 0xFF303030;
    static final int FRAME_MUTED = 0xFF555555;
    private static final int FRAME = 0xFFC6C6C6;
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/advancements/backgrounds/stone.png");
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

    static void heading(GuiGraphicsExtractor graphics, Font font, String title, int x, int y, int width) {
        icon(graphics, 0, x, y, FRAME_TEXT);
        graphics.text(font, font.plainSubstrByWidth(title, Math.max(0, width - 13)), x + 13, y, FRAME_TEXT, false);
    }

    /** 状态区只显示短反馈，悬停保留完整原因。 */
    static String compactStatus(String status) {
        String text = status.replaceAll("\\s+", " ").strip();
        text = switch (text) {
            case "小镇设置没有修改" -> "未作修改";
            case "已连接当前游戏身份" -> "已连接";
            case "已自动匹配小镇当前地图" -> "地图已匹配";
            case "正在使用当前游戏身份连接…" -> "正在连接…";
            case "进入世界后自动读取游戏身份并连接小镇服务" -> "等待进入世界";
            default -> text;
        };
        return text.codePointCount(0, text.length()) <= 8 ? text : text.substring(0, text.offsetByCodePoints(0, 7)) + "…";
    }

    static void feedback(GuiGraphicsExtractor graphics, Font font, String status, int x, int y, int width, int statusColor, int mouseX, int mouseY) {
        String feedback = font.plainSubstrByWidth(compactStatus(status), Math.max(0, width));
        graphics.text(font, feedback, x, y, statusColor, false);
        if (!status.isBlank() && mouseX >= x && mouseX < x + width && mouseY >= y - 3 && mouseY < y + font.lineHeight + 3)
            graphics.setTooltipForNextFrame(Component.literal(status), mouseX, mouseY);
    }

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
        graphics.fill(x, y, right, bottom, 0xFF171717);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, 0xFF626262);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2, FRAME);
        graphics.fill(x + 2, y + 2, right - 2, y + 4, 0xFFF0F0F0);
        graphics.fill(x + 2, y + 4, x + 4, bottom - 4, 0xFFE0E0E0);
        graphics.fill(right - 4, y + 4, right - 2, bottom - 2, 0xFF777777);
        graphics.fill(x + 4, bottom - 4, right - 4, bottom - 2, 0xFF777777);
        graphics.fill(x + 6, bodyTop - 2, right - 6, bodyBottom + 2, 0xFF626262);
        graphics.fill(x + 7, bodyTop - 1, right - 7, bodyBottom + 1, 0xFF121212);
        for (int tileY = bodyTop; tileY < bodyBottom; tileY += 16) for (int tileX = x + 8; tileX < right - 8; tileX += 16) {
            int tileWidth = Math.min(16, right - 8 - tileX), tileHeight = Math.min(16, bodyBottom - tileY);
            graphics.blit(RenderPipelines.GUI_TEXTURED, STONE, tileX, tileY, 0, 0, tileWidth, tileHeight, 16, 16);
        }
        graphics.fill(x + 8, bodyTop, right - 8, bodyBottom, 0x77000000);
        graphics.fillGradient(x + 8, bodyTop, right - 8, bodyTop + 8, 0xB0000000, 0x00000000);
        graphics.fillGradient(x + 8, bodyBottom - 6, right - 8, bodyBottom, 0x00000000, 0x70000000);
        for (int inset = 0; inset < 5; inset++) {
            int shadow = (100 - inset * 20) << 24;
            graphics.fill(x + 8 + inset, bodyTop, x + 9 + inset, bodyBottom, shadow);
            graphics.fill(right - 9 - inset, bodyTop, right - 8 - inset, bodyBottom, shadow);
        }
        graphics.fill(x + 8, bodyBottom + 1, right - 7, bodyBottom + 2, 0xFFE0E0E0);
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean accented) {
        if (width < 3 || height < 3) return;
        int border = accented ? 0xFF6F5635 : LINE;
        graphics.fill(x, y, x + width, y + 1, border);
        graphics.fill(x, y + height - 1, x + width, y + height, border);
        graphics.fill(x, y + 1, x + 1, y + height - 1, border);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x44000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x55151515);
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

    static String title() {
        String name = TownScreen.stringValue(TownScreen.objectValue(TownGateway.get().snapshot, "town"), "name");
        return "Yet Another Town" + (name.isBlank() ? "" : " · " + name);
    }

    static Button button(String label, int x, int y, int width, Runnable action) {
        return Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, Math.max(18, width), 20).build();
    }

    static Button tab(String label, int x, int y, int width, Runnable action, boolean selected) {
        Button tab = button(label, x, y, width, action);
        if (selected) tab.setMessage(Component.literal(label).withStyle(net.minecraft.ChatFormatting.YELLOW));
        return tab;
    }
}
