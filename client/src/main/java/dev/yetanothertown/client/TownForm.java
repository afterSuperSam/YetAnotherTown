package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 通用表单负责输入、选择、滚动与明确提交。 */
final class TownForm extends Screen {
    record Field(String key, String label, String initial, String[] values, String[] labels, boolean number) {
        static Field text(String key, String label, String value) { return new Field(key, label, value, null, null, false); }
        static Field number(String key, String label, int value) { return new Field(key, label, Integer.toString(value), null, null, true); }
        static Field choice(String key, String label, String value, String[] values, String[] labels) { return new Field(key, label, value, values, labels, false); }
        static Field toggle(String key, String label, boolean value) { return choice(key, label, Boolean.toString(value), new String[]{"false", "true"}, new String[]{"关闭", "开启"}); }
    }
    record Action(String label, Runnable run) { }
    private final Screen parent;
    private final List<Field> fields;
    private final List<String> description;
    private final List<Action> actions;
    private final Consumer<JsonObject> submit;
    private final String initialStatus;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<TextLine> labels = new ArrayList<>();
    private int scroll;
    private int maximumScroll;
    private String error = "";
    private boolean submitting;
    private record TextLine(String value, int x, int y, int color) { }

    TownForm(Screen parent, String title, List<Field> fields, Consumer<JsonObject> submit) {
        this(parent, title, fields, List.of(), List.of(), submit);
    }

    TownForm(Screen parent, String title, List<Field> fields, List<String> description, List<Action> actions, Consumer<JsonObject> submit) {
        super(Component.literal(title));
        this.parent = parent;
        this.fields = fields;
        this.description = description;
        this.actions = actions;
        this.submit = submit;
        this.initialStatus = TownGateway.get().status;
        fields.forEach(field -> values.put(field.key(), field.initial()));
    }

    @Override
    protected void init() { rebuild(); }

    private void rebuild() {
        clearWidgets();
        labels.clear();
        int left = Math.max(12, (width - 570) / 2);
        int contentWidth = Math.min(570, width - 24);
        int y = 48 - scroll;
        for (String text : description) {
            for (String line : TownScreen.wrapText(text, contentWidth - 8, font)) {
                labels.add(new TextLine(line, left + 4, y, TownScreen.MUTED));
                y += 12;
            }
            y += 4;
        }
        for (Field field : fields) {
            labels.add(new TextLine(field.label(), left + 4, y, TownScreen.TEXT));
            int inputY = y + 13;
            if (inputY >= 45 && inputY + 20 <= height - 54) {
                if (field.values() == null) {
                    EditBox input = new EditBox(font, left + 2, inputY, contentWidth - 4, 20, Component.literal(field.label()));
                    input.setMaxLength(2048);
                    input.setValue(values.get(field.key()));
                    input.setResponder(value -> values.put(field.key(), value));
                    addRenderableWidget(input);
                } else {
                    String current = values.get(field.key());
                    int index = 0;
                    for (int i = 0; i < field.values().length; i++) if (field.values()[i].equals(current)) index = i;
                    final int selected = index;
                    addRenderableWidget(Button.builder(Component.literal(field.labels()[selected]), button -> {
                        values.put(field.key(), field.values()[(selected + 1) % field.values().length]);
                        rebuild();
                    }).bounds(left + 2, inputY, contentWidth - 4, 20).build());
                }
            }
            y += 42;
        }
        for (Action action : actions) {
            if (y >= 45 && y + 20 <= height - 54) addRenderableWidget(Button.builder(Component.literal(action.label()), button -> action.run()).bounds(left + 2, y, contentWidth - 4, 20).build());
            y += 24;
        }
        maximumScroll = Math.max(0, y + scroll - (height - 58));
        int bottom = height - 29;
        int buttonWidth = Math.min(140, (contentWidth - 8) / 2);
        addRenderableWidget(Button.builder(Component.literal(submit == null ? "返回" : "取消"), button -> onClose()).bounds(left, bottom, buttonWidth, 20).build());
        if (submit != null) {
            Button save = addRenderableWidget(Button.builder(Component.literal(submitting ? "正在提交…" : "保存 / 提交"), button -> save()).bounds(left + contentWidth - buttonWidth, bottom, buttonWidth, 20).build());
            save.active = !submitting;
        }
        if (maximumScroll > 0) {
            addRenderableWidget(Button.builder(Component.literal("↑"), button -> moveScroll(-84)).bounds(width - 24, 45, 18, 20).build());
            addRenderableWidget(Button.builder(Component.literal("↓"), button -> moveScroll(84)).bounds(width - 24, height - 76, 18, 20).build());
        }
    }

    private void save() {
        if (submitting) return;
        JsonObject payload = new JsonObject();
        try {
            for (Field field : fields) {
                String value = values.get(field.key()).strip();
                if (field.number()) payload.addProperty(field.key(), Integer.parseInt(value));
                else if (field.values() != null && (value.equals("true") || value.equals("false"))) payload.addProperty(field.key(), Boolean.parseBoolean(value));
                else payload.addProperty(field.key(), value);
            }
            error = "";
            submitting = true;
            rebuild();
            submit.accept(payload);
        } catch (NumberFormatException exception) {
            submitting = false;
            error = "坐标和数量必须填写有效整数";
        } catch (IllegalArgumentException exception) {
            submitting = false;
            error = exception.getMessage();
        } catch (IllegalStateException exception) {
            submitting = false;
            error = exception.getMessage();
        }
        if (!submitting) rebuild();
    }

    static void track(CompletableFuture<?> request) {
        if (!(VersionClient.currentScreen() instanceof TownForm form)) return;
        request.whenComplete((ignored, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            form.submitting = false;
            if (failure != null) form.error = TownGateway.get().status;
            form.rebuild();
        }));
    }

    static JsonArray identifiers(String value) {
        JsonArray result = new JsonArray();
        for (String identifier : value.split("[,，\\s]+")) if (!identifier.isBlank()) result.add(identifier.strip().replace("-", ""));
        return result;
    }

    private void moveScroll(int amount) { scroll = Math.max(0, Math.min(maximumScroll, scroll + amount)); rebuild(); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { moveScroll((int) (-vertical * 36)); return true; }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TownScreen.background(graphics, width, height);
        graphics.text(font, title, 16, 17, TownScreen.TEXT, true);
        graphics.enableScissor(0, 43, width - 26, height - 49);
        for (TextLine label : labels) graphics.text(font, label.value(), label.x(), label.y(), label.color(), false);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String feedback = !TownGateway.get().status.equals(initialStatus) ? TownGateway.get().status : "Tab 切换输入项 · 鼠标滚轮浏览 · Esc 返回";
        String footer = submitting ? "正在提交，请稍候…" : error.isBlank() ? feedback : error;
        graphics.text(font, font.plainSubstrByWidth(footer, width - 28), 14, height - 44, error.isBlank() ? TownScreen.MUTED : 0xFFFF9988, false);
        if (submitting) {
            int phase = TownGateway.get().config().animationEnabled ? (int) (System.nanoTime() / 160_000_000L % 4) : 0;
            for (int index = 0; index < 4; index++) graphics.fill(width - 49 + index * 8, 16, width - 44 + index * 8, 21, index == phase ? TownScreen.ACCENT : 0xFF55624D);
        }
    }
}
