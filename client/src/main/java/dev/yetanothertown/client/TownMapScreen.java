package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 地图入口交由 Xaero 原生地图显示地形、框选与工程叠层。 */
public final class TownMapScreen extends Screen {
    private final Screen parent;
    private final Consumer<JsonObject> selected;
    private String information = "正在准备 Xaero 地图…";

    public TownMapScreen(Screen parent) { this(parent, null); }
    public TownMapScreen(Screen parent, Consumer<JsonObject> selected) {
        super(Component.literal(selected == null ? "小镇地图" : "按区块框选建设区域"));
        this.parent = parent;
        this.selected = selected;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(240, width - 32);
        addRenderableWidget(Button.builder(Component.literal("打开 Xaero 小镇地图"), button -> open()).bounds((width - buttonWidth) / 2, height / 2 + 10, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose()).bounds((width - buttonWidth) / 2, height / 2 + 37, buttonWidth, 20).build());
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
        TownScreen.background(graphics, width, height);
        graphics.text(font, title, 16, 17, TownScreen.TEXT, true);
        int y = Math.max(55, height / 2 - 42);
        for (String line : TownScreen.wrapText(information, width - 48, font)) { graphics.centeredText(font, line, width / 2, y, TownScreen.MUTED); y += 14; }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
