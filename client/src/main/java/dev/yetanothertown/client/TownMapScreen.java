package dev.yetanothertown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 地图入口交由 Xaero 原生地图显示地形、框选与工程叠层。 */
public final class TownMapScreen extends Screen {
    private final Screen parent;
    private String information = "正在准备 Xaero 地图…";
    private TownStyle.Window window;
    private List<String> informationLines = List.of();
    private boolean pendingOpen = true;
    private boolean showIcon;
    private int bodyTop;
    private int bodyBottom;
    private int textTop;
    private int scroll;
    private int maximumScroll;

    public TownMapScreen(Screen parent) {
        super(Component.literal("小镇地图"));
        this.parent = parent;
    }

    @Override protected void init() {
        clearFocus();
        clearWidgets();
        window = TownStyle.bounds(width, height, 380, 270);
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 91;
        layoutInformation();
        int buttonWidth = Math.min(250, window.width() - 40);
        addRenderableWidget(TownStyle.button("前往绑定当前地图", (width - buttonWidth) / 2, window.bottom() - 81, buttonWidth, () -> TownScreen.openMapBinding(this)));
        addRenderableWidget(TownStyle.button("重新打开 Xaero 地图", (width - buttonWidth) / 2, window.bottom() - 55, buttonWidth, this::open));
        addRenderableWidget(TownStyle.button("返回小镇", (width - buttonWidth) / 2, window.bottom() - 29, buttonWidth, this::onClose));
    }

    @Override public void tick() {
        if (pendingOpen) open();
    }

    private void open() {
        pendingOpen = false;
        information = "打开地图失败；请确认已进入世界，且 World Map 与 Minimap 前置版本正确。";
        boolean opened = XaeroBridge.openMap(parent);
        information = opened ? "已打开 Xaero 地图" : TownGateway.get().status;
        scroll = 0;
        layoutInformation();
    }

    /** 长错误信息可完整浏览，短提示保留地图装饰与居中留白。 */
    private void layoutInformation() {
        informationLines = TownScreen.wrapText(information, window.width() - 40, font);
        int textHeight = informationLines.size() * 14;
        showIcon = bodyBottom - bodyTop >= 105 && textHeight + 68 <= bodyBottom - bodyTop;
        textTop = bodyTop + (showIcon ? 54 : 10);
        maximumScroll = Math.max(0, textTop + textHeight + 6 - bodyBottom);
        scroll = Math.max(0, Math.min(scroll, maximumScroll));
    }

    private void moveScroll(int amount) { scroll = Math.max(0, Math.min(maximumScroll, scroll + amount)); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < window.left() + 8 || x > window.right() - 8 || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        int amount = (int) Math.round(-vertical * 28);
        moveScroll(amount == 0 ? (vertical < 0 ? 1 : -1) : amount);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            moveScroll((event.key() == InputConstants.KEY_PAGEUP ? -1 : 1) * Math.max(28, bodyBottom - bodyTop - 20));
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TownStyle.background(graphics, width, height);
        TownStyle.window(graphics, window, bodyTop, bodyBottom);
        graphics.text(font, font.plainSubstrByWidth(TownStyle.title(), window.width() - 32), window.left() + 16, window.top() + 11, TownStyle.FRAME_MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(title.getString(), window.width() - 32), window.left() + 16, window.top() + 29, TownStyle.FRAME_TEXT, false);
        if (showIcon) {
            int iconX = width / 2 - 17;
            int iconY = bodyTop + 12;
            graphics.fill(iconX, iconY + 3, iconX + 11, iconY + 28, TownStyle.LINE);
            graphics.fill(iconX + 12, iconY, iconX + 23, iconY + 25, TownStyle.ACCENT);
            graphics.fill(iconX + 24, iconY + 3, iconX + 35, iconY + 28, TownStyle.LINE);
            graphics.fill(iconX + 3, iconY + 8, iconX + 8, iconY + 9, TownStyle.MUTED);
            graphics.fill(iconX + 27, iconY + 21, iconX + 32, iconY + 22, TownStyle.MUTED);
        }
        int y = textTop - scroll;
        graphics.enableScissor(window.left() + 16, bodyTop, window.right() - 16, bodyBottom - 6);
        for (String line : informationLines) {
            graphics.centeredText(font, line, width / 2, y, TownStyle.MUTED);
            y += 14;
        }
        graphics.disableScissor();
        if (maximumScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int handleHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maximumScroll));
            int handleY = bodyTop + (trackHeight - handleHeight) * scroll / maximumScroll;
            graphics.fill(window.right() - 12, bodyTop, window.right() - 10, bodyBottom, TownStyle.LINE);
            graphics.fill(window.right() - 13, handleY, window.right() - 9, handleY + handleHeight, TownStyle.ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
