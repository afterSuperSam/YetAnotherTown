package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static dev.yetanothertown.client.TownForm.Field.*;
import static dev.yetanothertown.client.TownGateway.object;

/** 小镇三入口与所有协作、审核、管理操作。 */
public final class TownScreen extends Screen {
    static final int TEXT = TownStyle.TEXT;
    static final int MUTED = TownStyle.MUTED;
    static final int ACCENT = TownStyle.ACCENT;
    private static final String[] MANAGEMENT = {"成员审核", "权限组", "世界与周目", "小镇设置"};
    private final TownGateway gateway = TownGateway.get();
    private final Screen parent;
    private Screen returnScreen;
    private boolean externalParent;
    private final long openedAt = System.nanoTime();
    private final List<Line> lines = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Button> homeButtons = new ArrayList<>();
    private int avatarX, avatarY, logoX, logoY;
    private boolean showLogo;
    private final Map<String, EditBox> townInputs = new LinkedHashMap<>();
    private String settingsTownId = "";
    private int settingsLogoX, settingsLogoY;
    private boolean showSettingsLogo;
    private boolean settingsSubmitting;
    private int settingsScroll;
    private int tab;
    private int section;
    private int page;
    private EditBox searchInput;
    private boolean searchChanged;
    private boolean pendingSearchFocus;
    private TownGateway.Context listContext;
    private JsonObject previousSnapshot;
    private JsonArray previousTowns;
    private int contentLeft;
    private int contentWidth;
    private int contentTop;
    private int contentBottom;
    private int bodyTop;
    private int bodyBottom;
    private TownStyle.Window window;
    private int homeScroll;
    private int maximumHomeScroll;
    private boolean homeContent;
    private record Panel(int x, int y, int width, int height, boolean accented) { }
    private record Line(String text, int x, int y, int color) { }
    private record Card(int x, int y, int width, int height, String title, String subtitle, String state, boolean repair, JsonObject record) { }

    public TownScreen() { this(null); }
    public TownScreen(Screen parent) { super(Component.literal("Yet Another Town · 小镇协作")); this.parent = parent; }

