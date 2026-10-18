package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 地图入口交由 Xaero 原生地图显示地形、框选与工程叠层。 */
public final class TownMapScreen extends Screen {
    private final Screen parent;
    private final Consumer<JsonObject> selected;
    private String information = "正在准备 Xaero 地图…";
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public TownMapScreen(Screen parent) { this(parent, null); }
    public TownMapScreen(Screen parent, Consumer<JsonObject> selected) {
        super(Component.literal(selected == null ? "小镇地图" : "按区块框选建设区域"));
        this.parent = parent;
        this.selected = selected;
    }

    @Override protected void init() {
        panelWidth = Math.min(420, width - 24);
        panelHeight = Math.min(244, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        int buttonWidth = Math.min(250, panelWidth - 40);
        addRenderableWidget(TownStyle.button("重新打开 Xaero 地图", (width - buttonWidth) / 2, panelTop + panelHeight - 59, buttonWidth, this::open, true));
        addRenderableWidget(TownStyle.button("返回小镇", (width - buttonWidth) / 2, panelTop + panelHeight - 33, buttonWidth, this::onClose, false));
    }

    @Override public void tick() {
        if (information.startsWith("正在")) open();
    }

    private void open() {
        information = "打开地图失败；请确认已进入世界，且 World Map 与 Minimap 前置版本正确。";
        boolean opened = XaeroBridge.openMap(parent, selected);
        information = opened ? "已打开 Xaero 地图" : TownGateway.get().status;
    }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TownStyle.background(graphics, width, height);
        TownStyle.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight, true);
        boolean compact = panelHeight < 200;
        if (!compact) {
            int iconX = width / 2 - 17;
            int iconY = panelTop + 21;
            graphics.fill(iconX, iconY + 3, iconX + 11, iconY + 28, TownStyle.LINE);
            graphics.fill(iconX + 12, iconY, iconX + 23, iconY + 25, TownStyle.ACCENT);
            graphics.fill(iconX + 24, iconY + 3, iconX + 35, iconY + 28, TownStyle.LINE);
            graphics.fill(iconX + 3, iconY + 8, iconX + 8, iconY + 9, TownStyle.MUTED);
            graphics.fill(iconX + 27, iconY + 21, iconX + 32, iconY + 22, TownStyle.MUTED);
        }
        int titleY = panelTop + (compact ? 22 : 66);
        graphics.centeredText(font, font.plainSubstrByWidth(title.getString(), panelWidth - 32), width / 2, titleY, TownStyle.TEXT);
        int y = titleY + 23;
        graphics.enableScissor(panelLeft + 16, y, panelLeft + panelWidth - 16, panelTop + panelHeight - 69);
        for (String line : TownScreen.wrapText(information, panelWidth - 40, font)) {
            graphics.centeredText(font, line, width / 2, y, TownStyle.MUTED);
            y += 14;
        }
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
