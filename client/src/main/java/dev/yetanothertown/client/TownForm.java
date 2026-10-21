package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
    record Field(String key, String label, String initial, String[] values, String[] labels, boolean number, boolean paired, boolean multiline, boolean icon, Runnable action) {
        static Field text(String key, String label, String value) { return new Field(key, label, value, null, null, false, false, false, false, null); }
        static Field multiline(String key, String label, String value) { return new Field(key, label, value, null, null, false, true, true, false, null); }
        static Field number(String key, String label, int value) { return new Field(key, label, Integer.toString(value), null, null, true, true, false, false, null); }
        static Field choice(String key, String label, String value, String[] values, String[] labels) { return new Field(key, label, value, values, labels, false, true, false, false, null); }
        static Field icon(String key, String label, String value) { return new Field(key, label, value, null, null, false, false, false, true, null); }
        static Field action(String key, String label, Runnable run) { return new Field(key, label, "", null, null, false, true, false, false, run); }
        Field compact() { return new Field(key, label, initial, values, labels, number, true, multiline, icon, action); }
        static Field toggle(String key, String label, boolean value) { return choice(key, label, Boolean.toString(value), new String[]{"false", "true"}, new String[]{TownText.text("yat.ui.off"), TownText.text("yat.ui.on")}); }
    }
    record Action(String label, Runnable run, java.util.function.Supplier<String> liveLabel) {
        Action(String label, Runnable run) { this(label, run, null); }
        @Override public String label() { return liveLabel == null ? label : liveLabel.get(); }
    }
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
    private final Map<Button, Action> liveActions = new LinkedHashMap<>();
    private final List<AbstractWidget> focusOrder = new ArrayList<>();
    private final List<Integer> separators = new ArrayList<>();
    private List<JsonObject> towns = List.of();
    private final List<TownCard> townCards = new ArrayList<>();
    private record TownCard(JsonObject town, int x, int y, int width) { }
    private int scroll;
    private int maximumScroll;
    private int left;
    private int panelWidth;
    private int bodyTop;
    private int bodyBottom;
    private TownStyle.Window window;
    private String error = "";
    private boolean submitting;
    private boolean discardConfirmation;
    private boolean breadcrumbTitle;
    private boolean guardActions;
    private JsonObject project;
    private String projectOwner;
    private int projectHeaderY;
    private int returningFocusIndex = -1;
    private AbstractWidget pendingFocus;
    private record TextLine(String value, int x, int y, int color) { }

    TownForm(Screen parent, String title, List<Field> fields, Consumer<JsonObject> submit) {
        this(parent, title, fields, List.of(), List.of(), submit);
    }

    TownForm(Screen parent, String title, List<Field> fields, List<String> description, List<Action> actions, Consumer<JsonObject> submit) {
        super(Component.literal(title));
        this.parent = parent;
        this.fields = fields;
        this.description = new ArrayList<>(description);
        this.actions = new ArrayList<>(actions);
        this.submit = submit;
        this.initialStatus = TownGateway.get().status;
        this.context = TownGateway.get().context();
        fields.forEach(field -> values.put(field.key(), field.initial()));
    }

    TownForm withBreadcrumbTitle() { breadcrumbTitle = true; return this; }
    TownForm withGuardedActions() { guardActions = true; return this; }
    TownForm withProjectTitle(JsonObject project, String owner) {
        this.project = project;
        projectOwner = owner;
        return this;
    }

    private void runAction(Action action) {
        if (submitting || !validContext()) return;
        if (guardActions && fields.stream().anyMatch(field -> !field.initial().equals(values.get(field.key())))) {
            error = TownText.text("yat.error.unsaved_changes");
            return;
        }
        try { action.run().run(); }
        catch (IllegalArgumentException | IllegalStateException exception) {
            submitting = false;
            error = exception.getMessage();
            rebuild();
        }
    }

    void updateContent(List<String> description, List<Action> actions) {
        this.description.clear(); this.description.addAll(description);
        this.actions.clear(); this.actions.addAll(actions);
        rebuild();
    }

    void showTowns(List<JsonObject> towns) {
        this.towns = List.copyOf(towns);
        description.clear();
        if (towns.isEmpty()) description.add(TownText.text("yat.empty.joinable_towns"));
        rebuild();
    }

    @Override public void tick() {
        liveActions.forEach((button, action) -> {
            String label = action.label();
            if (!button.getMessage().getString().equals(label)) button.setMessage(Component.literal(label));
        });
    }

    @Override
    protected void init() {
        rebuild();
        if (returningFocusIndex >= 0 && returningFocusIndex < focusOrder.size()) focusControl(focusOrder.get(returningFocusIndex));
        returningFocusIndex = -1;
    }

    private void rebuild() {
        int focusedIndex = focusOrder.indexOf(getFocused());
        clearFocus();
        clearWidgets();
        labels.clear();
        contentWidgets.clear();
        liveActions.clear();
        focusOrder.clear();
        separators.clear();
        townCards.clear();
        window = TownStyle.bounds(width, height, 440, 320);
        panelWidth = window.width();
        left = window.left();
        bodyTop = window.top() + (breadcrumbTitle || project != null ? 32 : 48);
        bodyBottom = window.bottom() - 57;
        int contentLeft = left + 16;
        int contentWidth = panelWidth - 32;
        int y = bodyTop + 6 - scroll;
        if (project != null) {
            projectHeaderY = y;
            String status = TownScreen.state(TownScreen.stringValue(project, "status"));
            String name = font.plainSubstrByWidth(title.getString(), Math.max(0, contentWidth - 44 - (status.isEmpty() ? 0 : font.width(status) + 8)));
            labels.add(new TextLine(name, contentLeft + 44, y + 1, TownScreen.projectColor(project)));
            if (!status.isEmpty()) labels.add(new TextLine(status, contentLeft + 44 + font.width(name) + 8, y + 1, TownScreen.projectColor(project)));
            labels.add(new TextLine(font.plainSubstrByWidth(projectOwner, contentWidth - 30), contentLeft + 30, y + 14, TownStyle.MUTED));
            y += 34;
            String purpose = TownScreen.stringValue(project, "purpose");
            if (!purpose.isBlank()) {
                for (String line : TownScreen.wrapText(purpose, contentWidth, font)) {
                    labels.add(new TextLine(line, contentLeft, y, TownStyle.TEXT));
                    y += 13;
                }
                y += 8;
            }
            separators.add(y); y += 10;
        }
        for (String text : description) {
            for (String line : TownScreen.wrapText(text, contentWidth, font)) {
                labels.add(new TextLine(line, contentLeft, y, project != null && text.startsWith(TownText.text("yat.label.repair_needed")) ? TownStyle.DANGER : TownStyle.MUTED));
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
            y += field.multiline() || pair && fields.get(index - 1).multiline() ? multilineHeight() + 20 : 40;
        }
        if (!fields.isEmpty() && !actions.isEmpty()) { separators.add(y - 3); y += 8; }
        for (int index = 0; index < actions.size();) {
            Action action = actions.get(index);
            int halfWidth = (contentWidth - 8) / 2;
            boolean pair = index + 1 < actions.size() && font.width(action.label()) <= halfWidth - 12 && font.width(actions.get(index + 1).label()) <= halfWidth - 12;
            int actionWidth = pair ? halfWidth : contentWidth;
            Button actionButton = TownStyle.button(action.label(), contentLeft, y, actionWidth, () -> runAction(action));
            actionButton.active = !submitting; addContentWidget(actionButton);
            if (action.liveLabel() != null) liveActions.put(actionButton, action);
            if (pair) {
                Action next = actions.get(index + 1);
                Button nextButton = TownStyle.button(next.label(), contentLeft + actionWidth + 8, y, contentWidth - actionWidth - 8, () -> runAction(next));
                nextButton.active = !submitting; addContentWidget(nextButton);
                if (next.liveLabel() != null) liveActions.put(nextButton, next);
            }
            index += pair ? 2 : 1;
            y += 26;
        }
        for (JsonObject town : towns) {
            townCards.add(new TownCard(town, contentLeft, y, contentWidth));
            Button select = TownStyle.button(TownText.text("yat.ui.select"), contentLeft + contentWidth - 52, y + 8, 44, () -> {
                if (submitting || !validContext()) return;
                values.put("townId", TownScreen.stringValue(town, "id"));
                values.put("password", "");
                scroll = 0;
                rebuild();
                focusControl(fieldWidgets.get(TownScreen.bool(town, "passwordEnabled") ? "password" : "townId"));
            });
            select.active = !submitting;
            addContentWidget(select);
            y += 58;
        }
        maximumScroll = Math.max(0, y + scroll - bodyBottom);
        if (scroll > maximumScroll) { scroll = maximumScroll; rebuild(); return; }
        int bottom = window.bottom() - 29;
        int buttonWidth = Math.min(128, (contentWidth - 8) / 2);
        Button cancel = addControl(TownStyle.button(discardConfirmation ? TownText.text("yat.ui.keep_editing") : submit == null ? TownText.text("yat.ui.back") : TownText.text("yat.ui.cancel"), contentLeft, bottom, buttonWidth, this::onClose));
        cancel.active = !submitting;
        if (submit != null) {
            Button save = addControl(TownStyle.button(submitting ? TownText.text("yat.status.submitting") : discardConfirmation ? TownText.text("yat.ui.discard_changes") : TownText.text("yat.ui.confirm_submit"), contentLeft + contentWidth - buttonWidth, bottom, buttonWidth, this::save));
            save.active = !submitting;
        }
        if (focusedIndex >= 0 && focusedIndex < focusOrder.size()) {
            AbstractWidget focused = focusOrder.get(focusedIndex);
            if (focused.active) focusControl(focused);
        }
    }

    private int multilineHeight() { return Math.max(20, Math.min(52, bodyBottom - bodyTop - 24)); }

    private void addField(Field field, int x, int y, int width) {
        if (field.action() == null) labels.add(new TextLine(font.plainSubstrByWidth(field.label(), width), x, y, TownStyle.TEXT));
        if (field.icon()) width -= 38;
        int inputY = y + 12;
        if (field.action() != null) {
            Button button = TownStyle.button(field.label(), x, inputY, width, () -> { if (!submitting && validContext()) field.action().run(); });
            button.active = !submitting;
            fieldWidgets.put(field.key(), button); addContentWidget(button);
        } else if (field.multiline()) {
            MultiLineEditBox input;
            if (fieldWidgets.get(field.key()) instanceof MultiLineEditBox existing) {
                input = existing; input.setWidth(width); input.setHeight(multilineHeight()); input.setX(x); input.setY(inputY);
            } else {
                input = MultiLineEditBox.builder().setX(x).setY(inputY).setTextColor(TownStyle.TEXT).build(font, width, multilineHeight(), Component.literal(field.label()));
                input.setCharacterLimit(2000); input.setValue(values.get(field.key()));
                input.setValueListener(value -> values.put(field.key(), value));
            }
            input.active = !submitting;
            fieldWidgets.put(field.key(), input); addContentWidget(input);
        } else if (field.values() == null) {
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
            int index = choiceIndex(field.values(), values.get(field.key()), 0);
            Button choice = TownStyle.button(choiceLabel(field, index), x, inputY, width, () -> cycleChoice(field, 1));
            choice.setTooltip(choiceTooltip(field, index));
            choice.active = !submitting && field.values().length > 1; fieldWidgets.put(field.key(), choice); addContentWidget(choice);
        }
    }

    static int choiceIndex(String[] options, String selected, int direction) {
        if (options.length == 0) return -1;
        int index = 0;
        for (int option = 0; option < options.length; option++) if (options[option].equals(selected)) index = option;
        return Math.floorMod(index + direction, options.length);
    }

    private String choiceLabel(Field field, int index) {
        if (index < 0) return TownText.text("yat.empty.options");
        return field.labels() != null && index < field.labels().length ? field.labels()[index] : field.values()[index];
    }

    private Tooltip choiceTooltip(Field field, int index) {
        String hint = field.label() + "：" + choiceLabel(field, index);
        if (field.values().length > 1) hint += "（" + (index + 1) + "/" + field.values().length + TownText.text("yat.hint.cycle_options");
        return Tooltip.create(Component.literal(hint));
    }

    private void cycleChoice(Field field, int direction) {
        if (submitting || field.values().length < 2) return;
        int index = choiceIndex(field.values(), values.get(field.key()), direction);
        values.put(field.key(), field.values()[index]);
        AbstractWidget widget = fieldWidgets.get(field.key());
        widget.setMessage(Component.literal(choiceLabel(field, index)));
        widget.setTooltip(choiceTooltip(field, index));
    }

    static int parseNumber(String label, String value) {
        try { return Integer.parseInt(value.strip()); }
        catch (NumberFormatException exception) { throw new NumberFormatException(TownText.text("yat.error.field_integer", label)); }
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
        if (submitting || !discardConfirmation && !validContext()) return;
        JsonObject payload = new JsonObject();
        for (Field field : fields) {
            if (field.action() != null) continue;
            String value = values.get(field.key()).strip();
            if (field.number()) {
                try { payload.addProperty(field.key(), parseNumber(field.label(), value)); }
                catch (NumberFormatException exception) {
                    error = exception.getMessage();
                    pendingFocus = fieldWidgets.get(field.key());
                    return;
                }
            } else if (field.values() != null && (value.equals("true") || value.equals("false"))) payload.addProperty(field.key(), Boolean.parseBoolean(value));
            else payload.addProperty(field.key(), value);
        }
        try {
            error = "";
            submitting = true;
            rebuild();
            submit.accept(payload);
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
        error = TownText.text("yat.error.context_changed");
        return false;
    }

    static void track(CompletableFuture<?> request) {
        Screen origin = VersionClient.currentScreen();
        TownGateway.Context context = TownGateway.get().context();
        if (!(origin instanceof TownForm form)) {
            request.whenComplete((ignored, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (failure != null && VersionClient.currentScreen() == origin && TownGateway.get().isCurrent(context))
                    showError(origin, TownGateway.get().status);
            }));
            return;
        }
        form.submitting = true;
        form.error = "";
        form.rebuild();
        request.whenComplete((ignored, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            form.submitting = false;
            if (form.validContext() && failure != null) form.error = TownGateway.get().status;
            if (VersionClient.currentScreen() == form) form.rebuild();
        }));
    }

    static void showError(Screen parent, String message) {
        VersionClient.show(new TownForm(parent, TownText.text("yat.error.operation_failed"), List.of(), List.of(message), List.of(), null));
    }

    private void moveScroll(int amount) {
        int target = Math.max(0, Math.min(maximumScroll, scroll + amount));
        if (target == scroll) return;
        int offset = scroll - target;
        scroll = target;
        projectHeaderY += offset;
        labels.replaceAll(line -> new TextLine(line.value(), line.x(), line.y() + offset, line.color()));
        separators.replaceAll(y -> y + offset);
        townCards.replaceAll(card -> new TownCard(card.town(), card.x(), card.y() + offset, card.width()));
        for (AbstractWidget widget : contentWidgets) {
            widget.setY(widget.getY() + offset);
            widget.visible = widget.getY() >= bodyTop && widget.getY() + widget.getHeight() <= bodyBottom;
        }
        if (getFocused() instanceof AbstractWidget focused && !focused.visible) clearFocus();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < left + 8 || x > window.right() - 8 || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        for (AbstractWidget widget : contentWidgets) if (widget instanceof MultiLineEditBox && widget.active && widget.visible && widget.isMouseOver(x, y) && widget.mouseScrolled(x, y, horizontal, vertical)) return true;
        int amount = (int) Math.round(-vertical * 36);
        moveScroll(amount == 0 ? (vertical < 0 ? 1 : -1) : amount);
        return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.hasShiftDown()) {
            for (Field field : fields) {
                AbstractWidget widget = fieldWidgets.get(field.key());
                if (field.values() != null && widget.active && widget.visible && widget.isMouseOver(event.x(), event.y())) {
                    cycleChoice(field, -1); focusControl(widget); return true;
                }
            }
        }
        boolean handled = super.mouseClicked(event, doubleClick);
        if (getFocused() != null && !children().contains(getFocused())) clearFocus();
        applyPendingFocus();
        return handled;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (submitting && event.key() == InputConstants.KEY_ESCAPE) return true;
        if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
            for (Field field : fields) if (field.values() != null && fieldWidgets.get(field.key()) == getFocused()) {
                cycleChoice(field, event.key() == InputConstants.KEY_LEFT ? -1 : 1); return true;
            }
        }
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
        boolean handled = super.keyPressed(event);
        applyPendingFocus();
        return handled;
    }

    private void applyPendingFocus() {
        if (pendingFocus == null) return;
        focusControl(pendingFocus);
        pendingFocus = null;
    }
    @Override public void onClose() {
        if (submitting) return;
        if (submit != null && fields.stream().anyMatch(field -> !field.initial().equals(values.get(field.key())))) {
            returningFocusIndex = focusOrder.indexOf(getFocused());
            TownForm confirmation = new TownForm(this, TownText.text("yat.title.discard_unsubmitted"), List.of(), List.of(TownText.text("yat.confirm.discard_unsubmitted")), List.of(), ignored -> VersionClient.show(parent));
            confirmation.discardConfirmation = true;
            VersionClient.show(confirmation);
        } else VersionClient.show(parent);
    }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TownStyle.background(graphics, width, height);
        TownStyle.window(graphics, window, bodyTop, bodyBottom);
        String feedback = !TownGateway.get().status.equals(initialStatus) ? TownGateway.get().status : "";
        String status = submitting ? TownText.text("yat.status.submitting") : error.isBlank() ? feedback : error;
        TownStyle.heading(graphics, font, TownStyle.title() + (breadcrumbTitle ? " · " + title.getString() : ""), left + 16, window.top() + 10, panelWidth - 68);
        if (!breadcrumbTitle && project == null) {
            graphics.text(font, font.plainSubstrByWidth(title.getString(), panelWidth - 32), left + 16, window.top() + 29, TownStyle.FRAME_TEXT, false);
        }
        graphics.enableScissor(left + 12, bodyTop, left + panelWidth - 12, bodyBottom);
        if (project != null) {
            VersionClient.drawAvatar(graphics, TownScreen.stringValue(project, "leaderId"), projectOwner, left + 16, projectHeaderY, 24);
            VersionClient.drawLogo(graphics, ProjectHighlighter.projectIcon(TownScreen.stringValue(project, "type")), left + 46, TownStyle.centeredY(projectHeaderY + 1, font.lineHeight, 12), 12);
        }
        for (TownCard card : townCards) {
            JsonObject town = card.town();
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), 52, false);
            VersionClient.drawLogo(graphics, TownScreen.stringValue(town, "logo", "minecraft:oak_sapling"), card.x() + 8, card.y() + 7, 24);
            graphics.text(font, font.plainSubstrByWidth(TownScreen.stringValue(town, "name"), card.width() - 100), card.x() + 40, card.y() + 7, TownStyle.TEXT, false);
            graphics.text(font, "ID " + TownScreen.stringValue(town, "id"), card.x() + 40, card.y() + 21, TownStyle.ACCENT, false);
            String introduction = TownScreen.singleLine(TownScreen.stringValue(town, "introduction"));
            graphics.text(font, font.plainSubstrByWidth(introduction, card.width() - 16), card.x() + 8, card.y() + 38, TownStyle.MUTED, false);
            if (font.width(introduction) > card.width() - 16 && mouseX >= card.x() && mouseX < card.x() + card.width() && mouseY >= Math.max(bodyTop, card.y() + 34) && mouseY < Math.min(bodyBottom, card.y() + 51))
                graphics.setTooltipForNextFrame(Component.literal(introduction), mouseX, mouseY);
        }
        for (int y : separators) graphics.fill(left + 16, y, left + panelWidth - 16, y + 1, TownStyle.LINE);
        for (TextLine label : labels) graphics.text(font, label.value(), label.x(), label.y(), label.color(), false);
        for (Field field : fields) if (field.icon()) {
            AbstractWidget input = fieldWidgets.get(field.key());
            if (input != null && input.visible) VersionClient.drawLogo(graphics, values.get(field.key()), input.getX() + input.getWidth() + 10, TownStyle.centeredY(input.getY(), input.getHeight(), 24), 24);
        }
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        TownStyle.feedback(graphics, font, status, left + 16, window.bottom() - 46, panelWidth - 32,
                error.isBlank() ? TownStyle.FRAME_MUTED : 0xFF8A3026, mouseX, mouseY);
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