    public static void createProject(Screen parent, JsonObject chunks) {
        TownScreen controller = new TownScreen(parent);
        controller.returnScreen = parent;
        controller.externalParent = true;
        controller.projectForm(new JsonObject(), chunks);
    }
    public static void openProject(Screen parent, JsonObject project) {
        TownScreen controller = new TownScreen(parent);
        controller.returnScreen = parent;
        controller.externalParent = true;
        controller.showRecord("projects", project);
    }
    public static void openMapBinding(Screen parent) {
        TownScreen controller = new TownScreen(parent);
        controller.returnScreen = parent; controller.externalParent = true;
        for (JsonElement element : controller.array("worlds")) {
            JsonObject world = element.getAsJsonObject();
            if (stringValue(world, "id").equals(controller.gateway.worldId)) {
                if (bool(world, "archived")) controller.bindWorldMap(world);
                else if (!XaeroBridge.associateCurrentMap(parent)) VersionClient.show(new TownForm(parent, "关联当前地图", List.of(),
                        List.of(controller.gateway.status), List.of(controller.action("重试关联", () -> openMapBinding(parent))), null));
                return;
            }
        }
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : controller.array("worlds")) {
            JsonObject world = element.getAsJsonObject();
            if (bool(world, "archived")) continue;
            actions.add(controller.action(stringValue(world, "name"), () -> {
                controller.gateway.selectWorld(stringValue(world, "id"));
                TownGateway.Context context = controller.gateway.context();
                controller.gateway.refresh().whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
                    if (!controller.gateway.isCurrent(context)) return;
                    if (failure == null) openMapBinding(parent);
                    else VersionClient.show(new TownForm(parent, "加载周目失败", List.of(), List.of(controller.gateway.status), List.of(), null));
                }));
            }));
        }
        if (controller.has("worlds.manage")) actions.add(controller.action("登记新世界 / 周目", controller::worldForm));
        VersionClient.show(new TownForm(parent, "这张地图属于哪个周目", List.of(), List.of(actions.isEmpty() ? "请先联系小镇管理员登记世界与周目。" : "选择一次即可关联当前地图。"), actions, null));
    }
    private Screen destination() { return externalParent ? returnScreen : this; }

    @Override protected void init() { rebuild(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public void tick() {
        if (searchChanged || previousSnapshot != gateway.snapshot || previousTowns != gateway.towns) rebuild();
    }

    private void rebuild() {
        if (!gateway.context().equals(listContext)) { listContext = gateway.context(); searchInput = null; page = 0; }
        String focusedSetting = townInputs.entrySet().stream().filter(entry -> entry.getValue() == getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        boolean searchFocused = searchInput != null && (pendingSearchFocus || getFocused() == searchInput);
        pendingSearchFocus = false;
        searchChanged = false;
        Button focused = getFocused() instanceof Button button ? button : null;
        clearFocus();
        showSettingsLogo = false;
        clearWidgets(); lines.clear(); cards.clear(); panels.clear(); homeButtons.clear();
        previousSnapshot = gateway.snapshot; previousTowns = gateway.towns;
        window = TownStyle.bounds(width, height, 560, 346);
        contentLeft = window.left() + 12;
        contentWidth = window.width() - 24;
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 34;
        contentBottom = bodyBottom - 6;
        int navigationWidth = Math.min(86, (contentWidth - 8) / 3);
        String[] tabs = {"主页", "平台", "管理"};
        for (int index = 0; index < tabs.length; index++) {
            final int selected = index;
            Button navigation = addRenderableWidget(TownStyle.tab(tabs[index], contentLeft - 5 + index * (navigationWidth + 4), window.top() + 25, navigationWidth,
                    () -> { tab = selected; section = 0; page = 0; searchInput = null; rebuild(); }, tab == index));
            navigation.active = index == 0 || gateway.loggedIn() && !gateway.townId.isEmpty();
        }
        button("×", window.right() - 28, window.top() + 5, 20, this::onClose);
        contentTop = bodyTop + 6;
        if (tab == 0) {
            homeContent = true; home(); homeContent = false;
            if (homeScroll > maximumHomeScroll) { homeScroll = maximumHomeScroll; rebuild(); return; }
        }
        else if (tab == 1) platform();
        else {
            String[] sections = MANAGEMENT;
            int sectionWidth = (contentWidth - (sections.length - 1) * 4) / sections.length;
            if (contentWidth < 420 && tab != 1) {
                button("‹", contentLeft, contentTop, 22, () -> { section = (section + sections.length - 1) % sections.length; page = 0; rebuild(); });
                String sectionTitle = sections[section] + "  " + (section + 1) + "/" + sections.length;
                label(sectionTitle, contentLeft + (contentWidth - font.width(sectionTitle)) / 2, contentTop + 6, ACCENT);
                button("›", contentLeft + contentWidth - 22, contentTop, 22, () -> { section = (section + 1) % sections.length; page = 0; rebuild(); });
            } else for (int index = 0; index < sections.length; index++) {
                final int selected = index;
                int offset = index * (sectionWidth + 4);
                addRenderableWidget(TownStyle.tab(sections[index], contentLeft + offset, contentTop, index == sections.length - 1 ? contentWidth - offset : sectionWidth,
                        () -> { section = selected; page = 0; searchInput = null; rebuild(); }, section == index));
            }
            contentTop += 26;
            management();
        }
        button("刷新资料", window.right() - 71, window.bottom() - 26, 64, () -> { gateway.refreshTowns(); gateway.refresh(); });
        restoreFocus(focused);
        if (searchFocused && tab == 1) setInitialFocus(searchInput);
        if (focusedSetting != null && tab == 2 && section == 3 && townInputs.get(focusedSetting).visible) setInitialFocus(townInputs.get(focusedSetting));
    }

    private void restoreFocus(Button previous) {
        if (previous == null) return;
        for (var child : children()) if (child instanceof Button button && button.active && button.visible && button.getX() == previous.getX() && button.getY() == previous.getY() && button.getMessage().getString().equals(previous.getMessage().getString())) { setInitialFocus(button); break; }
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        // 原版会在回调结束后重新聚焦被点击按钮；重建页面后改用新控件。
        if (getFocused() != null && !children().contains(getFocused())) {
            Button previous = getFocused() instanceof Button button ? button : null;
            clearFocus();
            restoreFocus(previous);
        }
        return handled;
    }

    private void home() {
        int x = contentLeft, y = contentTop - homeScroll;
        boolean wide = contentWidth >= 460;
        int sideWidth = wide ? 146 : contentWidth;
        int mainWidth = wide ? contentWidth - sideWidth - 8 : contentWidth;
        int profileX = wide ? x + mainWidth + 8 : x;
        int profileHeight = wide ? 103 : 66;
        panels.add(new Panel(profileX, y, sideWidth, profileHeight, false));
        avatarX = profileX + 10; avatarY = y + 12;
        int nameWidth = wide ? sideWidth - 57 : Math.max(20, sideWidth - 147);
        label(font.plainSubstrByWidth(VersionClient.playerName(), nameWidth), profileX + 47, y + 13, TEXT);
        label(font.plainSubstrByWidth(gateway.loggedIn() ? "身份已连接" : "等待连接", nameWidth), profileX + 47, y + 28, gateway.loggedIn() ? TownStyle.SUCCESS : MUTED);
        JsonObject town = objectValue(gateway.snapshot, "town");
        String townName = stringValue(town, "name", gateway.townId.isEmpty() ? "尚未加入小镇" : "正在载入小镇…");
        label(font.plainSubstrByWidth("所在镇 · " + townName, sideWidth - 20), profileX + 10, y + 49, MUTED);
        int settingsX = wide ? profileX + 10 : profileX + sideWidth - 90;
        int settingsWidth = wide ? sideWidth - 20 : 80;
        button("客户端设置", settingsX, wide ? y + 73 : y + 18, settingsWidth, this::connectionForm);
        int heroY = wide ? y : y + profileHeight + 8;
        int archiveWidth = mainWidth;
        int homeActionWidth = (mainWidth - 30) / 2;
        int heroHeight = 180;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 12; logoY = heroY + 12;
        int titleX = x + (showLogo ? 55 : 12);
        label("小镇档案", titleX, heroY + 14, ACCENT);
        label(font.plainSubstrByWidth(gateway.townId.isEmpty() ? "从这里，开始建设" : townName, archiveWidth - (titleX - x) - 10), titleX, heroY + 31, TEXT);
        if (!gateway.loggedIn()) {
            paragraph("使用当前游戏 UUID 自动连接，登记项目并参与协作。", x + 12, heroY + 70, archiveWidth - 24, MUTED);
            button("重新连接", x + 12, heroY + 148, archiveWidth - 24, () -> VersionClient.connectIdentity(this::rebuild));
        } else if (gateway.townId.isEmpty()) {
            paragraph("注册小镇，或通过八位 ID 加入伙伴。", x + 12, heroY + 70, archiveWidth - 24, MUTED);
        } else {
            label(font.plainSubstrByWidth("ID " + gateway.townId + " · " + array("members").size() + " 位成员", archiveWidth - 24), x + 12, heroY + 66, MUTED);
            List<String> introduction = wrap(stringValue(town, "introduction", "一起规划，一起建设。"), archiveWidth - 24);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 12, heroY + 83 + index * 12, MUTED);
            int openTickets = 0;
            for (JsonElement element : array("tickets")) if (!stringValue(element.getAsJsonObject(), "status").equals("resolved")) openTickets++;
            label(font.plainSubstrByWidth("小镇事务 · " + array("projects").size() + " 项目 · " + openTickets + " 待修", archiveWidth - 24), x + 12, heroY + 115, ACCENT);
            button("项目地图", x + 12, heroY + 148, homeActionWidth, this::openMap);
            button("成员与权限", x + 18 + homeActionWidth, heroY + 148, homeActionWidth, () -> { tab = 2; section = 0; rebuild(); });
        }
        label(font.plainSubstrByWidth("服务器 · " + stringValue(town, "server", VersionClient.serverAddress()), archiveWidth - 24), x + 12, heroY + 131, MUTED);
        int worldY = heroY + heroHeight + 8;
        boolean compactWorld = mainWidth >= 280;
        int worldHeight = compactWorld ? 64 : 96;
        int worldTextWidth = compactWorld ? homeActionWidth : mainWidth - 20;
        panels.add(new Panel(x, worldY, mainWidth, worldHeight, false));
        label("世界与周目", x + 10, worldY + 12, ACCENT);
        label(font.plainSubstrByWidth(worldName(gateway.worldId), worldTextWidth), x + 10, worldY + 29, TEXT);
        label(font.plainSubstrByWidth(roleName(stringValue(objectValue(gateway.snapshot, "currentMember"), "roleId", "尚未加入")), worldTextWidth), x + 10, worldY + 44, MUTED);
        Button world = button("周目与地图", x + 18 + homeActionWidth, worldY + (compactWorld ? 22 : 65), homeActionWidth, this::chooseWorld);
        world.active = !gateway.townId.isEmpty();
        int linksY = wide ? y + profileHeight + 8 : worldY + worldHeight + 8;
        Button create = button("注册小镇", settingsX, linksY, settingsWidth, this::createTown);
        Button join = button("加入小镇", settingsX, linksY + 26, settingsWidth, this::joinTown);
        Button change = button("切换小镇", settingsX, linksY + 52, settingsWidth, this::chooseTown);
        create.active = gateway.loggedIn(); join.active = gateway.loggedIn(); change.active = gateway.loggedIn() && !gateway.townId.isEmpty();
        int bottom = Math.max(worldY + worldHeight, linksY + 72);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - contentBottom);
    }

    private void platform() {
        boolean compact = contentWidth >= 400;
        int actionWidth = compact ? 100 : (contentWidth - 8) / 2;
        int y = contentTop;
        button("打开项目地图", contentLeft, y, actionWidth, this::openMap);
        Button create = button("创建项目", contentLeft + contentWidth - actionWidth, y, actionWidth, this::createRecord);
        create.active = !gateway.worldId.isEmpty() && has("project.create") && !archived();
        int searchLeft = compact ? contentLeft + actionWidth + 6 : contentLeft;
        int searchTop = compact ? y : y + 26;
        int searchWidth = compact ? contentWidth - 2 * (actionWidth + 6) : contentWidth;
        if (searchInput == null) {
            searchInput = new EditBox(font, searchLeft, searchTop, searchWidth - 26, 20, Component.literal("搜索"));
            searchInput.setMaxLength(100);
            searchInput.setResponder(value -> { page = 0; searchChanged = true; });
        }
        searchInput.setX(searchLeft); searchInput.setY(searchTop); searchInput.setWidth(searchWidth - 26);
        String hint = "搜索项目、简介、负责人";
        searchInput.setHint(Component.literal(hint));
        searchInput.setTooltip(Tooltip.create(Component.literal(hint + "；空格分隔多个关键词")));
        addRenderableWidget(searchInput);
        Button clear = button("×", searchLeft + searchWidth - 20, searchTop, 20, () -> { searchInput.setValue(""); pendingSearchFocus = true; });
        clear.setTooltip(Tooltip.create(Component.literal("清空搜索")));
        clear.active = !searchInput.getValue().isEmpty();
        String collection = "projects";
        List<JsonObject> records = objects(array(collection));
        if (!searchInput.getValue().isBlank()) records.removeIf(record -> !matchesSearch(record, subtitle(record), searchInput.getValue()));
        list(records, y + (compact ? 26 : 52), record -> showRecord(collection, record));
    }

    /** 只搜索可读业务文本，多个关键词需全部匹配。 */
    static boolean matchesSearch(JsonObject record, String related, String query) {
        if (query.isBlank()) return true;
        String text = (stringValue(record, "name") + " " + stringValue(record, "purpose") + " " + stringValue(record, "notes") + " " + stringValue(record, "dimension") + " " + state(stringValue(record, "status")) + " " + related).toLowerCase(Locale.ROOT);
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) if (!text.contains(word)) return false;
        return true;
    }

    private void management() {
        int y = contentTop;
        if (section == 0) {
            label(has("members.manage") ? "新人只读；审核时为成员分配权限组。" : "成员名单 · 当前权限仅供查阅", contentLeft + 3, y + 5, MUTED);
            list(objects(array("members")), y + 24, this::memberDetail);
        } else if (section == 1) {
            Button create = button("＋ 创建权限组", contentLeft, y, 125, () -> roleForm(new JsonObject()));
            create.active = has("roles.manage");
            list(objects(array("roles")), y + 28, this::roleDetail);
        } else if (section == 2) {
            Button create = button("＋ 登记新世界 / 周目", contentLeft, y, 170, this::worldForm);
            create.active = has("worlds.manage");
            list(objects(array("worlds")), y + 28, this::worldDetail);
        } else inlineTownSettings(y);
    }

    /** 设置直接编辑，空密码保持现状，关闭密码需要单独确认。 */
    private void inlineTownSettings(int y) {
        JsonObject town = objectValue(gateway.snapshot, "town");
        if (!settingsTownId.equals(gateway.townId)) { settingsTownId = gateway.townId; townInputs.clear(); settingsScroll = 0; }
        boolean editable = has("town.manage") && !settingsSubmitting;
        boolean paired = contentWidth >= 240;
        int fullHeight = 20 + (paired ? 2 : 4) * 36 + 24;
        settingsScroll = Math.max(0, Math.min(settingsScroll, y + fullHeight - contentBottom));
        y -= settingsScroll;
        label("小镇 ID " + gateway.townId, contentLeft, y + 3, MUTED);
        String[][] definitions = {{"name", "名称"}, {"introduction", "介绍"}, {"logo", "Logo（物品 ID 或 texture:路径）"}, {"password", "新密码（留空保持，至少 6 字）"}};
        int fieldWidth = paired ? (contentWidth - 8) / 2 : contentWidth;
        for (int index = 0; index < definitions.length; index++) {
            String key = definitions[index][0], title = definitions[index][1];
            int fieldX = contentLeft + (paired ? index % 2 * (fieldWidth + 8) : 0);
            int fieldY = y + 20 + (paired ? index / 2 : index) * 36;
            label(font.plainSubstrByWidth(title, fieldWidth), fieldX, fieldY, TEXT);
            int inputWidth = fieldWidth - (key.equals("logo") ? 30 : 0);
            EditBox input = townInputs.get(key);
            if (input == null) { input = new EditBox(font, fieldX, fieldY + 11, inputWidth, 20, Component.literal(title)); input.setMaxLength(key.equals("password") ? 128 : 2048); input.setValue(key.equals("password") ? "" : stringValue(town, key)); townInputs.put(key, input); }
            input.setX(fieldX); input.setY(fieldY + 11); input.setWidth(inputWidth); input.setEditable(editable); input.active = editable;
            input.visible = input.getY() >= bodyTop && input.getY() + 20 <= bodyBottom;
            addRenderableWidget(input);
            if (key.equals("logo")) { settingsLogoX = fieldX + inputWidth + 5; settingsLogoY = fieldY + 9; showSettingsLogo = true; }
        }
        int bottom = y + 20 + (paired ? 2 : 4) * 36 + 4;
        int halfWidth = (contentWidth - 8) / 2;
        Button save = button("保存小镇设置", contentLeft, bottom, halfWidth, () -> {
            if (!has("town.manage")) { gateway.status = "没有小镇管理权限"; return; }
            JsonObject data = new JsonObject();
            for (String key : townInputs.keySet()) { String value = townInputs.get(key).getValue().strip(); if (!key.equals("password") || !value.isEmpty()) data.addProperty(key, value); }
            saveTownSettings(data);
        });
        save.active = editable; save.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;
        Button disable = button(bool(town, "passwordEnabled") ? "关闭加入密码" : "加入密码未开启", contentLeft + halfWidth + 8, bottom, halfWidth, () -> confirm("关闭加入密码", "关闭后，知道小镇 ID 的玩家无需密码即可申请加入。", () -> perform("town.update", object("password", ""))));
        disable.active = editable && bool(town, "passwordEnabled"); disable.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;

    }

    private void saveTownSettings(JsonObject data) {
        if (settingsSubmitting || !has("town.manage")) return;
        settingsSubmitting = true;
        data.addProperty("townId", gateway.townId);
        TownGateway.Context context = gateway.context();
        rebuild();
        gateway.request("town.update", data).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            settingsSubmitting = false;
            if (!gateway.isCurrent(context)) return;
            if (failure == null) { gateway.status = "小镇设置已保存"; townInputs.get("password").setValue(""); gateway.refresh(); }
            if (VersionClient.currentScreen() == this) rebuild();
        }));
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details) {
        int rowStep = tab == 2 && section == 1 ? 44 : 48;
        int available = Math.max(0, (contentBottom - top - 24 + 4) / rowStep);
        if (tab == 2 && section == 1) available = Math.min(4, available);
        if (available == 0) {
            if (contentBottom - top >= 14) label("请减小 GUI 缩放以查看列表", contentLeft + 4, top + 3, MUTED);
            return;
        }
        int pages = Math.max(1, (records.size() + available - 1) / available);
        page = Math.max(0, Math.min(page, pages - 1));
        if (records.isEmpty()) {
            int emptyHeight = Math.min(70, contentBottom - top - 24);
            panels.add(new Panel(contentLeft, top, contentWidth, emptyHeight, false));
            boolean searching = tab == 1 && searchInput != null && !searchInput.getValue().isBlank();
            label(searching ? "没有匹配的记录" : "暂无记录", contentLeft + 12, top + 12, TEXT);
            if (emptyHeight >= 45 && searching) label("缩短关键词，或清空搜索查看全部。", contentLeft + 12, top + 30, MUTED);
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * rowStep;
            String title = stringValue(record, "name", stringValue(record, "title", "记录"));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            boolean repair = tab == 1 && List.of("completed", "maintenance").contains(stringValue(record, "status"));
            cards.add(new Card(contentLeft, y, contentWidth, rowStep - 4, title, subtitle, status, repair, record));
            if (repair) {
                Button report = button("报修", contentLeft + contentWidth - 55, y + 12, 48, () -> ticketForm(record));
                report.active = has("ticket.create") && !archived();
            }
            button("详情", contentLeft + contentWidth - (repair ? 109 : 55), y + 12, 48, () -> details.accept(record));
        }
        int footer = contentBottom - 20;
        if (pages > 1) {
            Button previous = button("‹", contentLeft, footer, 24, () -> { page--; rebuild(); });
            Button next = button("›", contentLeft + 100, footer, 24, () -> { page++; rebuild(); });
            previous.active = page > 0;
            next.active = page < pages - 1;
            label((page + 1) + " / " + pages, contentLeft + 36, footer + 6, MUTED);
        }
        boolean history = tab == 1 && archived();
        int countOffset = pages > 1 ? 136 : 0;
        String count = "共 " + records.size() + " 项";
        if (font.width(count) <= contentWidth - countOffset - (history ? 104 : 0)) label(count, contentLeft + countOffset, footer + 6, MUTED);
        if (history) label("历史周目 · 只读", contentLeft + contentWidth - 96, footer + 6, ACCENT);
    }

    private void openMap() {
        if (externalParent && returnScreen != null) { VersionClient.show(returnScreen); return; }
        if (!XaeroBridge.openMap(destination())) VersionClient.show(new TownMapScreen(destination()));
    }
    private void createRecord() { openMap(); }

    private void showRecord(String collection, JsonObject record) {
        List<String> description = describe(record);
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        if (collection.equals("projects")) {
            if (canManageProject(record) && writable) {
                if (has("project.approve")) actions.add(action("移交负责人", () -> form("移交项目负责人", List.of(memberChoice("leaderId", "新负责人", stringValue(record, "leaderId"))), data -> { data.addProperty("id", id); perform("project.update", data); })));
                actions.add(action("编辑项目", () -> projectForm(record, objectValue(record, "chunks"))));
                if (stringValue(record, "status").equals("planned")) actions.add(action("开始施工", () -> perform("project.status", object("id", id, "status", "active"))));
                if (stringValue(record, "status").equals("active")) actions.add(action("标记完工", () -> confirm("项目完工", "确认项目已建成，后续可随时报修。", () -> perform("project.status", object("id", id, "status", "completed")))));
            }
            if (writable && has("ticket.create") && List.of("completed", "maintenance").contains(stringValue(record, "status"))) actions.add(action("提交维护工单", () -> ticketForm(record)));
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id)) actions.add(action(stringValue(ticket, "title") + " · " + state(stringValue(ticket, "status")), () -> showRecord("tickets", ticket)));
            }
            actions.add(action("查看地图", this::openMap));
        } else if (collection.equals("tickets") && writable && (has("ticket.manage") || canManageProject(project(stringValue(record, "projectId"))))) {
            if (stringValue(record, "status").equals("open")) actions.add(action("开始维护", () -> perform("ticket.update", object("id", id, "status", "working"))));
            if (!stringValue(record, "status").equals("resolved")) actions.add(action("完成维护", () -> form("维护结果", List.of(text("resolution", "处理说明", "")), data -> { data.addProperty("id", id); data.addProperty("status", "resolved"); perform("ticket.update", data); })));
        }
        if (record.has("dimension") && !archived()) actions.add(action("导航到这里", () -> VersionClient.navigate(record)));
        VersionClient.show(new TownForm(destination(), stringValue(record, "name", stringValue(record, "title", "项目详情")), List.of(), description, actions, null));
    }

    private List<String> describe(JsonObject record) {
        List<String> result = new ArrayList<>();
        if (record.has("status") && !stringValue(record, "status").equals("completed")) result.add("状态：" + state(stringValue(record, "status")));
        String[][] labels = {{"purpose", "简介"}, {"description", "说明"}, {"notes", "备注"}, {"resolution", "处理结果"}};
        for (String[] label : labels) if (!stringValue(record, label[0]).isBlank()) result.add(label[1] + "：" + stringValue(record, label[0]));
        if (record.has("leaderId")) result.add("负责人：" + memberName(stringValue(record, "leaderId")));
        if (record.has("projectId") && !stringValue(record, "projectId").isBlank()) result.add("项目：" + projectName(stringValue(record, "projectId")));
        if (record.has("chunks")) { JsonObject chunks = objectValue(record, "chunks"); result.add("区块：(" + stringValue(chunks, "minX") + ", " + stringValue(chunks, "minZ") + ") → (" + stringValue(chunks, "maxX") + ", " + stringValue(chunks, "maxZ") + ")"); }
        if (record.has("x")) result.add("位置：" + stringValue(record, "dimension") + "  " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z"));
        if (record.has("createdBy")) result.add("登记人：" + stringValue(record, "createdByName", memberName(stringValue(record, "createdBy"))));
        if (!arrayValue(record, "overlapIds").isEmpty()) result.add("与 " + arrayValue(record, "overlapIds").size() + " 个项目区域重叠");
        return result;
    }

    private void projectForm(JsonObject record, JsonObject chunks) {
        boolean editing = record.has("id");
        if (archived() || gateway.worldId.isEmpty() || !(editing ? canManageProject(record) : has("project.create"))) { gateway.status = "当前项目只读或没有操作权限"; return; }
        String status = stringValue(record, "status", "planned");
        List<TownForm.Field> fields = List.of(text("name", "项目名", stringValue(record, "name")).compact(), choice("type", "类型", stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{"建筑", "机器", "其他"}), multiline("purpose", "简介", stringValue(record, "purpose")), choice("status", "状态", status, editing ? new String[]{status} : new String[]{"planned", "active", "completed"}, editing ? new String[]{status.equals("maintenance") ? "待维护（由工单控制）" : status.equals("completed") ? "已完工" : state(status)} : new String[]{"计划中", "施工中", "已完工"}));
        String dimension = stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()));
        List<String> details = List.of("选区：" + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + dimension);
        VersionClient.show(new TownForm(destination(), editing ? "编辑项目" : "创建项目", fields, details, List.of(), data -> {
            if (editing) data.remove("status");
            data.add("chunks", chunks.deepCopy()); data.addProperty("dimension", dimension); data.addProperty("notes", stringValue(record, "notes"));
            if (editing) data.addProperty("id", stringValue(record, "id")); else data.addProperty("worldId", gateway.worldId);
            perform(editing ? "project.update" : "project.create", data);
        }));
    }

    private void ticketForm(JsonObject project) {
        JsonObject location = VersionClient.position();
        boolean atProjectWorld = Minecraft.getInstance().player != null && XaeroBridge.isCurrentWorld() && stringValue(location, "dimension").equals(stringValue(project, "dimension"));
        if (!atProjectWorld) {
            JsonObject chunks = objectValue(project, "chunks");
            location = object("dimension", stringValue(project, "dimension"), "x", (integer(chunks, "minX") + integer(chunks, "maxX") + 1) * 8, "y", 64, "z", (integer(chunks, "minZ") + integer(chunks, "maxZ") + 1) * 8);
        }
        JsonObject position = location;
        List<TownForm.Field> fields = List.of(choice("title", "故障类型", "机器堵塞", new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}, new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}).compact(), text("description", "补充说明", "").compact());
        VersionClient.show(new TownForm(destination(), "维护报修 · " + stringValue(project, "name"), fields, List.of((atProjectWorld ? "当前位置：" : "项目中心：") + stringValue(position, "dimension") + " " + integer(position, "x") + ", " + integer(position, "y") + ", " + integer(position, "z")), List.of(), data -> {
            for (String key : List.of("dimension", "x", "y", "z")) data.add(key, position.get(key));
            data.addProperty("projectId", stringValue(project, "id")); data.addProperty("worldId", stringValue(project, "worldId")); perform("ticket.create", data);
        }));
    }

    private void connectionForm() {
        VersionClient.show(VersionClient.createConfigScreen(this));
    }
    private void createTown() {
        form("注册小镇", List.of(text("name", "小镇名称", ""), text("introduction", "小镇介绍", ""), text("logo", "Logo：物品 ID 或 texture:命名空间:贴图路径.png", "minecraft:oak_sapling"), text("server", "所在服务器", VersionClient.serverAddress()), text("password", "加入密码（留空关闭，开启时至少 6 字）", "")), data -> perform("town.create", data));
    }
    private void joinTown() {
        form("通过小镇 ID 加入", List.of(text("townId", "八位数字小镇 ID", ""), text("password", "小镇密码（未开启则留空）", "")), data -> perform("town.join", data));
    }
    private void chooseTown() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : gateway.towns) { JsonObject town = element.getAsJsonObject(); actions.add(action(stringValue(town, "name") + " · " + stringValue(town, "id"), () -> { gateway.selectTown(stringValue(town, "id")); homeScroll = 0; tab = 0; VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择当前小镇", List.of(), List.of("加入多个小镇后，可随时切换。"), actions, null));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); actions.add(action(stringValue(world, "name") + (bool(world, "archived") ? " · 历史" : " · 进行中"), () -> { gateway.selectWorld(stringValue(world, "id")); VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择世界与周目", List.of(), List.of("归档周目保存项目与业务标记，并按 Xaero 世界标识关联个人本地历史地图。"), actions, null));
    }
    private void memberDetail(JsonObject member) {
        String id = stringValue(member, "playerId");
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("复制玩家 UUID", () -> { VersionClient.copy(id); gateway.status = "已复制 UUID"; }));
        if (has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")))) {
            actions.add(action("审核 / 分配权限组", () -> form("修改 " + stringValue(member, "name") + " 的权限组", List.of(roleChoice(stringValue(member, "roleId"))), data -> { data.addProperty("playerId", id); perform("member.role", data); })));
            actions.add(action("移出小镇", () -> confirm("移出成员", "将 " + stringValue(member, "name") + " 移出小镇。", () -> perform("member.remove", object("playerId", id)))));
        }
        if (isMayor() && !id.equals(playerId())) actions.add(action("移交镇长职位", () -> confirm("移交镇长", "将唯一镇长职位交给 " + stringValue(member, "name") + "，你的权限将改变。", () -> perform("town.transfer", object("playerId", id)))));
        VersionClient.show(new TownForm(this, stringValue(member, "name"), List.of(), List.of("权限组：" + roleName(stringValue(member, "roleId")), "UUID：" + id, "加入时间：" + stringValue(member, "joinedAt")), actions, null));
    }
    private void roleDetail(JsonObject role) {
        List<String> description = new ArrayList<>();
        description.add("权限组 ID：" + stringValue(role, "id"));
        for (JsonElement permission : arrayValue(role, "permissions")) description.add("✓ " + permissionName(permission.getAsString()));
        if (arrayValue(role, "permissions").isEmpty()) description.add("只读；没有修改权限。");
        List<TownForm.Action> actions = new ArrayList<>();
        if (canEditRole(role)) {
            actions.add(action(List.of("newcomer", "mayor").contains(stringValue(role, "id")) ? "修改图标" : "编辑名称、图标与权限", () -> roleForm(role)));
        }
        boolean assigned = objects(array("members")).stream().anyMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")));
        if (has("roles.manage") && canGrant(role) && !assigned && !List.of("newcomer", "mayor", "member", "manager").contains(stringValue(role, "id"))) actions.add(action("删除权限组", () -> confirm("删除权限组", "只有没有成员使用的权限组可删除。", () -> perform("role.delete", object("id", stringValue(role, "id"))))));
        if (has("roles.manage") && !canEditRole(role)) description.add("此权限组不可编辑：需具备该组全部权限；修改其他成员使用的组还需成员管理权限。镇长与新人组只允许修改图标。");
        VersionClient.show(new TownForm(this, stringValue(role, "name"), List.of(), description, actions, null));
    }
    private static String roleIcon(JsonObject role) {
        String fallback = switch (stringValue(role, "id")) { case "newcomer" -> "minecraft:dirt"; case "member" -> "minecraft:cobblestone"; case "manager" -> "minecraft:iron_block"; case "mayor" -> "minecraft:emerald_block"; default -> "minecraft:stone"; };
        return stringValue(role, "icon", fallback);
    }
    private void roleForm(JsonObject role) {
        boolean fixed = List.of("newcomer", "mayor").contains(stringValue(role, "id"));
        List<TownForm.Field> fields = new ArrayList<>();
        if (!fixed) fields.add(text("name", "权限组名称", stringValue(role, "name")).compact());
        fields.add(icon("icon", "权限组图标", roleIcon(role)).compact());
        if (!fixed) for (JsonElement element : array("permissionCatalog")) { JsonObject permission = element.getAsJsonObject(); String id = stringValue(permission, "id"); if (isMayor() || has(id)) fields.add(toggle(id, stringValue(permission, "name"), contains(arrayValue(role, "permissions"), id))); }
        form(fixed ? "修改权限组图标" : "逐项设置权限", fields, data -> {
            JsonObject payload = object("icon", stringValue(data, "icon"));
            if (!fixed) {
                JsonArray permissions = new JsonArray();
                for (TownForm.Field field : fields) if (!List.of("name", "icon").contains(field.key()) && bool(data, field.key())) permissions.add(field.key());
                payload.addProperty("name", stringValue(data, "name")); payload.add("permissions", permissions);
            }
            if (role.has("id")) payload.addProperty("id", stringValue(role, "id"));
            perform("role.save", payload);
        });
    }
    private void worldForm() { worldForm(new JsonObject()); }
    private void worldForm(JsonObject world) {
        form(world.has("id") ? "编辑世界 / 周目信息" : "登记新世界 / 新周目", List.of(text("name", "世界 / 周目名称", stringValue(world, "name")), text("serverAddress", "游戏服务器地址", stringValue(world, "serverAddress", VersionClient.serverAddress())), text("worldKey", "世界标识（小镇自定义，如周目编号）", stringValue(world, "worldKey"))), data -> { if (world.has("id")) data.addProperty("id", stringValue(world, "id")); perform(world.has("id") ? "world.update" : "world.create", data); });
    }
    private void worldDetail(JsonObject world) {
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("切换并查看" + (bool(world, "archived") ? "历史" : "当前") + "地图资料", () -> { gateway.selectWorld(stringValue(world, "id")); VersionClient.show(destination()); }));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action("编辑世界名称、服务器与地图标识", () -> worldForm(world)));
        if (!XaeroBridge.currentMapKey().isBlank() && stringValue(world, "id").equals(gateway.worldId))
            actions.add(action(bool(world, "archived") ? "关联历史底图" : "关联此地图并继续", () -> openMapBinding(destination())));
        String viewedDimension = XaeroBridge.currentMapDimension();
        if (stringValue(world, "id").equals(gateway.worldId) && !gateway.getBoundMapKey(viewedDimension).isEmpty())
            actions.add(action("解除当前地图关联", () -> confirm("解除地图关联", "仅解除本机当前维度的关联，项目与 Xaero 地形保留。",
                    () -> { gateway.unbindMap(viewedDimension); VersionClient.show(destination()); })));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action("归档此世界 / 周目", () -> confirm("归档周目", "归档后业务资料只读，个人 Xaero 地图保留。", () -> perform("world.archive", object("worldId", stringValue(world, "id"))))));
        List<String> details = new ArrayList<>(List.of("服务器：" + stringValue(world, "serverAddress"), "世界标识：" + stringValue(world, "worldKey"), "状态：" + (bool(world, "archived") ? "已归档" : "进行中"), "个人地图绑定仅保存在本机，每位成员分别关联自己探索的 Xaero 地形。"));
        if (stringValue(world, "id").equals(gateway.worldId)) {
            String dimension = XaeroBridge.currentMapDimension();
            String bound = gateway.getBoundMapKey(dimension);
            details.add("我的 " + dimension + " 地图：" + (bound.isBlank() ? "尚未绑定" : bound));
        } else details.add("选择此周目后，可查看本机的地图绑定。");
        VersionClient.show(new TownForm(destination(), stringValue(world, "name"), List.of(), details, actions, null));
    }
    private void bindWorldMap(JsonObject world) {
        String dimension = XaeroBridge.currentMapDimension(), mapKey = XaeroBridge.currentMapKey();
        String explanation = "周目：" + stringValue(world, "name") + "\n地图维度：" + dimension + "\n个人 Xaero 实例：" + mapKey + "\n请确认当前 Xaero 实例属于这个周目。";
        if (bool(world, "archived")) explanation += "\n这是历史周目，请先在 Xaero 中选择对应历史实例；不要关联当前周目。";
        confirm("关联历史底图", explanation, () -> { gateway.bindCurrentMap(dimension, mapKey); gateway.status = "历史底图已关联，仅供查看"; VersionClient.show(destination()); });
    }
    private TownForm.Field roleChoice(String selected) {
        JsonArray roles = new JsonArray();
        for (JsonElement element : array("roles")) {
            JsonObject role = element.getAsJsonObject();
            if (stringValue(role, "id").equals("mayor")) continue;
            if (canGrant(role)) roles.add(element);
        }
        return collectionChoice("roleId", "权限组", roles, "id", "name", selected);
    }
    private TownForm.Field memberChoice(String key, String label, String selected) { return collectionChoice(key, label, array("members"), "playerId", "name", selected); }
    private TownForm.Field collectionChoice(String key, String label, JsonArray collection, String idKey, String nameKey, String selected) {
        List<String> values = new ArrayList<>(), names = new ArrayList<>();
        for (JsonElement element : collection) { JsonObject item = element.getAsJsonObject(); values.add(stringValue(item, idKey)); names.add(stringValue(item, nameKey)); }
        if (values.isEmpty()) { values.add(""); names.add("暂无可选项"); }
        return choice(key, label, values.contains(selected) ? selected : values.get(0), values.toArray(String[]::new), names.toArray(String[]::new));
    }
    private void form(String title, List<TownForm.Field> fields, Consumer<JsonObject> submit) { VersionClient.show(new TownForm(destination(), title, fields, submit)); }
    private void confirm(String title, String explanation, Runnable submit) { VersionClient.show(new TownForm(destination(), title, List.of(), List.of(explanation), List.of(), ignored -> submit.run())); }
    private TownForm.Action action(String label, Runnable run) { return new TownForm.Action(label, run); }
    private void perform(String action, JsonObject data) {
        boolean selectTown = action.equals("town.create") || action.equals("town.join");
        if (!selectTown && !data.has("townId")) data.addProperty("townId", gateway.townId);
        TownGateway.Context context = gateway.context();
        Screen origin = VersionClient.currentScreen();
        TownForm.track(gateway.request(action, data).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            if (!gateway.isCurrent(context)) return;
            gateway.status = "操作已保存";
            if (selectTown) {
                gateway.refreshTowns();
                if (VersionClient.currentScreen() == origin) { gateway.selectTown(stringValue(result, "townId")); tab = 0; section = 0; page = 0; }
            } else gateway.refresh();
            if (VersionClient.currentScreen() == origin) VersionClient.show(destination());
        })));
    }

    private Button button(String label, int x, int y, int width, Runnable run) {
        Button button = TownStyle.button(label, x, y, width, run);
        if (homeContent) { homeButtons.add(button); button.visible = y >= bodyTop && y + 20 <= bodyBottom; }
        return addRenderableWidget(button);
    }
    private void label(String text, int x, int y, int color) { lines.add(new Line(text, x, y, color)); }
    private int paragraph(String text, int x, int y, int width, int color) { for (String line : wrap(text, width)) { label(line, x, y, color); y += 13; } return y; }
    private List<String> wrap(String text, int width) { return wrapText(text, width, font); }
    static List<String> wrapText(String text, int width, net.minecraft.client.gui.Font font) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            String remaining = paragraph;
            while (!remaining.isEmpty()) { String part = font.plainSubstrByWidth(remaining, Math.max(12, width)); if (part.isEmpty()) part = remaining.substring(0, 1); result.add(part); remaining = remaining.substring(part.length()); }
            if (paragraph.isEmpty()) result.add("");
        }
        return result;
    }
    static JsonObject objectValue(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    static JsonArray arrayValue(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    static String stringValue(JsonObject object, String key) { return stringValue(object, key, ""); }
    static String stringValue(JsonObject object, String key, String fallback) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback; }
    static int integer(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0; }
    static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
    private JsonArray array(String key) { return arrayValue(gateway.snapshot, key); }
    private List<JsonObject> objects(JsonArray array) { List<JsonObject> result = new ArrayList<>(); for (JsonElement element : array) result.add(element.getAsJsonObject()); return result; }
    static boolean contains(JsonArray array, String value) { for (JsonElement item : array) if (item.isJsonPrimitive() && item.getAsString().equals(value)) return true; return false; }
    private boolean has(String permission) { return contains(array("permissions"), permission); }
    private JsonObject project(String id) { for (JsonElement element : array("projects")) if (stringValue(element.getAsJsonObject(), "id").equals(id)) return element.getAsJsonObject(); return new JsonObject(); }
    private boolean canManageProject(JsonObject project) { return has("project.approve") || has("project.edit") && stringValue(project, "leaderId").equals(playerId()); }
    private JsonObject role(String id) { for (JsonElement element : array("roles")) if (stringValue(element.getAsJsonObject(), "id").equals(id)) return element.getAsJsonObject(); return new JsonObject(); }
    private boolean canGrant(JsonObject role) {
        if (!role.has("id")) return false;
        for (JsonElement permission : arrayValue(role, "permissions")) if (!has(permission.getAsString())) return false;
        return true;
    }
    private boolean canEditRole(JsonObject role) {
        if (!has("roles.manage") || !canGrant(role)) return false;
        return has("members.manage") || objects(array("members")).stream().noneMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")) && !stringValue(member, "playerId").equals(playerId()));
    }
    private boolean isMayor() { return stringValue(objectValue(gateway.snapshot, "town"), "ownerId").equals(playerId()); }
    private String playerId() { return stringValue(gateway.player(), "id", stringValue(gateway.player(), "playerId")); }
    private boolean archived() { for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); if (stringValue(world, "id").equals(gateway.worldId)) return bool(world, "archived"); } return false; }
    private String lookup(String collection, String key, String id, String fallback) { for (JsonElement element : array(collection)) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, key).equals(id)) return stringValue(item, "name", fallback); } return fallback; }
    private String projectName(String id) { return lookup("projects", "id", id, "未指定"); }
    private String worldName(String id) { return lookup("worlds", "id", id, "尚未登记世界"); }
    private String roleName(String id) { return lookup("roles", "id", id, id); }
    private String memberName(String id) { return lookup("members", "playerId", id, id.isEmpty() ? "未指定" : id); }
    private String permissionName(String id) { for (JsonElement element : array("permissionCatalog")) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, "id").equals(id)) return stringValue(item, "name"); } return id; }
    private String subtitle(JsonObject record) {
        if (record.has("roleId")) return roleName(stringValue(record, "roleId"));
        if (record.has("permissions")) return arrayValue(record, "permissions").size() + " 项权限";
        if (record.has("worldKey")) return stringValue(record, "serverAddress") + " · " + stringValue(record, "worldKey");
        if (record.has("projectId")) return projectName(stringValue(record, "projectId")) + " · " + memberName(stringValue(record, "createdBy"));
        if (record.has("purpose")) return (stringValue(record, "purpose").isBlank() ? "" : stringValue(record, "purpose") + " · ") + memberName(stringValue(record, "leaderId"));
        return stringValue(record, "dimension") + (record.has("x") ? " · " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z") : "");
    }
    static String state(String value) { return switch (value) { case "planned" -> "计划中"; case "active" -> "施工中"; case "completed" -> ""; case "maintenance" -> "待维护"; case "open" -> "待处理"; case "working" -> "处理中"; case "resolved" -> "已解决"; case "archived" -> "历史归档"; default -> value; }; }

    static void background(GuiGraphicsExtractor graphics, int width, int height) { TownStyle.background(graphics, width, height); }
    private void moveScroll(int direction, boolean pageStep) {
        if (tab == 2 && section == 3) settingsScroll = Math.max(0, settingsScroll + direction * (pageStep ? Math.max(32, bodyBottom - bodyTop - 20) : 32));
        else if (tab == 0) homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + direction * (pageStep ? Math.max(32, bodyBottom - bodyTop - 20) : 32)));
        else page += direction;
        rebuild();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < contentLeft || x > contentLeft + contentWidth || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        moveScroll(vertical < 0 ? 1 : -1, false);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (tab == 0 && event.isCycleFocus()) {
            int direction = event.hasShiftDown() ? -1 : 1;
            int index = children().indexOf(getFocused());
            if (index < 0) index = direction > 0 ? -1 : 0;
            for (int step = 0; step < children().size(); step++) {
                index = Math.floorMod(index + direction, children().size());
                if (!(children().get(index) instanceof Button target) || !target.active) continue;
                if (homeButtons.contains(target) && !target.visible) {
                    int offset = target.getY() < bodyTop ? target.getY() - bodyTop : target.getY() + target.getHeight() - bodyBottom;
                    homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + offset));
                    rebuild();
                }
                setInitialFocus(children().get(index));
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            moveScroll(event.key() == InputConstants.KEY_PAGEUP ? -1 : 1, true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        background(graphics, width, height);
        TownStyle.window(graphics, window, bodyTop, bodyBottom);
        double progress = gateway.config().animationEnabled ? Math.min(1, (System.nanoTime() - openedAt) / 260_000_000d) : 1;
        double eased = 1 - Math.pow(1 - progress, 3);
        int headingX = contentLeft + (int) ((1 - eased) * 4);
        TownStyle.icon(graphics, 0, headingX, window.top() + 11, TownStyle.FRAME_TEXT);
        graphics.text(font, font.plainSubstrByWidth(TownStyle.title(), contentWidth - 50), headingX + 14, window.top() + 10, TownStyle.FRAME_TEXT, false);
        Component hoveredText = null;
        graphics.enableScissor(contentLeft, bodyTop, contentLeft + contentWidth, bodyBottom);
        for (Panel panel : panels) TownStyle.panel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), panel.accented());
        for (Card card : cards) {
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), card.height(), false);
            boolean projectRow = tab == 1;
            int textOffset = projectRow ? 12 : 38;
            if (!projectRow) {
                TownStyle.panel(graphics, card.x() + 7, card.y() + 10, 23, 23, false);
                if (tab == 2 && section == 0) VersionClient.drawAvatar(graphics, stringValue(card.record(), "playerId"), stringValue(card.record(), "name"), card.x() + 9, card.y() + 12, 20);
                else if (tab == 2 && section == 1) VersionClient.drawLogo(graphics, roleIcon(card.record()), card.x() + 8, card.y() + 10, 24);
                else TownStyle.icon(graphics, 3, card.x() + 15, card.y() + 18, ACCENT);
            }
            int buttonSpace = card.repair() ? 120 : 66;
            int textWidth = card.width() - textOffset - buttonSpace;
            int titleY = card.y() + 9;
            int badgeWidth = card.state().isEmpty() ? 0 : Math.min(79, font.width(card.state()) + 12);
            int titleWidth = Math.max(0, textWidth - (badgeWidth > 0 ? badgeWidth + 8 : 0));
            graphics.text(font, font.plainSubstrByWidth(card.title(), titleWidth), card.x() + textOffset, titleY, TEXT, true);
            graphics.text(font, font.plainSubstrByWidth(card.subtitle(), Math.max(0, textWidth)), card.x() + textOffset, card.y() + 26, MUTED, false);
            if ((font.width(card.title()) > titleWidth || font.width(card.subtitle()) > textWidth)
                    && mouseX >= card.x() + textOffset && mouseX < card.x() + textOffset + textWidth && mouseY >= titleY - 3 && mouseY < card.y() + 38 && mouseY >= bodyTop && mouseY < bodyBottom)
                hoveredText = Component.literal(card.title() + (card.subtitle().isEmpty() ? "" : "\n" + card.subtitle()));
            if (badgeWidth > 0) {
                int badgeX = card.x() + card.width() - buttonSpace - badgeWidth;
                graphics.fill(badgeX, titleY - 3, badgeX + badgeWidth, titleY + 11, TownStyle.RAISED);
                graphics.text(font, font.plainSubstrByWidth(card.state(), badgeWidth - 12), badgeX + 6, titleY, ACCENT, false);
            }
        }
        for (Line line : lines) graphics.text(font, font.plainSubstrByWidth(line.text(), contentLeft + contentWidth - line.x() - 8), line.x(), line.y(), line.color(), false);
        if (tab == 0) {
            VersionClient.drawAvatar(graphics, avatarX, avatarY, 28);
            if (showLogo) {
                TownStyle.panel(graphics, logoX - 4, logoY - 4, 36, 36, false);
                VersionClient.drawLogo(graphics, stringValue(objectValue(gateway.snapshot, "town"), "logo"), logoX - 2, logoY - 2, 32);
            }
        }
        if (showSettingsLogo) VersionClient.drawLogo(graphics, townInputs.get("logo").getValue(), settingsLogoX, settingsLogoY, 24);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (hoveredText != null) graphics.setTooltipForNextFrame(hoveredText, mouseX, mouseY);
        if (tab == 0 && maximumHomeScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int scrollbarX = window.right() - 10;
            int thumbHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maximumHomeScroll));
            graphics.fill(scrollbarX, bodyTop, scrollbarX + 2, bodyBottom, TownStyle.LINE);
            int thumbY = bodyTop + (trackHeight - thumbHeight) * homeScroll / maximumHomeScroll;
            graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, ACCENT);
        }
        graphics.fill(contentLeft, window.bottom() - 19, contentLeft + 3, window.bottom() - 16, gateway.loggedIn() ? 0xFF42643C : TownStyle.FRAME_MUTED);
        graphics.text(font, font.plainSubstrByWidth(gateway.status, contentWidth - 84), contentLeft + 9, window.bottom() - 20, TownStyle.FRAME_MUTED, false);
        if (font.width(gateway.status) > contentWidth - 84 && mouseX >= contentLeft + 9 && mouseX < contentLeft + contentWidth - 76 && mouseY >= window.bottom() - 23 && mouseY < window.bottom() - 9) graphics.setTooltipForNextFrame(Component.literal(gateway.status), mouseX, mouseY);
    }
}
