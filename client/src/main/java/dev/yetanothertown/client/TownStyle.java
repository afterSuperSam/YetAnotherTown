package dev.yetanothertown.client;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 统一深灰窗框、石头面板及保留原生交互与旁白的按钮。 */
final class TownStyle {
    static final int BACKGROUND = 0xA6000000;
    static final int SURFACE = 0xFF292929;
    static final int RAISED = 0xDD252525;
    static final int LINE = 0xFF3A3A3A;
    static final int TEXT = 0xFFF0F0F0;
    static final int MUTED = 0xFFB3B3B3;
    static final int ACCENT = 0xFFD6A45D;
    static final int SUCCESS = 0xFF9BB58B;
    static final int DANGER = 0xFFDF9481;
    static final int FRAME_TEXT = TEXT;
    static final int FRAME_MUTED = MUTED;
    private static final int FRAME = 0xFF202020;
    private static final int FRAME_EDGE = 0xFF484848;
    private static final int FRAME_SHADOW = 0xFF161616;
    private static final int TEXT_DISABLED = 0xFFA0A0A0;
    private static final int BUTTON_FOCUS = 0xFFF3D7AC;
    private static final int PANEL_ACCENT = 0xFF725A39;
    private static final long BUTTON_TRANSITION_NANOS = 150_000_000L;
    private static final long LOADING_DELAY_NANOS = 150_000_000L;
    private static final long LOADING_CYCLE_NANOS = 900_000_000L;
    private static final int LOADING_SHADE = 0xB0000000;
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

    /** 请求立即拦截输入，持续到显示延迟后再绘制加载层。 */
    static final class LoadingState {
        private boolean busy;
        private long startedAt;
        private long now;

        void update(boolean busy, long now) {
            if (busy && !this.busy) startedAt = now;
            this.busy = busy;
            this.now = now;
        }

        boolean blocking() { return busy; }
        boolean visible() { return busy && now - startedAt >= LOADING_DELAY_NANOS; }
        long startedAt() { return startedAt; }
    }

    static void loadingOverlay(GuiGraphicsExtractor graphics, Font font, Window window, long startedAt) {
        graphics.nextStratum();
        graphics.fill(window.left() + 2, window.top() + 2, window.right() - 2, window.bottom() - 2, LOADING_SHADE);
        String text = TownText.text("yat.status.loading");
        int centerX = window.left() + window.width() / 2;
        int centerY = window.top() + window.height() / 2;
        graphics.text(font, text, centerX - font.width(text) / 2, centerY - 17, TEXT, false);
        boolean animated = TownGateway.get().config().animationEnabled;
        double elapsed = (double) (System.nanoTime() - startedAt) / LOADING_CYCLE_NANOS;
        for (int index = 0; index < 3; index++) {
            double phase = elapsed - index / 3.0;
            int jump = animated ? (int) Math.round(Math.max(0, Math.sin(phase * Math.PI * 2)) * 5) : 0;
            int x = centerX - 8 + index * 6;
            graphics.fill(x, centerY + 4 - jump, x + 3, centerY + 14 - jump, ACCENT);
        }
    }

    static int centeredY(int top, int height, int contentHeight) {
        return top + Math.floorDiv(height - contentHeight, 2);
    }

