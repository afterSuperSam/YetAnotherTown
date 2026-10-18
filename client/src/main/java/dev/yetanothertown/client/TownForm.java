package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 通用表单负责输入、选择、滚动与明确提交。 */
final class TownForm extends Screen {
    record Field(String key, String label, String initial, String[] values, String[] labels, boolean number, boolean paired) {
        static Field text(String key, String label, String value) { return new Field(key, label, value, null, null, false, false); }
        static Field number(String key, String label, int value) { return new Field(key, label, Integer.toString(value), null, null, true, true); }
        static Field choice(String key, String label, String value, String[] values, String[] labels) { return new Field(key, label, value, values, labels, false, true); }
        Field compact() { return new Field(key, label, initial, values, labels, number, true); }
        static Field toggle(String key, String label, boolean value) { return choice(key, label, Boolean.toString(value), new String[]{"false", "true"}, new String[]{"关闭", "开启"}); }
    }
    record Action(String label, Runnable run) { }
    private final Screen parent;
    private final List<Field> fields;
    private final List<String> description;
    private final List<Action> actions;
    private final Consumer<JsonObject> submit;
    private final String initialStatus;
    private final TownGateway.Context context;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<TextLine> labels = new ArrayList<>();
    private final Map<String, AbstractWidget> fieldWidgets = new LinkedHashMap<>();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<AbstractWidget> focusOrder = new ArrayList<>();
    private final List<Integer> separators = new ArrayList<>();
    private int scroll;
    private int maximumScroll;
    private int left;
    private int panelWidth;
    private int bodyTop;
    private int bodyBottom;
    private TownStyle.Window window;
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
        this.context = TownGateway.get().context();
        fields.forEach(field -> values.put(field.key(), field.initial()));
    }

    @Override
    protected void init() { rebuild(); }

    private void rebuild() {
        int focusedIndex = focusOrder.indexOf(getFocused());
        clearFocus();
        clearWidgets();
        labels.clear();
        contentWidgets.clear();
        focusOrder.clear();
        separators.clear();
        window = TownStyle.bounds(width, height, 440, 320);
        panelWidth = window.width();
        left = window.left();
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 57;
        int contentLeft = left + 16;
        int contentWidth = panelWidth - 32;
        int y = bodyTop + 6 - scroll;
        for (String text : description) {
            for (String line : TownScreen.wrapText(text, contentWidth, font)) {
                labels.add(new TextLine(line, contentLeft, y, TownStyle.MUTED));
                y += 13;
            }
            y += 7;
        }
        if (!description.isEmpty() && (!fields.isEmpty() || !actions.isEmpty())) { separators.add(y); y += 14; }
        for (int index = 0; index < fields.size();) {
            Field field = fields.get(index);
            boolean pair = field.paired() && index + 1 < fields.size() && fields.get(index + 1).paired() && contentWidth >= 240;
            int fieldWidth = pair ? (contentWidth - 8) / 2 : contentWidth;
            addField(field, contentLeft, y, fieldWidth);
            if (pair) addField(fields.get(index + 1), contentLeft + fieldWidth + 8, y, contentWidth - fieldWidth - 8);
            index += pair ? 2 : 1;
            y += 40;
        }
        if (!fields.isEmpty() && !actions.isEmpty()) { separators.add(y - 3); y += 8; }
        for (int index = 0; index < actions.size();) {
            Action action = actions.get(index);
            int halfWidth = (contentWidth - 8) / 2;
            boolean pair = index + 1 < actions.size() && font.width(action.label()) <= halfWidth - 12 && font.width(actions.get(index + 1).label()) <= halfWidth - 12;
            int actionWidth = pair ? halfWidth : contentWidth;
            Button actionButton = TownStyle.button(action.label(), contentLeft, y, actionWidth, () -> { if (validContext()) action.run().run(); });
            actionButton.active = !submitting; addContentWidget(actionButton);
            if (pair) {
                Action next = actions.get(index + 1);
                Button nextButton = TownStyle.button(next.label(), contentLeft + actionWidth + 8, y, contentWidth - actionWidth - 8, () -> { if (validContext()) next.run().run(); });
                nextButton.active = !submitting; addContentWidget(nextButton);
            }
            index += pair ? 2 : 1;
            y += 26;
        }
        maximumScroll = Math.max(0, y + scroll - bodyBottom);
        if (scroll > maximumScroll) { scroll = maximumScroll; rebuild(); return; }
        int bottom = window.bottom() - 29;
        int buttonWidth = Math.min(128, (contentWidth - 8) / 2);
        addControl(TownStyle.button(submit == null ? "返回" : "取消", contentLeft, bottom, buttonWidth, this::onClose));
        if (submit != null) {
            Button save = addControl(TownStyle.button(submitting ? "正在提交…" : "确认提交", contentLeft + contentWidth - buttonWidth, bottom, buttonWidth, this::save));
            save.active = !submitting;
        }
        if (focusedIndex >= 0 && focusedIndex < focusOrder.size()) {
            AbstractWidget focused = focusOrder.get(focusedIndex);
            if (focused.active) focusControl(focused);
        }
    }

    private void addField(Field field, int x, int y, int width) {
        labels.add(new TextLine(font.plainSubstrByWidth(field.label(), width), x, y, TownStyle.TEXT));
        int inputY = y + 12;
        if (field.values() == null) {
            EditBox input;
            if (fieldWidgets.get(field.key()) instanceof EditBox existing) {
                input = existing; input.setWidth(width); input.setX(x); input.setY(inputY);
            } else {
                input = new EditBox(font, x, inputY, width, 20, Component.literal(field.label()));
                input.setMaxLength(2048); input.setValue(values.get(field.key()));
                input.setResponder(value -> values.put(field.key(), value));
            }
            input.setTextColor(TownStyle.TEXT); input.setTextColorUneditable(TownStyle.MUTED);
            input.setTooltip(font.width(field.label()) > width ? Tooltip.create(Component.literal(field.label())) : null);
            input.setEditable(!submitting); input.active = !submitting;
            fieldWidgets.put(field.key(), input); addContentWidget(input);
        } else {
            int index = 0;
            for (int option = 0; option < field.values().length; option++) if (field.values()[option].equals(values.get(field.key()))) index = option;
            Button choice = TownStyle.button(field.labels()[index], x, inputY, width, () -> {
                int selected = 0;
                for (int option = 0; option < field.values().length; option++) if (field.values()[option].equals(values.get(field.key()))) selected = option;
                int next = (selected + 1) % field.values().length;
                values.put(field.key(), field.values()[next]);
                fieldWidgets.get(field.key()).setMessage(Component.literal(field.labels()[next]));
            });
            choice.setTooltip(font.width(field.label()) > width ? Tooltip.create(Component.literal(field.label())) : null);
            choice.active = !submitting; fieldWidgets.put(field.key(), choice); addContentWidget(choice);
        }
    }

    private <T extends AbstractWidget> T addControl(T widget) {
        focusOrder.add(widget);
        return addRenderableWidget(widget);
    }

    private void addContentWidget(AbstractWidget widget) {
        widget.visible = widget.getY() >= bodyTop && widget.getY() + widget.getHeight() <= bodyBottom;
        contentWidgets.add(widget);
        addControl(widget);
    }

    /** 让键盘焦点依次到达全部控件，并把字段标签一起带入视口。 */
    private void focusControl(AbstractWidget widget) {
        if (contentWidgets.contains(widget)) {
            int top = widget.getY() - (fieldWidgets.containsValue(widget) ? 12 : 0);
            if (top < bodyTop + 6) moveScroll(top - bodyTop - 6);
            else if (widget.getY() + widget.getHeight() > bodyBottom - 6) moveScroll(widget.getY() + widget.getHeight() - bodyBottom + 6);
        }
        setInitialFocus(widget);
    }

    private void save() {
        if (submitting || !validContext()) return;
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

    private boolean validContext() {
        if (TownGateway.get().isCurrent(context)) return true;
        error = "身份或小镇/周目已变化，请返回后重新打开";
        return false;
    }

    static void track(CompletableFuture<?> request) {
        if (!(VersionClient.currentScreen() instanceof TownForm form)) return;
        request.whenComplete((ignored, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            form.submitting = false;
            if (failure != null) form.error = TownGateway.get().status;
            if (VersionClient.currentScreen() == form) form.rebuild();
        }));
    }

    private void moveScroll(int amount) {
        int target = Math.max(0, Math.min(maximumScroll, scroll + amount));
        if (target == scroll) return;
        int offset = scroll - target;
        scroll = target;
        labels.replaceAll(line -> new TextLine(line.value(), line.x(), line.y() + offset, line.color()));
        separators.replaceAll(y -> y + offset);
        for (AbstractWidget widget : contentWidgets) {
            widget.setY(widget.getY() + offset);
            widget.visible = widget.getY() >= bodyTop && widget.getY() + widget.getHeight() <= bodyBottom;
        }
        if (getFocused() instanceof AbstractWidget focused && !focused.visible) clearFocus();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < left + 8 || x > window.right() - 8 || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        int amount = (int) Math.round(-vertical * 36);
        moveScroll(amount == 0 ? (vertical < 0 ? 1 : -1) : amount);
        return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        if (getFocused() != null && !children().contains(getFocused())) clearFocus();
        return handled;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isCycleFocus()) {
            int direction = event.hasShiftDown() ? -1 : 1;
            int index = focusOrder.indexOf(getFocused());
            if (index < 0) index = direction > 0 ? -1 : 0;
            for (int count = 0; count < focusOrder.size(); count++) {
                index = Math.floorMod(index + direction, focusOrder.size());
                AbstractWidget widget = focusOrder.get(index);
                if (widget.active) { focusControl(widget); break; }
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            moveScroll((event.key() == InputConstants.KEY_PAGEUP ? -1 : 1) * Math.max(36, bodyBottom - bodyTop - 24));
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TownStyle.background(graphics, width, height);
        TownStyle.window(graphics, window, bodyTop, bodyBottom);
        graphics.text(font, font.plainSubstrByWidth(TownStyle.title(), panelWidth - 68), left + 16, window.top() + 11, TownStyle.FRAME_MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(title.getString(), panelWidth - 32), left + 16, window.top() + 29, TownStyle.FRAME_TEXT, false);
        graphics.enableScissor(left + 12, bodyTop, left + panelWidth - 12, bodyBottom);
        for (int y : separators) graphics.fill(left + 16, y, left + panelWidth - 16, y + 1, TownStyle.LINE);
        for (TextLine label : labels) graphics.text(font, label.value(), label.x(), label.y(), label.color(), false);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String feedback = !TownGateway.get().status.equals(initialStatus) ? TownGateway.get().status : "Tab 切换 · 滚轮 / PageUp / PageDown 浏览";
        String footer = submitting ? "正在提交，请稍候…" : error.isBlank() ? feedback : error;
        graphics.text(font, font.plainSubstrByWidth(footer, panelWidth - 32), left + 16, window.bottom() - 46, error.isBlank() ? TownStyle.FRAME_MUTED : 0xFF8A3026, false);
        if (font.width(footer) > panelWidth - 32 && mouseX >= left + 16 && mouseX < window.right() - 16 && mouseY >= window.bottom() - 48 && mouseY < window.bottom() - 35)
            graphics.setTooltipForNextFrame(Component.literal(footer), mouseX, mouseY);
        if (maximumScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int handleHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maximumScroll));
            int handleY = bodyTop + (trackHeight - handleHeight) * scroll / maximumScroll;
            graphics.fill(left + panelWidth - 12, bodyTop, left + panelWidth - 10, bodyBottom, TownStyle.LINE);
            graphics.fill(left + panelWidth - 13, handleY, left + panelWidth - 9, handleY + handleHeight, TownStyle.ACCENT);
        }
        if (submitting) {
            int phase = TownGateway.get().config().animationEnabled ? (int) (System.nanoTime() / 160_000_000L % 4) : 0;
            for (int index = 0; index < 4; index++) graphics.fill(left + panelWidth - 49 + index * 8, window.top() + 12, left + panelWidth - 44 + index * 8, window.top() + 17, index == phase ? TownStyle.FRAME_TEXT : 0xFF8C8373);
        }
    }
}
