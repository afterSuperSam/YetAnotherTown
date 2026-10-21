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
    private final List<Line> lines = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<ItemIcon> homeIcons = new ArrayList<>();
    private record ItemIcon(String item, int x, int y) { }
    private final List<Button> homeButtons = new ArrayList<>();
    private int avatarX, avatarY, logoX, logoY;
    private boolean showLogo;
    private final Map<String, EditBox> townInputs = new LinkedHashMap<>();
    private final Map<String, String> savedTownValues = new LinkedHashMap<>();
    private String settingsTownId = "";
    private String settingsEndpoint = "";
    private long settingsAuthentication = -1;
    private JsonObject settingsSnapshot;
    private int settingsLogoX, settingsLogoY;
    private boolean showSettingsLogo;
    private boolean settingsSubmitting;
    private int settingsScroll;
    private int tab;
    private int section;
    private int page;
    private int projectType;
    private EditBox searchInput;
    private boolean searchChanged;
    private boolean pendingSearchFocus;
    private TownGateway.Context listContext;
    private JsonObject previousSnapshot;
    private JsonObject previousNavigation;
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
            if (stringValue(world, "id").equals(controller.gateway.worldId) && bool(world, "archived")) {
                controller.bindWorldMap(world);
                return;
            }
        }
        List<TownForm.Action> actions = new ArrayList<>();
        JsonObject current = controller.currentWorld();
        if (!current.isEmpty()) actions.add(controller.action("返回小镇当前世界", () -> {
            controller.gateway.followCurrentWorld();
            VersionClient.show(parent);
        }));
        if (controller.isMayor()) {
            if (!current.isEmpty()) actions.add(controller.action("使用我所在的地图", () -> controller.activateWorld(current)));
            else actions.add(controller.action("选择并设置当前世界", controller::chooseWorld));
            if (controller.has("worlds.manage")) actions.add(controller.action("登记新世界 / 周目", controller::worldForm));
        }
        String expectedName = stringValue(objectValue(current, "mapNames"), XaeroBridge.currentMapDimension(), stringValue(current, "mapName"));
        String explanation = current.isEmpty() || expectedName.isBlank()
                ? "请由镇长进入游戏，点击使用所在地图。设置一次，成员自动匹配，各维度均可创建工程。"
                : "小镇当前世界：" + stringValue(current, "name") + "\n服务器：" + stringValue(current, "serverAddress")
                    + "\n小镇地图：" + expectedName + "\n本机地图：" + XaeroBridge.currentMapName()
                    + "\n服务器、维度和地图名称匹配后自动显示工程。如名称不同，请在 Xaero 恢复名称或联系镇长更新。";
        VersionClient.show(new TownForm(parent, "小镇当前地图", List.of(), List.of(explanation), actions, null));
    }
    private Screen destination() { return externalParent ? returnScreen : this; }

    @Override protected void init() { rebuild(); }
    @Override public boolean isPauseScreen() { return false; }
    private boolean settingsScopeMatches() {
        return sameSettingsScope(settingsTownId, settingsEndpoint, settingsAuthentication, gateway.townId, gateway.context());
    }
    static boolean sameSettingsScope(String savedTown, String savedEndpoint, long savedAuthentication, String currentTown, TownGateway.Context context) {
        return savedTown.equals(currentTown) && savedEndpoint.equals(context.endpoint()) && savedAuthentication == context.authentication();
    }
    static boolean validSettingsTown(JsonObject snapshot, String townId) { return !townId.isEmpty() && townId.equals(stringValue(objectValue(snapshot, "town"), "id")); }
    static String synchronizedSettingValue(String value, String baseline, String remote) { return value.equals(baseline) ? remote : value; }
    static JsonObject changedTownSettings(Map<String, String> values, Map<String, String> baseline, JsonObject snapshot, String townId) {
        JsonObject changes = new JsonObject();
        if (!validSettingsTown(snapshot, townId)) return changes;
        for (String key : List.of("name", "introduction", "logo", "password")) if (values.containsKey(key)) {
            String value = values.get(key).strip();
            if (key.equals("password") ? !value.isEmpty() : !value.equals(baseline.get(key))) changes.addProperty(key, value);
        }
        return changes;
    }
    static boolean isNavigationTarget(JsonObject record, JsonObject target) {
        return target != null && !stringValue(record, "id").isEmpty() && stringValue(record, "id").equals(stringValue(target, "id"))
                && stringValue(record, "worldId").equals(stringValue(target, "worldId"));
    }
    private boolean dirtyTownSettings() {
        return settingsScopeMatches() && townInputs.entrySet().stream().anyMatch(entry -> !entry.getValue().getValue().equals(savedTownValues.get(entry.getKey())));
    }
    private void resetTownSettings() {
        TownGateway.Context context = gateway.context();
        settingsTownId = gateway.townId; settingsEndpoint = context.endpoint(); settingsAuthentication = context.authentication();
        townInputs.clear(); savedTownValues.clear(); settingsSnapshot = null; settingsScroll = 0; settingsSubmitting = false;
    }
    private void leaveTownSettings(Runnable action) {
        if (settingsSubmitting) { gateway.status = "正在保存小镇设置，请稍候…"; return; }
        if (dirtyTownSettings()) {
            VersionClient.show(new TownForm(this, "放弃未保存的小镇设置", List.of(), List.of("小镇设置尚未保存，关闭后将丢失本次修改。"),
                    List.of(action("放弃修改并继续", () -> { resetTownSettings(); action.run(); })), null));
        } else action.run();
    }
    @Override public void onClose() { leaveTownSettings(() -> VersionClient.show(parent)); }
    @Override public void tick() {
        if (searchChanged || previousSnapshot != gateway.snapshot || previousTowns != gateway.towns || previousNavigation != VersionClient.navigation()) rebuild();
    }

    private void rebuild() {
        if (!settingsScopeMatches()) resetTownSettings();
        if (!gateway.context().equals(listContext)) { listContext = gateway.context(); searchInput = null; page = 0; }
        String focusedSetting = townInputs.entrySet().stream().filter(entry -> entry.getValue() == getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        boolean searchFocused = searchInput != null && (pendingSearchFocus || getFocused() == searchInput);
        pendingSearchFocus = false;
        searchChanged = false;
        Button focused = getFocused() instanceof Button button ? button : null;
        clearFocus();
        showSettingsLogo = false;
        clearWidgets(); lines.clear(); cards.clear(); panels.clear(); homeButtons.clear(); homeIcons.clear();
        previousSnapshot = gateway.snapshot; previousTowns = gateway.towns;
        previousNavigation = VersionClient.navigation();
        window = TownStyle.bounds(width, height, 560, 346);
        contentLeft = window.left() + 12;
        contentWidth = window.width() - 24;
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 34;
        contentBottom = bodyBottom - 6;
        String[] tabs = {"主页", "平台", "管理"};
        int navigationWidth = Math.min(86, (contentWidth - 8) / 3);
        for (int index = 0; index < tabs.length; index++) {
            final int selected = index;
            Button navigation = addRenderableWidget(TownStyle.tab(tabs[index], contentLeft - 6 + index * (navigationWidth + 4), window.top() + 25, navigationWidth,
                    () -> { tab = selected; section = 0; page = 0; searchInput = null; rebuild(); }, tab == index));
            navigation.active = index == 0 || gateway.loggedIn() && !gateway.townId.isEmpty();
        }
        button("×", window.right() - 26, window.top() + 5, 20, this::onClose);
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
        restoreFocus(focused);
        if (searchFocused && tab == 1) setInitialFocus(searchInput);
        if (focusedSetting != null && tab == 2 && section == 3 && children().contains(townInputs.get(focusedSetting)) && townInputs.get(focusedSetting).visible) setInitialFocus(townInputs.get(focusedSetting));
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
        int homeActionWidth = (mainWidth - 36) / 3;
        int heroHeight = 164;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 12; logoY = heroY + 12;
        int titleX = x + (showLogo ? 55 : 12);
        label("小镇档案" + (gateway.townId.isEmpty() ? "" : " · ID " + gateway.townId), titleX, heroY + 14, ACCENT);
        label(font.plainSubstrByWidth(gateway.townId.isEmpty() ? "从这里，开始建设" : townName, archiveWidth - (titleX - x) - 10), titleX, heroY + 31, TEXT);
        if (!gateway.loggedIn()) {
            paragraph("使用当前游戏 UUID 自动连接，登记项目并参与协作。", x + 12, heroY + 70, archiveWidth - 24, MUTED);
            button("重新连接", x + 12, heroY + 132, archiveWidth - 24, () -> VersionClient.connectIdentity(this::rebuild));
        } else if (gateway.townId.isEmpty()) {
            paragraph("注册小镇，或通过八位 ID 加入伙伴。", x + 12, heroY + 70, archiveWidth - 24, MUTED);
        } else {
            label(font.plainSubstrByWidth(array("members").size() + " 位成员 · 服务器 "
                    + stringValue(town, "server", VersionClient.serverAddress()), archiveWidth - 24), x + 12, heroY + 66, MUTED);
            List<String> introduction = wrap(stringValue(town, "introduction", "一起规划，一起建设。"), archiveWidth - 24);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 12, heroY + 83 + index * 12, MUTED);
            long maintenance = objects(array("projects")).stream().filter(item -> stringValue(item, "status").equals("maintenance")).count();
            int statisticsY = heroY + 83 + Math.min(2, introduction.size()) * 12 + 3;
            String projectCount = array("projects").size() + " 项目";
            int maintenanceX = x + 36 + font.width(projectCount) + 14;
            homeIcons.add(new ItemIcon("minecraft:quartz", x + 12, statisticsY - 5));
            homeIcons.add(new ItemIcon("minecraft:redstone", maintenanceX, statisticsY - 5));
            label(projectCount, x + 36, statisticsY + 2, TEXT);
            label(maintenance + " 待修", maintenanceX + 24, statisticsY + 2, maintenance > 0 ? TownStyle.DANGER : TownStyle.SUCCESS);
            button("项目地图", x + 12, heroY + 132, homeActionWidth, this::openMap);
            button("成员权限", x + 18 + homeActionWidth, heroY + 132, homeActionWidth, () -> { tab = 2; section = 0; rebuild(); });
            button("小镇世界", x + 24 + homeActionWidth * 2, heroY + 132, homeActionWidth, this::chooseWorld);
        }
        int worldY = heroY + heroHeight + 8;
        int worldHeight = 76;
        panels.add(new Panel(x, worldY, mainWidth, worldHeight, false));
        label("最近项目 · 待修优先", x + 10, worldY + 9, ACCENT);
        List<JsonObject> recent = recentProjects(objects(array("projects")));
        if (recent.isEmpty()) label("暂无项目", x + 10, worldY + 32, MUTED);
        for (int index = 0; index < Math.min(2, recent.size()); index++) {
            JsonObject record = recent.get(index);
            int rowY = worldY + 25 + index * 23;
            homeIcons.add(new ItemIcon(ProjectHighlighter.projectIcon(stringValue(record, "type")), x + 6, rowY - 2));
            label(font.plainSubstrByWidth(XaeroBridge.projectName(record), mainWidth - 92), x + 32, rowY + 6, projectColor(record));
            button("查看", x + mainWidth - 52, rowY, 42, () -> showRecord("projects", record));
        }
        int linksY = wide ? y + profileHeight + 8 : worldY + worldHeight + 8;
        Button create = button("注册小镇", settingsX, linksY, settingsWidth, () -> leaveTownSettings(this::createTown));
        Button join = button("加入小镇", settingsX, linksY + 26, settingsWidth, () -> leaveTownSettings(this::joinTown));
        Button change = button("切换小镇", settingsX, linksY + 52, settingsWidth, this::chooseTown);
        create.active = gateway.loggedIn(); join.active = gateway.loggedIn(); change.active = gateway.loggedIn() && !gateway.townId.isEmpty();
        int bottom = Math.max(worldY + worldHeight, linksY + 72);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - contentBottom);
    }

    private void platform() {
        boolean navigating = VersionClient.navigation() != null;
        boolean compact = !navigating && contentWidth >= 400;
        int actionWidth = navigating ? (contentWidth - 16) / 3 : compact ? 100 : (contentWidth - 8) / 2;
        int y = contentTop;
        button("打开项目地图", contentLeft, y, actionWidth, this::openMap);
        if (navigating) button("停止导航", contentLeft + actionWidth + 8, y, actionWidth, () -> { VersionClient.stopNavigation(); rebuild(); });
        String[] types = {"全部类型", "建筑", "机器", "其他"};
        button(types[projectType], contentLeft + contentWidth - actionWidth, y, actionWidth, () -> { projectType = (projectType + 1) % types.length; page = 0; rebuild(); });
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
        records.removeIf(record -> !matchesProjectType(record, projectType));
        if (!searchInput.getValue().isBlank()) records.removeIf(record -> !matchesSearch(record, subtitle(record), searchInput.getValue()));
        list(records, y + (compact ? 26 : 52), record -> showRecord(collection, record));
    }

    /** 只搜索可读业务文本，多个关键词需全部匹配。 */
    static boolean matchesSearch(JsonObject record, String related, String query) {
        if (query.isBlank()) return true;
        String text = (XaeroBridge.projectName(record) + " " + stringValue(record, "purpose") + " " + stringValue(record, "notes") + " " + stringValue(record, "dimension") + " " + state(stringValue(record, "status")) + " " + related).toLowerCase(Locale.ROOT);
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) if (!text.contains(word)) return false;
        return true;
    }

    static boolean matchesProjectType(JsonObject record, int type) {
        return type == 0 || stringValue(record, "type").equals(switch (type) { case 1 -> "building"; case 2 -> "machine"; default -> "other"; });
    }

    static List<JsonObject> recentProjects(List<JsonObject> records) {
        List<JsonObject> sorted = new ArrayList<>(records);
        sorted.sort(java.util.Comparator.comparingInt((JsonObject record) -> stringValue(record, "status").equals("maintenance") ? 0 : 1)
                .thenComparing(record -> stringValue(record, "updatedAt", stringValue(record, "createdAt")), java.util.Comparator.reverseOrder()));
        return sorted;
    }

    static int projectColor(JsonObject record) {
        return switch (stringValue(record, "status")) { case "maintenance" -> TownStyle.DANGER; case "planned" -> ACCENT; default -> TEXT; };
    }

    static String singleLine(String value) { return value.replaceAll("\\s+", " ").strip(); }

    private void management() {
        int y = contentTop;
        if (section == 0) {
            list(objects(array("members")), y, this::memberDetail);
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
        if (!validSettingsTown(gateway.snapshot, gateway.townId)) {
            label("小镇资料尚未加载，请稍候。", contentLeft, y + 3, MUTED);
            return;
        }
        boolean synchronize = !settingsSubmitting && settingsSnapshot != gateway.snapshot;
        boolean editable = has("town.manage") && !settingsSubmitting;
        boolean paired = contentWidth >= 240;
        int fullHeight = 20 + (paired ? 2 : 4) * 36 + 50;
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
            if (input == null) { input = new EditBox(font, fieldX, fieldY + 11, inputWidth, 20, Component.literal(title)); input.setMaxLength(key.equals("password") ? 128 : 2048); input.setValue(key.equals("password") ? "" : stringValue(town, key)); townInputs.put(key, input); savedTownValues.put(key, input.getValue()); }
            else if (synchronize && !key.equals("password")) {
                boolean clean = input.getValue().equals(savedTownValues.get(key));
                input.setValue(synchronizedSettingValue(input.getValue(), savedTownValues.get(key), stringValue(town, key)));
                if (clean) savedTownValues.put(key, input.getValue());
            }
            input.setX(fieldX); input.setY(fieldY + 11); input.setWidth(inputWidth); input.setEditable(editable); input.active = editable;
            input.visible = input.getY() >= bodyTop && input.getY() + 20 <= bodyBottom;
            addRenderableWidget(input);
            if (key.equals("logo")) { settingsLogoX = fieldX + inputWidth + 5; settingsLogoY = fieldY + 9; showSettingsLogo = true; }
        }
        if (synchronize) settingsSnapshot = gateway.snapshot;
        int bottom = y + 20 + (paired ? 2 : 4) * 36 + 4;
        int halfWidth = (contentWidth - 8) / 2;
        Button save = button("保存小镇设置", contentLeft, bottom, halfWidth, () -> {
            if (!has("town.manage")) { gateway.status = "没有小镇管理权限"; return; }
            Map<String, String> values = new LinkedHashMap<>();
            townInputs.forEach((key, input) -> values.put(key, input.getValue()));
            JsonObject data = changedTownSettings(values, savedTownValues, gateway.snapshot, gateway.townId);
            if (data.isEmpty()) { gateway.status = "小镇设置没有修改"; return; }
            saveTownSettings(data);
        });
        save.active = editable; save.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;
        Button disable = button(bool(town, "passwordEnabled") ? "关闭加入密码" : "加入密码未开启", contentLeft + halfWidth + 8, bottom, halfWidth, () -> confirm("关闭加入密码", "关闭后，知道小镇 ID 的玩家无需密码即可申请加入。", () -> perform("town.update", object("password", ""))));
        disable.active = editable && bool(town, "passwordEnabled"); disable.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;
        Button listed = button(bool(town, "listed") ? "展示小镇：开启" : "展示小镇：关闭", contentLeft, bottom + 26, halfWidth,
                () -> saveTownSettings(object("listed", !bool(town, "listed"))));
        listed.active = editable; listed.visible = bottom + 26 >= bodyTop && bottom + 46 <= bodyBottom;

    }

    private void saveTownSettings(JsonObject data) {
        if (settingsSubmitting || !has("town.manage")) return;
        settingsSubmitting = true;
        data.addProperty("townId", gateway.townId);
        TownGateway.Context context = gateway.context();
        rebuild();
        gateway.request("town.update", data).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (!sameSettingsScope(stringValue(data, "townId"), context.endpoint(), context.authentication(), gateway.townId, gateway.context())) return;
            settingsSubmitting = false;
            if (failure == null) {
                gateway.status = "小镇设置已保存";
                for (String key : data.keySet()) if (townInputs.containsKey(key)) {
                    String value = key.equals("password") ? "" : stringValue(data, key);
                    townInputs.get(key).setValue(value); savedTownValues.put(key, value);
                }
                settingsSnapshot = gateway.snapshot;
                gateway.refresh();
            }
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
            label(searching ? "没有匹配的记录" : gateway.snapshot.isEmpty() ? "资料尚未加载" : tab == 1 ? "当前周目暂无工程" : "暂无记录", contentLeft + 12, top + 12, TEXT);
            if (emptyHeight >= 45 && searching) label("缩短关键词，或清空搜索查看全部。", contentLeft + 12, top + 30, MUTED);
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * rowStep;
            String title = tab == 1 ? XaeroBridge.projectName(record) : stringValue(record, "name", stringValue(record, "title", "记录"));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            if (record.has("worldKey") && isCurrentWorld(stringValue(record, "id"))) status = "小镇当前世界";
            boolean repair = tab == 1 && List.of("completed", "maintenance").contains(stringValue(record, "status"));
            cards.add(new Card(contentLeft, y, contentWidth, rowStep - 4, title, subtitle, status, repair, record));
            int buttonY = y + (rowStep - 4 - 20) / 2;
            if (repair) {
                Button report = button("报修", contentLeft + contentWidth - 55, buttonY, 48, () -> ticketForm(record));
                report.active = has("ticket.create") && !archived();
            }
            boolean assign = tab == 2 && section == 0 && canAssignMember(record);
            if (assign) button("分配权限组", contentLeft + contentWidth - 79, buttonY, 72, () -> assignMember(record));
            button("详情", contentLeft + contentWidth - (assign ? 133 : repair ? 109 : 55), buttonY, 48, () -> details.accept(record));
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

    private void showRecord(String collection, JsonObject record) {
        List<String> description = describe(record);
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        if (collection.equals("projects")) {
            actions.add(action("查看地图", this::openMap));
            if ((canManageProject(record) || has("ticket.manage") && stringValue(record, "status").equals("maintenance")) && writable) {
                actions.add(action("编辑项目", () -> projectForm(record, objectValue(record, "chunks"))));
            }
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id) && !stringValue(ticket, "status").equals("resolved")) description.add("待修：" + stringValue(ticket, "description", stringValue(ticket, "title")));
            }
        } else if (collection.equals("tickets") && writable && (has("ticket.manage") || canManageProject(project(stringValue(record, "projectId"))))) {
            if (stringValue(record, "status").equals("open")) actions.add(action("开始维护", () -> perform("ticket.update", object("id", id, "status", "working"))));
            if (!stringValue(record, "status").equals("resolved")) actions.add(action("完成维护", () -> form("维护结果", List.of(text("resolution", "处理说明", "")), data -> { data.addProperty("id", id); data.addProperty("status", "resolved"); perform("ticket.update", data); })));
        }
        if (record.has("dimension") && !archived()) {
            actions.add(new TownForm.Action("导航到这里", () -> {
                JsonObject currentNavigation = VersionClient.navigation();
                if (isNavigationTarget(record, currentNavigation)) VersionClient.stopNavigation();
                else if (!VersionClient.navigate(record)) return;
                showRecord(collection, record);
            }, () -> isNavigationTarget(record, VersionClient.navigation()) ? "停止导航" : "导航到这里"));
        }
        TownForm detail = new TownForm(destination(), collection.equals("projects") ? XaeroBridge.projectName(record) : stringValue(record, "name", stringValue(record, "title", "项目详情")), List.of(), description, actions, null);
        if (collection.equals("projects")) detail.withProjectTitle(record);
        VersionClient.show(detail);
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
        boolean maintenanceOnly = editing && !canManageProject(record) && has("ticket.manage") && stringValue(record, "status").equals("maintenance");
        if (archived() || gateway.worldId.isEmpty() || !(editing ? canManageProject(record) || maintenanceOnly : has("project.create"))) { gateway.status = "当前项目只读或没有操作权限"; return; }
        String status = stringValue(record, "status", "planned");
        List<TownForm.Field> fields = maintenanceOnly ? List.of() : List.of(text("name", "项目名", stringValue(record, "name")).compact(), choice("type", "类型", stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{"建筑", "机器", "其他"}), multiline("purpose", "简介", stringValue(record, "purpose")), choice("status", "状态", status, editing ? new String[]{status} : new String[]{"planned", "active", "completed"}, editing ? new String[]{status.equals("maintenance") ? "待维护（由工单控制）" : status.equals("completed") ? "已完工" : state(status)} : new String[]{"计划中", "施工中", "已完工"}));
        String dimension = stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()));
        List<String> details = List.of("选区：" + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + dimension);
        List<TownForm.Action> actions = new ArrayList<>();
        if (editing) {
            String id = stringValue(record, "id");
            if (status.equals("planned")) actions.add(action("开始施工", () -> perform("project.status", object("id", id, "status", "active"))));
            if (status.equals("active")) actions.add(action("标记完工", () -> perform("project.status", object("id", id, "status", "completed"))));
            if (has("project.approve")) actions.add(action("移交负责人", () -> form("移交项目负责人", List.of(memberChoice("leaderId", "新负责人", stringValue(record, "leaderId"))), data -> { data.addProperty("id", id); perform("project.update", data); })));
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id) && !stringValue(ticket, "status").equals("resolved"))
                    actions.add(action("处理报修 · " + singleLine(stringValue(ticket, "title")), () -> showRecord("tickets", ticket)));
            }
            if (!maintenanceOnly) actions.add(action("删除项目", () -> confirm("删除项目", "删除“" + XaeroBridge.projectName(record) + "”及其维修工单，并释放占用区块。此操作不可撤销。", () -> perform("project.delete", object("id", id, "worldId", stringValue(record, "worldId"))))));
        }
        VersionClient.show(new TownForm(destination(), editing ? "编辑项目" : "创建项目", fields, details, actions, maintenanceOnly ? null : data -> {
            if (editing) data.remove("status");
            data.add("chunks", chunks.deepCopy()); data.addProperty("dimension", dimension); data.addProperty("notes", stringValue(record, "notes"));
            if (editing) data.addProperty("id", stringValue(record, "id")); else data.addProperty("worldId", gateway.worldId);
            perform(editing ? "project.update" : "project.create", data);
        }).withGuardedActions());
    }

    private void ticketForm(JsonObject project) {
        JsonObject location = VersionClient.position();
        boolean atProjectWorld = Minecraft.getInstance().player != null && XaeroBridge.isCurrentWorld() && stringValue(location, "dimension").equals(stringValue(project, "dimension"));
        if (!atProjectWorld) {
            JsonObject chunks = objectValue(project, "chunks");
            location = object("dimension", stringValue(project, "dimension"), "x", (integer(chunks, "minX") + integer(chunks, "maxX") + 1) * 8, "y", 64, "z", (integer(chunks, "minZ") + integer(chunks, "maxZ") + 1) * 8);
        }
        JsonObject position = location;
        List<TownForm.Field> fields = List.of(multiline("description", "内容说明", ""));
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
        TownForm join = new TownForm(destination(), "加入小镇", List.of(text("townId", "八位数字 ID", "").compact(), text("password", "小镇密码", "").compact()), List.of("正在加载展示小镇…"), List.of(), data -> perform("town.join", data));
        VersionClient.show(join);
        TownGateway.Context context = gateway.context();
        gateway.request("town.discover", object()).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (!gateway.isCurrent(context) || VersionClient.currentScreen() != join) return;
            if (failure != null) { join.updateContent(List.of("小镇列表加载失败，可直接填写 ID 加入。"), List.of()); return; }
            List<TownForm.Action> towns = new ArrayList<>();
            for (JsonElement element : arrayValue(result, "items")) {
                JsonObject town = element.getAsJsonObject();
                towns.add(action(stringValue(town, "name") + " · " + stringValue(town, "id"), () -> {
                    VersionClient.show(new TownForm(join, "加入 " + stringValue(town, "name"),
                            List.of(text("townId", "八位数字 ID", stringValue(town, "id")).compact(), text("password", "小镇密码", "").compact()),
                            List.of(stringValue(town, "introduction"), integer(town, "memberCount") + " 位成员 · " + stringValue(town, "server"), bool(town, "passwordEnabled") ? "需要小镇密码" : "无需密码"), List.of(), data -> perform("town.join", data)));
                }));
            }
            join.updateContent(List.of(towns.isEmpty() ? "暂无展示小镇" : "展示小镇"), towns);
        }));
    }
    private void chooseTown() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : gateway.towns) {
            JsonObject town = element.getAsJsonObject();
            actions.add(action(stringValue(town, "name") + " · " + stringValue(town, "id"), () -> {
                if (gateway.townId.equals(stringValue(town, "id"))) { VersionClient.show(this); return; }
                leaveTownSettings(() -> { gateway.selectTown(stringValue(town, "id")); homeScroll = 0; tab = 0; VersionClient.show(this); });
            }));
        }
        VersionClient.show(new TownForm(this, "选择当前小镇", List.of(), List.of("加入多个小镇后，可随时切换。"), actions, null));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        JsonObject current = currentWorld();
        if (!current.isEmpty()) actions.add(action("返回当前世界 · " + stringValue(current, "name"), () -> { gateway.followCurrentWorld(); VersionClient.show(destination()); }));
        for (JsonElement element : array("worlds")) {
            JsonObject world = element.getAsJsonObject();
            actions.add(action(stringValue(world, "name") + (isCurrentWorld(stringValue(world, "id")) ? " · 当前" : bool(world, "archived") ? " · 历史" : " · 未启用"), () -> worldDetail(world)));
        }
        if (actions.isEmpty() && has("worlds.manage")) actions.add(action("登记新世界 / 周目", this::worldForm));
        VersionClient.show(new TownForm(destination(), "当前世界与历史", List.of(), List.of("镇长统一指定当前世界，成员自动跟随。历史资料可单独浏览。"), actions, null));
    }
    private void memberDetail(JsonObject member) {
        String id = stringValue(member, "playerId");
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("复制玩家 UUID", () -> { VersionClient.copy(id); gateway.status = "已复制 UUID"; }));
        if (has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")))) {
            actions.add(action("移出小镇", () -> confirm("移出成员", "将 " + stringValue(member, "name") + " 移出小镇。", () -> perform("member.remove", object("playerId", id)))));
        }
        if (isMayor() && !id.equals(playerId())) actions.add(action("移交镇长职位", () -> confirm("移交镇长", "将唯一镇长职位交给 " + stringValue(member, "name") + "，你的权限将改变。", () -> perform("town.transfer", object("playerId", id)))));
        VersionClient.show(new TownForm(this, stringValue(member, "name"), List.of(), List.of("权限组：" + roleName(stringValue(member, "roleId")), "UUID：" + id, "加入时间：" + stringValue(member, "joinedAt")), actions, null));
    }
    private boolean canAssignMember(JsonObject member) {
        return has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")));
    }
    private void assignMember(JsonObject member) {
        if (!canAssignMember(member)) return;
        form("分配权限组 · " + stringValue(member, "name"), List.of(roleChoice(stringValue(member, "roleId"))), data -> {
            data.addProperty("playerId", stringValue(member, "playerId")); perform("member.role", data);
        });
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
        boolean current = isCurrentWorld(stringValue(world, "id"));
        actions.add(action(current ? "返回小镇当前世界" : "浏览此周目资料", () -> {
            if (current) gateway.followCurrentWorld(); else gateway.selectWorld(stringValue(world, "id"));
            VersionClient.show(destination());
        }));
        if (isMayor() && !bool(world, "archived")) actions.add(action("将所在地图设为小镇当前世界", () -> activateWorld(world)));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action("编辑世界名称、服务器与地图标识", () -> worldForm(world)));
        if (bool(world, "archived") && !XaeroBridge.currentMapKey().isBlank() && stringValue(world, "id").equals(gateway.worldId))
            actions.add(action("关联历史底图", () -> bindWorldMap(world)));
        String viewedDimension = XaeroBridge.currentMapDimension();
        if (bool(world, "archived") && stringValue(world, "id").equals(gateway.worldId) && !gateway.getBoundMapKey(viewedDimension).isEmpty())
            actions.add(action("清除本机旧地图关联", () -> confirm("清除本机旧地图关联", "仅清除本机当前维度保存的关联，项目与 Xaero 地形保留。",
                    () -> { gateway.unbindMap(viewedDimension); VersionClient.show(destination()); })));
        if (has("worlds.manage") && !bool(world, "archived") && !current) actions.add(action("归档此世界 / 周目", () -> confirm("归档周目", "归档后业务资料只读，个人 Xaero 地图保留。", () -> perform("world.archive", object("worldId", stringValue(world, "id"))))));
        List<String> details = new ArrayList<>(List.of("服务器：" + stringValue(world, "serverAddress"), "世界标识：" + stringValue(world, "worldKey"), "状态：" + (current ? "小镇当前世界" : bool(world, "archived") ? "历史归档 · 只读" : "尚未设为当前世界")));
        if (bool(world, "archived")) {
            if (stringValue(world, "id").equals(gateway.worldId)) {
                String bound = gateway.getBoundMapKey(viewedDimension);
                details.add("历史底图：" + (bound.isBlank() ? "尚未关联，请在 Xaero 选择历史地图后关联" : bound));
            } else details.add("先浏览此周目，再关联本机保存的历史底图。");
        } else {
            String mapName = stringValue(objectValue(world, "mapNames"), viewedDimension, stringValue(world, "mapName"));
            details.add("小镇地图名称：" + (mapName.isBlank() ? "等待镇长设置" : mapName));
            details.add("各维度自动使用同名地图，成员保留自己探索的地形。");
            if (current && !mapName.isBlank() && !mapName.equals(XaeroBridge.currentMapName()))
                details.add("地图名称与小镇当前世界不一致，请在 Xaero 恢复地图名称或联系镇长更新。");
        }
        VersionClient.show(new TownForm(destination(), stringValue(world, "name"), List.of(), details, actions, null));
    }
    private void activateWorld(JsonObject world) {
        if (!isMayor() || bool(world, "archived")) { gateway.status = "只有镇长可以设置未归档的当前世界"; return; }
        String reason = XaeroBridge.currentMapSetupBlockReason();
        String mapName = XaeroBridge.currentMapName();
        if (reason.isEmpty() && mapName.isBlank()) reason = "当前 Xaero 地图尚未命名，请先设置地图名称";
        if (!reason.isEmpty()) {
            gateway.status = reason;
            VersionClient.show(new TownForm(destination(), "暂时无法设置当前世界", List.of(), List.of(reason), List.of(action("重试设置", () -> activateWorld(world))), null));
            return;
        }
        perform("world.activate", object("worldId", stringValue(world, "id"), "serverAddress", VersionClient.serverAddress(),
                "mapName", mapName, "dimension", XaeroBridge.currentMapDimension()));
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
            gateway.status = "";
            if (action.equals("project.delete") && isNavigationTarget(data, VersionClient.navigation())) VersionClient.stopNavigation();
            if (selectTown) {
                gateway.refreshTowns();
                if (VersionClient.currentScreen() == origin) { gateway.selectTown(stringValue(result, "townId")); tab = 0; section = 0; page = 0; }
            } else if (action.equals("world.activate")) gateway.followCurrentWorld();
            else gateway.refresh();
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
        return ProjectHighlighter.textLines(text, Math.max(12, width), font);
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
    private boolean isCurrentWorld(String id) { return !id.isEmpty() && id.equals(stringValue(objectValue(gateway.snapshot, "town"), "currentWorldId")); }
    private JsonObject currentWorld() { for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); if (isCurrentWorld(stringValue(world, "id"))) return world; } return new JsonObject(); }
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
        if (record.has("purpose")) return (stringValue(record, "purpose").isBlank() ? "" : singleLine(stringValue(record, "purpose")) + " · ") + memberName(stringValue(record, "leaderId"));
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
        int headingX = contentLeft - 6;
        TownStyle.heading(graphics, font, TownStyle.title(), headingX, window.top() + 10, contentWidth - 36);
        Component hoveredText = null;
        graphics.enableScissor(contentLeft, bodyTop, contentLeft + contentWidth, bodyBottom);
        for (Panel panel : panels) TownStyle.panel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), panel.accented());
        for (Card card : cards) {
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), card.height(), false);
            boolean projectRow = tab == 1;
            int textOffset = projectRow ? 30 : 38;
            if (projectRow) VersionClient.drawLogo(graphics, ProjectHighlighter.projectIcon(stringValue(card.record(), "type")), card.x() + 7, card.y() + 5, 20);
            if (!projectRow) {
                TownStyle.panel(graphics, card.x() + 7, card.y() + 10, 23, 23, false);
                if (tab == 2 && section == 0) VersionClient.drawAvatar(graphics, stringValue(card.record(), "playerId"), stringValue(card.record(), "name"), card.x() + 9, card.y() + 12, 20);
                else if (tab == 2 && section == 1) VersionClient.drawLogo(graphics, roleIcon(card.record()), card.x() + 8, card.y() + 10, 24);
                else TownStyle.icon(graphics, 3, card.x() + 15, card.y() + 18, ACCENT);
            }
            int buttonSpace = tab == 2 && section == 0 && canAssignMember(card.record()) ? 144 : card.repair() ? 120 : 66;
            int titleY = card.y() + 9;
            int badgeWidth = card.state().isEmpty() ? 0 : Math.min(79, font.width(card.state()) + 12);
            int textWidth = Math.max(0, card.width() - textOffset - buttonSpace - (badgeWidth > 0 ? badgeWidth + 8 : 0));
            graphics.text(font, font.plainSubstrByWidth(card.title(), textWidth), card.x() + textOffset, titleY, projectRow ? projectColor(card.record()) : TEXT, true);
            graphics.text(font, font.plainSubstrByWidth(card.subtitle(), textWidth), card.x() + textOffset, card.y() + 26, MUTED, false);
            if ((font.width(card.title()) > textWidth || font.width(card.subtitle()) > textWidth)
                    && mouseX >= card.x() + textOffset && mouseX < card.x() + textOffset + textWidth && mouseY >= titleY - 3 && mouseY < card.y() + 38 && mouseY >= bodyTop && mouseY < bodyBottom)
                hoveredText = Component.literal(card.title() + (card.subtitle().isEmpty() ? "" : "\n" + card.subtitle()));
            if (badgeWidth > 0) {
                int badgeX = card.x() + card.width() - buttonSpace - badgeWidth;
                int badgeY = card.y() + (card.height() - 14) / 2;
                graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 14, TownStyle.RAISED);
                graphics.text(font, font.plainSubstrByWidth(card.state(), badgeWidth - 12), badgeX + 6, badgeY + 3, ACCENT, false);
            }
        }
        for (Line line : lines) graphics.text(font, font.plainSubstrByWidth(line.text(), contentLeft + contentWidth - line.x() - 8), line.x(), line.y(), line.color(), false);
        if (tab == 0) {
            for (ItemIcon icon : homeIcons) VersionClient.drawLogo(graphics, icon.item(), icon.x(), icon.y(), 24);
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
        TownStyle.feedback(graphics, font, gateway.status, contentLeft, window.bottom() - 26 + (20 - font.lineHeight) / 2,
                contentWidth, TownStyle.FRAME_MUTED, mouseX, mouseY);
    }
}