    static String fitText(String text, int width, java.util.function.ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= width) return text;
        String suffix = "…";
        if (measure.applyAsInt(suffix) > width) return "";
        int end = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (measure.applyAsInt(text.substring(0, next) + suffix) > width) break;
            end = next;
        }
        return text.substring(0, end) + suffix;
    }

    static java.util.List<String> nameLines(String name, int width, java.util.function.ToIntFunction<String> measure) {
        var wrapped = ProjectHighlighter.textLines(TownScreen.singleLine(name), Math.max(1, width), measure);
        if (wrapped.size() <= 2) return wrapped;
        return java.util.List.of(wrapped.getFirst(), fitText(String.join(" ", wrapped.subList(1, wrapped.size())), width, measure));
    }

    static void heading(GuiGraphicsExtractor graphics, Font font, String title, int x, int y, int width) {
        icon(graphics, 0, x, centeredY(y, font.lineHeight, 8), FRAME_TEXT);
        graphics.text(font, font.plainSubstrByWidth(title, Math.max(0, width - 13)), x + 13, y, FRAME_TEXT, false);
    }

    static void feedback(GuiGraphicsExtractor graphics, Font font, String status, int x, int y, int width, int statusColor, int mouseX, int mouseY) {
        String feedback = font.plainSubstrByWidth(status.replaceAll("\\s+", " ").strip(), Math.max(0, width));
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
        graphics.fill(x, y, right, bottom, FRAME_SHADOW);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, FRAME_EDGE);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2, FRAME);
        graphics.fill(x + 2, y + 2, right - 2, y + 3, LINE);
        graphics.fill(x + 6, bodyTop - 2, right - 6, bodyBottom + 2, LINE);
        graphics.fill(x + 7, bodyTop - 1, right - 7, bodyBottom + 1, FRAME_SHADOW);
        for (int tileY = bodyTop; tileY < bodyBottom; tileY += 16) for (int tileX = x + 8; tileX < right - 8; tileX += 16) {
            int tileWidth = Math.min(16, right - 8 - tileX), tileHeight = Math.min(16, bodyBottom - tileY);
            graphics.blit(RenderPipelines.GUI_TEXTURED, STONE, tileX, tileY, 0, 0, tileWidth, tileHeight, 16, 16);
        }
        graphics.fill(x + 8, bodyTop, right - 8, bodyBottom, 0xA8000000);
        graphics.fillGradient(x + 8, bodyTop, right - 8, bodyTop + 8, 0x70000000, 0x00000000);
        graphics.fillGradient(x + 8, bodyBottom - 6, right - 8, bodyBottom, 0x00000000, 0x70000000);
        for (int inset = 0; inset < 5; inset++) {
            int shadow = (100 - inset * 20) << 24;
            graphics.fill(x + 8 + inset, bodyTop, x + 9 + inset, bodyBottom, shadow);
            graphics.fill(right - 9 - inset, bodyTop, right - 8 - inset, bodyBottom, shadow);
        }
        graphics.fill(x + 8, bodyBottom + 1, right - 7, bodyBottom + 2, LINE);
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean accented) {
        if (width < 3 || height < 3) return;
        int border = accented ? PANEL_ACCENT : LINE;
        graphics.fill(x, y, x + width, y + 1, border);
        graphics.fill(x, y + height - 1, x + width, y + height, border);
        graphics.fill(x, y + 1, x + 1, y + height - 1, border);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x55202020);
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
        return new TownButton(label, x, y, width, action, false);
    }

    static Button tab(String label, int x, int y, int width, Runnable action, boolean selected) {
        return new TownButton(label, x, y, width, action, selected);
    }

    /** 原版 GUI sprite 由当前资源包提供，并沿用资源的九宫格缩放。 */
    private static final class ButtonSprites {
        static final Identifier NORMAL = Identifier.fromNamespaceAndPath("minecraft", "widget/button");
        static final Identifier HIGHLIGHTED = Identifier.fromNamespaceAndPath("minecraft", "widget/button_highlighted");
        static final Identifier DISABLED = Identifier.fromNamespaceAndPath("minecraft", "widget/button_disabled");
    }

    /** 单像素切角只改变绘制，不改变原生控件的命中区域。 */
    private static void cutout(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x + 1, y, x + width - 1, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** 收藏与卡片复用轻量反馈，不覆盖内容或增加按钮底板。 */
    static final class InteractionHighlight {
        private boolean initialized;
        private boolean highlighted;
        private float start;
        private long changedAt;

        void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hovered, boolean focused) {
            long now = System.nanoTime();
            boolean target = hovered || focused;
            if (!initialized) { initialized = true; highlighted = target; start = target ? 1 : 0; changedAt = now; }
            float elapsed = Math.min(1, (float) (now - changedAt) / BUTTON_TRANSITION_NANOS);
            float progress = start + ((highlighted ? 1 : 0) - start) * elapsed * elapsed * (3 - 2 * elapsed);
            if (target != highlighted) { start = progress; changedAt = now; highlighted = target; }
            if (!TownGateway.get().config().animationEnabled) progress = highlighted ? 1 : 0;
            if (progress > 0) cutout(graphics, x, y, width, height, opacity(0x18FFFFFF, progress));
            if (focused) {
                graphics.fill(x + 1, y, x + width - 1, y + 1, BUTTON_FOCUS);
                graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, BUTTON_FOCUS);
                graphics.fill(x, y + 1, x + 1, y + height - 1, BUTTON_FOCUS);
                graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, BUTTON_FOCUS);
            }
        }
    }

    private static final class TownButton extends Button {
        private final boolean selected;
        private boolean explicitTooltip;
        private String tooltipLabel;

        TownButton(String label, int x, int y, int width, Runnable action, boolean selected) {
            super(x, y, Math.max(18, width), 20, Component.literal(label), ignored -> action.run(), DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
            explicitTooltip = true;
            super.setTooltip(tooltip);
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            Identifier sprite = !active ? ButtonSprites.DISABLED
                    : selected || isHoveredOrFocused() ? ButtonSprites.HIGHLIGHTED : ButtonSprites.NORMAL;
            int x = getX(), y = getY(), width = getWidth(), height = getHeight();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, opacity(0xFFFFFFFF, alpha));
            Font font = Minecraft.getInstance().font;
            String label = getMessage().getString();
            int available = Math.max(0, width - 12);
            String visibleLabel = fitText(label, available, font::width);
            String truncatedLabel = visibleLabel.equals(label) ? null : label;
            if (!explicitTooltip && !java.util.Objects.equals(tooltipLabel, truncatedLabel)) {
                tooltipLabel = truncatedLabel;
                super.setTooltip(truncatedLabel == null ? null : net.minecraft.client.gui.components.Tooltip.create(Component.literal(label)));
            }
            label = visibleLabel;
            graphics.text(font, label, x + (width - font.width(label)) / 2, centeredY(y, height, font.lineHeight),
                    opacity(!active ? TEXT_DISABLED : 0xFFFFFFFF, alpha), true);
        }

    }

    private static int opacity(int color, float alpha) {
        return (color & 0xFFFFFF) | Math.round((color >>> 24) * alpha) << 24;
    }
}
