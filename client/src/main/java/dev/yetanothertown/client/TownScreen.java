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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static dev.yetanothertown.client.TownForm.Field.*;
import static dev.yetanothertown.client.TownGateway.object;

/** 小镇三入口与所有协作、审核、管理操作。 */
public final class TownScreen extends Screen {
    private static final DateTimeFormatter JOINED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final int TEXT = TownStyle.TEXT;
    static final int MUTED = TownStyle.MUTED;
    static final int ACCENT = TownStyle.ACCENT;
    private static String[] managementLabels() { return new String[] {TownText.text("yat.tab.members"), TownText.text("yat.tab.roles"), TownText.text("yat.tab.worlds_seasons"), TownText.text("yat.title.town_settings")}; }
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
    private String previousConnectionStatus = "";
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
    private boolean mutationPending;
    private record Panel(int x, int y, int width, int height, boolean accented) { }
    private record Line(String text, int x, int y, int color) { }
    private record Card(int x, int y, int width, int height, String title, String subtitle, String state, String joined, boolean repair, JsonObject record) { }

    public TownScreen() { this(null); }
    public TownScreen(Screen parent) { super(Component.literal(TownText.text("yat.title.main"))); this.parent = parent; }

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
        if (!current.isEmpty()) actions.add(controller.action(TownText.text("yat.ui.return_current_world"), () -> {
            controller.gateway.followCurrentWorld();
            VersionClient.show(parent);
        }));
        if (controller.isMayor()) {
            if (!current.isEmpty()) actions.add(controller.action(TownText.text("yat.ui.use_current_map"), () -> controller.activateWorld(current)));
            else actions.add(controller.action(TownText.text("yat.ui.select_current_world"), controller::chooseWorld));
            if (controller.has("worlds.manage")) actions.add(controller.action(TownText.text("yat.ui.register_world"), controller::worldForm));
        }
        String expectedName = stringValue(objectValue(current, "mapNames"), XaeroBridge.currentMapDimension(), stringValue(current, "mapName"));
        String explanation = current.isEmpty() || expectedName.isBlank()
                ? TownText.text("yat.hint.set_current_map")
                : TownText.text("yat.label.current_world") + TownText.recordName(current, "") + TownText.text("yat.detail.server") + stringValue(current, "serverAddress")
                    + TownText.text("yat.detail.town_map") + expectedName + TownText.text("yat.detail.local_map") + XaeroBridge.currentMapName()
                    + TownText.text("yat.hint.map_auto_match");
        VersionClient.show(new TownForm(parent, TownText.text("yat.title.current_map"), List.of(), List.of(explanation), actions, null));
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
        if (settingsSubmitting) { gateway.status = TownText.text("yat.status.saving_town"); return; }
        if (dirtyTownSettings()) {
            VersionClient.show(new TownForm(this, TownText.text("yat.title.discard_town_settings"), List.of(), List.of(TownText.text("yat.confirm.discard_town_settings")),
                    List.of(action(TownText.text("yat.ui.discard_continue"), () -> { resetTownSettings(); action.run(); })), null));
        } else action.run();
    }
    @Override public void onClose() { leaveTownSettings(() -> VersionClient.show(parent)); }
    @Override public void tick() {
        if (searchChanged || previousSnapshot != gateway.snapshot || previousTowns != gateway.towns || previousNavigation != VersionClient.navigation()
                || !gateway.loggedIn() && !previousConnectionStatus.equals(gateway.status)) rebuild();
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
        previousConnectionStatus = gateway.status;
        previousNavigation = VersionClient.navigation();
        window = TownStyle.bounds(width, height, 560, 322);
        contentLeft = window.left() + 12;
        contentWidth = window.width() - 24;
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 10;
        contentBottom = bodyBottom - 6;
        String[] tabs = {TownText.text("yat.tab.home"), TownText.text("yat.tab.projects"), TownText.text("yat.tab.manage")};
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
            String[] sections = managementLabels();
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
        avatarX = profileX + 10; avatarY = TownStyle.centeredY(y + 13, 15 + font.lineHeight, 28);
        int nameWidth = wide ? sideWidth - 57 : Math.max(20, sideWidth - 147);
        label(font.plainSubstrByWidth(VersionClient.playerName(), nameWidth), profileX + 47, y + 13, TEXT);
        label(font.plainSubstrByWidth(gateway.loggedIn() ? TownText.text("yat.status.identity_ready") : TownText.text("yat.status.awaiting_connection"), nameWidth), profileX + 47, y + 28, gateway.loggedIn() ? TownStyle.SUCCESS : MUTED);
        JsonObject town = objectValue(gateway.snapshot, "town");
        String townName = stringValue(town, "name", gateway.townId.isEmpty() ? TownText.text("yat.empty.membership") : TownText.text("yat.status.loading_town"));
        label(font.plainSubstrByWidth(TownText.text("yat.label.member_town") + townName, sideWidth - 20), profileX + 10, y + 49, MUTED);
        int settingsX = wide ? profileX + 10 : profileX + sideWidth - 90;
        int settingsWidth = wide ? sideWidth - 20 : 80;
        button(TownText.text("yat.title.client_settings"), settingsX, wide ? y + 73 : y + 18, settingsWidth, this::connectionForm);
        int heroY = wide ? y : y + profileHeight + 8;
        int archiveWidth = mainWidth;
        int homeActionWidth = (mainWidth - 36) / 3;
        int heroHeight = 148;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 12; logoY = heroY + 12;
        int titleX = x + (showLogo ? 55 : 12);
        label(TownText.text("yat.title.town_profile") + (gateway.townId.isEmpty() ? "" : " · ID " + gateway.townId), titleX, heroY + 14, ACCENT);
        label(font.plainSubstrByWidth(gateway.townId.isEmpty() ? TownText.text("yat.home.welcome") : townName, archiveWidth - (titleX - x) - 10), titleX, heroY + 31, TEXT);
        if (!gateway.loggedIn()) {
            List<String> connection = wrap(gateway.status, archiveWidth - 24);
            for (int index = 0; index < Math.min(3, connection.size()); index++) label(connection.get(index), x + 12, heroY + 70 + index * 13, MUTED);
            button(TownText.text("yat.ui.reconnect"), x + 12, heroY + 116, archiveWidth - 24, () -> VersionClient.connectIdentity(this::rebuild));
        } else if (gateway.townId.isEmpty()) {
            paragraph(TownText.text("yat.home.join_hint"), x + 12, heroY + 70, archiveWidth - 24, MUTED);
        } else {
            List<String> introduction = wrap(stringValue(town, "introduction", TownText.text("yat.home.tagline")), archiveWidth - 24);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 12, heroY + 54 + index * 12, TEXT);
            int membersY = heroY + 54 + Math.min(2, introduction.size()) * 12 + 3;
            label(font.plainSubstrByWidth(TownText.text("yat.label.members_server", array("members").size(), stringValue(town, "server", VersionClient.serverAddress())), archiveWidth - 24), x + 12, membersY, MUTED);
            long maintenance = objects(array("projects")).stream().filter(item -> stringValue(item, "status").equals("maintenance")).count();
            int statisticsY = membersY + 16;
            String projectCount = TownText.text("yat.label.project_count", array("projects").size());
            int maintenanceX = x + 36 + font.width(projectCount) + 14;
            homeIcons.add(new ItemIcon("minecraft:quartz", x + 16, TownStyle.centeredY(statisticsY + 2, font.lineHeight, 16)));
            homeIcons.add(new ItemIcon("minecraft:redstone", maintenanceX + 4, TownStyle.centeredY(statisticsY + 2, font.lineHeight, 16)));
            label(projectCount, x + 36, statisticsY + 2, TEXT);
            label(TownText.text("yat.label.repair_count", maintenance), maintenanceX + 24, statisticsY + 2, maintenance > 0 ? TownStyle.DANGER : TownStyle.SUCCESS);
            button(TownText.text("yat.ui.project_map"), x + 12, heroY + 116, homeActionWidth, this::openMap);
            button(TownText.text("yat.ui.member_permissions"), x + 18 + homeActionWidth, heroY + 116, homeActionWidth, () -> { tab = 2; section = 0; rebuild(); });
            button(TownText.text("yat.ui.town_worlds"), x + 24 + homeActionWidth * 2, heroY + 116, homeActionWidth, this::chooseWorld);
        }
        int worldY = heroY + heroHeight + 8;
        int worldHeight = 96;
        panels.add(new Panel(x, worldY, mainWidth, worldHeight, false));
        label(TownText.text("yat.title.recent_projects"), x + 10, worldY + 9, ACCENT);
        List<JsonObject> recent = recentProjects(objects(array("projects")));
        if (recent.isEmpty()) label(TownText.text("yat.empty.projects"), x + 10, worldY + 32, MUTED);
        for (int index = 0; index < Math.min(2, recent.size()); index++) {
            JsonObject record = recent.get(index);
            int rowY = worldY + 25 + index * 34;
            homeIcons.add(new ItemIcon(ProjectHighlighter.projectIcon(stringValue(record, "type")), x + 10, TownStyle.centeredY(rowY + 3, font.lineHeight, 16)));
            label(font.plainSubstrByWidth(XaeroBridge.projectName(record), mainWidth - 92), x + 32, rowY + 3, projectColor(record));
            String summary = stringValue(record, "status").equals("maintenance")
                    ? String.join("；", objects(array("tickets")).stream().filter(ticket -> stringValue(ticket, "projectId").equals(stringValue(record, "id")) && !stringValue(ticket, "status").equals("resolved"))
                            .map(ticket -> stringValue(ticket, "description").isBlank() ? stringValue(ticket, "title") : stringValue(ticket, "description")).toList())
                    : stringValue(record, "purpose");
            label(font.plainSubstrByWidth(singleLine(summary), mainWidth - 76), x + 12, rowY + 18, MUTED);
            button(TownText.text("yat.ui.view"), x + mainWidth - 52, rowY + 6, 42, () -> showRecord("projects", record)).setTooltip(Tooltip.create(Component.literal(summary)));
        }
        int linksY = wide ? y + profileHeight + 8 : worldY + worldHeight + 8;
        Button create = button(TownText.text("yat.ui.create_town"), settingsX, linksY, settingsWidth, () -> leaveTownSettings(this::createTown));
        Button join = button(TownText.text("yat.ui.join_town"), settingsX, linksY + 26, settingsWidth, () -> leaveTownSettings(this::joinTown));
        Button change = button(TownText.text("yat.ui.switch_town"), settingsX, linksY + 52, settingsWidth, this::chooseTown);
        create.active = gateway.loggedIn(); join.active = gateway.loggedIn(); change.active = gateway.loggedIn() && !gateway.townId.isEmpty();
        int bottom = Math.max(worldY + worldHeight, linksY + 72);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - contentBottom);
    }

    private void platform() {
        boolean navigating = VersionClient.navigation() != null;
        boolean compact = !navigating && contentWidth >= 400;
        int actionWidth = navigating ? (contentWidth - 16) / 3 : compact ? 100 : (contentWidth - 8) / 2;
        int y = contentTop;
        button(TownText.text("yat.ui.open_project_map"), contentLeft, y, actionWidth, this::openMap);
        if (navigating) button(TownText.text("yat.ui.stop_navigation"), contentLeft + actionWidth + 8, y, actionWidth, () -> { VersionClient.stopNavigation(); rebuild(); });
        String[] types = {TownText.text("yat.filter.all_types"), TownText.text("yat.project.type.building"), TownText.text("yat.project.type.machine"), TownText.text("yat.project.type.other")};
        button(types[projectType], contentLeft + contentWidth - actionWidth, y, actionWidth, () -> { projectType = (projectType + 1) % types.length; page = 0; rebuild(); });
        int searchLeft = compact ? contentLeft + actionWidth + 6 : contentLeft;
        int searchTop = compact ? y : y + 26;
        int searchWidth = compact ? contentWidth - 2 * (actionWidth + 6) : contentWidth;
        if (searchInput == null) {
            searchInput = new EditBox(font, searchLeft, searchTop, searchWidth - 26, 20, Component.literal(TownText.text("yat.ui.search")));
            searchInput.setMaxLength(100);
            searchInput.setResponder(value -> { page = 0; searchChanged = true; });
        }
        searchInput.setX(searchLeft); searchInput.setY(searchTop); searchInput.setWidth(searchWidth - 26);
        String hint = TownText.text("yat.hint.search_projects");
        searchInput.setHint(Component.literal(hint));
        searchInput.setTooltip(Tooltip.create(Component.literal(hint + TownText.text("yat.hint.search_keywords"))));
        addRenderableWidget(searchInput);
        Button clear = button("×", searchLeft + searchWidth - 20, searchTop, 20, () -> { searchInput.setValue(""); pendingSearchFocus = true; });
        clear.setTooltip(Tooltip.create(Component.literal(TownText.text("yat.ui.clear_search"))));
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
            list(objects(array("members")), y, null);
        } else if (section == 1) {
            Button create = button(TownText.text("yat.ui.create_role"), contentLeft, y, 125, () -> roleForm(new JsonObject()));
            create.active = has("roles.manage");
            list(objects(array("roles")), y + 28, this::roleDetail);
        } else if (section == 2) {
            Button create = button(TownText.text("yat.ui.add_world"), contentLeft, y, 170, this::worldForm);
            create.active = has("worlds.manage");
            list(objects(array("worlds")), y + 28, this::worldDetail);
        } else inlineTownSettings(y);
    }

    /** 设置直接编辑，空密码保持现状，关闭密码需要单独确认。 */
    private void inlineTownSettings(int y) {
        JsonObject town = objectValue(gateway.snapshot, "town");
        if (!validSettingsTown(gateway.snapshot, gateway.townId)) {
            label(TownText.text("yat.error.town_not_loaded"), contentLeft, y + 3, MUTED);
            return;
        }
        boolean synchronize = !settingsSubmitting && settingsSnapshot != gateway.snapshot;
        boolean editable = has("town.manage") && !settingsSubmitting;
        boolean paired = contentWidth >= 240;
        int fullHeight = 20 + (paired ? 2 : 4) * 36 + 50;
        settingsScroll = Math.max(0, Math.min(settingsScroll, y + fullHeight - contentBottom));
        y -= settingsScroll;
        label(TownText.text("yat.label.town_id") + gateway.townId, contentLeft, y + 3, MUTED);
        String[][] definitions = {{"name", TownText.text("yat.field.name")}, {"introduction", TownText.text("yat.field.description")}, {"logo", TownText.text("yat.field.logo")}, {"password", TownText.text("yat.field.new_password")}};
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
        Button save = button(TownText.text("yat.ui.save_town_settings"), contentLeft, bottom, halfWidth, () -> {
            if (!has("town.manage")) { gateway.status = TownText.text("yat.error.manage_town_permission"); return; }
            Map<String, String> values = new LinkedHashMap<>();
            townInputs.forEach((key, input) -> values.put(key, input.getValue()));
            JsonObject data = changedTownSettings(values, savedTownValues, gateway.snapshot, gateway.townId);
            if (data.isEmpty()) { gateway.status = TownText.text("yat.status.town_unchanged"); return; }
            saveTownSettings(data);
        });
        save.active = editable; save.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;
        Button disable = button(bool(town, "passwordEnabled") ? TownText.text("yat.ui.disable_join_password") : TownText.text("yat.status.join_password_disabled"), contentLeft + halfWidth + 8, bottom, halfWidth, () -> confirm(TownText.text("yat.ui.disable_join_password"), TownText.text("yat.confirm.disable_join_password"), () -> perform("town.update", object("password", ""))));
        disable.active = editable && bool(town, "passwordEnabled"); disable.visible = bottom >= bodyTop && bottom + 20 <= bodyBottom;
        Button listed = button(bool(town, "listed") ? TownText.text("yat.ui.town_public_on") : TownText.text("yat.ui.town_public_off"), contentLeft, bottom + 26, halfWidth,
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
                gateway.status = TownText.text("yat.status.town_saved");
                for (String key : data.keySet()) if (townInputs.containsKey(key)) {
                    String value = key.equals("password") ? "" : stringValue(data, key);
                    townInputs.get(key).setValue(value); savedTownValues.put(key, value);
                }
                settingsSnapshot = gateway.snapshot;
                gateway.refresh();
            }
            if (VersionClient.currentScreen() == this) {
                rebuild();
                if (failure != null) TownForm.showError(this, gateway.status);
            }
        }));
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details) {
        boolean members = tab == 2 && section == 0;
        int rowStep = members ? 44 : tab == 2 && section == 1 ? 44 : 48;
        int available = Math.max(0, (contentBottom - top - 24 + 4) / rowStep);
        if (tab == 2 && section == 1) available = Math.min(4, available);
        if (available == 0) {
            if (contentBottom - top >= 14) label(TownText.text("yat.hint.reduce_gui_scale"), contentLeft + 4, top + 3, MUTED);
            return;
        }
        int pages = Math.max(1, (records.size() + available - 1) / available);
        page = Math.max(0, Math.min(page, pages - 1));
        if (records.isEmpty()) {
            int emptyHeight = Math.min(70, contentBottom - top - 24);
            panels.add(new Panel(contentLeft, top, contentWidth, emptyHeight, false));
            boolean searching = tab == 1 && searchInput != null && !searchInput.getValue().isBlank();
            label(searching ? TownText.text("yat.empty.search_results") : gateway.snapshot.isEmpty() ? TownText.text("yat.empty.data_loading") : tab == 1 ? TownText.text("yat.empty.season_projects") : TownText.text("yat.empty.records"), contentLeft + 12, top + 12, TEXT);
            if (emptyHeight >= 45 && searching) label(TownText.text("yat.hint.no_search_results"), contentLeft + 12, top + 30, MUTED);
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * rowStep;
            String title = tab == 1 ? XaeroBridge.projectName(record) : TownText.recordName(record, stringValue(record, "title", TownText.text("yat.label.record")));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            if (record.has("worldKey") && isCurrentWorld(stringValue(record, "id"))) status = TownText.text("yat.title.current_world");
            boolean repair = tab == 1 && List.of("completed", "maintenance").contains(stringValue(record, "status"));
            cards.add(new Card(contentLeft, y, contentWidth, rowStep - 4, title, subtitle, status,
                    members ? joinedTime(stringValue(record, "joinedAt"), ZoneId.systemDefault()) : "", repair, record));
            if (members) {
                int actionY = y + rowStep - 27;
                int actionX = contentLeft + contentWidth - 7 - memberActionsWidth(record);
                if (canAssignMember(record)) {
                    button(TownText.text("yat.ui.assign_role"), actionX, actionY, 72, () -> assignMember(record));
                    button(TownText.text("yat.ui.remove_from_town"), actionX + 76, actionY, 48, () -> confirm(TownText.text("yat.title.remove_member"), TownText.text("yat.confirm.remove_member", stringValue(record, "name")), () -> perform("member.remove", object("playerId", stringValue(record, "playerId")))));
                    actionX += 128;
                }
                if (canTransferMayor(record)) button(TownText.text("yat.ui.transfer_mayor"), actionX, actionY, 60, () -> confirm(TownText.text("yat.ui.transfer_mayor"), TownText.text("yat.confirm.transfer_mayor", stringValue(record, "name")), () -> perform("town.transfer", object("playerId", stringValue(record, "playerId")))));
                continue;
            }
            int buttonY = y + (rowStep - 4 - 20) / 2;
            if (repair) {
                boolean maintenance = stringValue(record, "status").equals("maintenance");
                Button report = button(maintenance ? TownText.text("yat.ui.complete") : TownText.text("yat.ui.report_repair"), contentLeft + contentWidth - 55, buttonY, 48,
                        () -> { if (maintenance) perform("project.repair.complete", object("id", stringValue(record, "id"))); else ticketForm(record); });
                report.active = !archived() && (maintenance ? canManageProject(record) || has("ticket.manage") : has("ticket.create"));
            }
            button(TownText.text("yat.ui.details"), contentLeft + contentWidth - (repair ? 109 : 55), buttonY, 48, () -> details.accept(record));
        }
        int footer = contentBottom - 20;
        if (pages > 1) {
            Button previous = button("‹", contentLeft, footer, 24, () -> { page--; rebuild(); });
            Button next = button("›", contentLeft + 100, footer, 24, () -> { page++; rebuild(); });
            previous.active = page > 0;
            next.active = page < pages - 1;
            label((page + 1) + " / " + pages, contentLeft + 36, TownStyle.centeredY(footer, 20, font.lineHeight), MUTED);
        }
        boolean history = tab == 1 && archived();
        if (history) label(TownText.text("yat.label.historic_season"), contentLeft + contentWidth - 96, TownStyle.centeredY(footer, 20, font.lineHeight), ACCENT);
    }

    private void openMap() {
        if (externalParent && returnScreen != null) { VersionClient.show(returnScreen); return; }
        if (!XaeroBridge.openMap(destination())) VersionClient.show(new TownMapScreen(destination()));
    }

    private void showRecord(String collection, JsonObject record) {
        List<String> description = collection.equals("projects") ? new ArrayList<>() : describe(record);
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        if (collection.equals("projects")) {
            actions.add(action(TownText.text("yat.ui.view_map"), this::openMap));
            if ((canManageProject(record) || has("ticket.manage") && stringValue(record, "status").equals("maintenance")) && writable) {
                actions.add(action(TownText.text("yat.ui.edit_project"), () -> projectForm(record, objectValue(record, "chunks"))));
            }
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id) && !stringValue(ticket, "status").equals("resolved")) description.add(TownText.text("yat.label.repair_needed") + stringValue(ticket, "description", stringValue(ticket, "title")));
            }
            if (!stringValue(record, "notes").isBlank()) description.add(stringValue(record, "notes"));
            JsonObject chunks = objectValue(record, "chunks");
            description.add(TownText.text("yat.label.chunk") + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + stringValue(record, "dimension"));
            if (!arrayValue(record, "overlapIds").isEmpty()) description.add(TownText.text("yat.label.overlaps", arrayValue(record, "overlapIds").size()));
        } else if (collection.equals("tickets") && writable && (has("ticket.manage") || canManageProject(project(stringValue(record, "projectId"))))) {
            if (stringValue(record, "status").equals("open")) actions.add(action(TownText.text("yat.ui.start_repair"), () -> perform("ticket.update", object("id", id, "status", "working"))));
            if (!stringValue(record, "status").equals("resolved")) actions.add(action(TownText.text("yat.ui.finish_repair"), () -> form(TownText.text("yat.field.repair_result"), List.of(text("resolution", TownText.text("yat.field.resolution_details"), "")), data -> { data.addProperty("id", id); data.addProperty("status", "resolved"); perform("ticket.update", data); })));
        }
        if (record.has("dimension") && !archived()) {
            actions.add(new TownForm.Action(TownText.text("yat.ui.navigate_here"), () -> {
                JsonObject currentNavigation = VersionClient.navigation();
                if (isNavigationTarget(record, currentNavigation)) VersionClient.stopNavigation();
                else if (!VersionClient.navigate(record)) throw new IllegalStateException(gateway.status);
                showRecord(collection, record);
            }, () -> isNavigationTarget(record, VersionClient.navigation()) ? TownText.text("yat.ui.stop_navigation") : TownText.text("yat.ui.navigate_here")));
        }
        TownForm detail = new TownForm(destination(), collection.equals("projects") ? XaeroBridge.projectName(record) : TownText.recordName(record, stringValue(record, "title", TownText.text("yat.title.project_details"))), List.of(), description, actions, null);
        if (collection.equals("projects")) detail.withProjectTitle(record, memberName(stringValue(record, "leaderId")));
        VersionClient.show(detail);
    }

    private List<String> describe(JsonObject record) {
        List<String> result = new ArrayList<>();
        if (record.has("status") && !stringValue(record, "status").equals("completed")) result.add(TownText.text("yat.label.status") + state(stringValue(record, "status")));
        String[][] labels = {{"purpose", TownText.text("yat.field.summary")}, {"description", TownText.text("yat.field.details")}, {"notes", TownText.text("yat.field.notes")}, {"resolution", TownText.text("yat.field.resolution")}};
        for (String[] label : labels) if (!stringValue(record, label[0]).isBlank()) {
            String value = label[0].equals("resolution") && stringValue(record, "resolutionKey").equals("yat.default.repair_completed") ? TownText.text("yat.default.repair_completed") : stringValue(record, label[0]);
            result.add(label[1] + ": " + value);
        }
        if (record.has("leaderId")) result.add(TownText.text("yat.label.owner") + memberName(stringValue(record, "leaderId")));
        if (record.has("projectId") && !stringValue(record, "projectId").isBlank()) result.add(TownText.text("yat.label.project") + projectName(stringValue(record, "projectId")));
        if (record.has("chunks")) { JsonObject chunks = objectValue(record, "chunks"); result.add(TownText.text("yat.label.chunk_coordinates") + stringValue(chunks, "minX") + ", " + stringValue(chunks, "minZ") + ") → (" + stringValue(chunks, "maxX") + ", " + stringValue(chunks, "maxZ") + ")"); }
        if (record.has("x")) result.add(TownText.text("yat.label.position") + stringValue(record, "dimension") + "  " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z"));
        if (record.has("createdBy")) result.add(TownText.text("yat.label.registered_by") + stringValue(record, "createdByName", memberName(stringValue(record, "createdBy"))));
        if (!arrayValue(record, "overlapIds").isEmpty()) result.add(TownText.text("yat.label.overlaps", arrayValue(record, "overlapIds").size()));
        return result;
    }

    private void projectForm(JsonObject record, JsonObject chunks) {
        boolean editing = record.has("id");
        boolean maintenanceOnly = editing && !canManageProject(record) && has("ticket.manage") && stringValue(record, "status").equals("maintenance");
        if (archived() || gateway.worldId.isEmpty() || !(editing ? canManageProject(record) || maintenanceOnly : has("project.create"))) { gateway.status = TownText.text("yat.error.project_read_only"); return; }
        String status = stringValue(record, "status", "planned");
        List<TownForm.Field> fields = maintenanceOnly ? List.of() : List.of(text("name", TownText.text("yat.field.project_name"), TownText.recordName(record, "")).compact(), choice("type", TownText.text("yat.field.type"), stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{TownText.text("yat.project.type.building"), TownText.text("yat.project.type.machine"), TownText.text("yat.project.type.other")}), multiline("purpose", TownText.text("yat.field.summary"), stringValue(record, "purpose")), choice("status", TownText.text("yat.field.status"), status, editing ? new String[]{status} : new String[]{"planned", "active", "completed"}, editing ? new String[]{status.equals("maintenance") ? TownText.text("yat.project.status.repair_managed") : status.equals("completed") ? TownText.text("yat.project.status.completed") : state(status)} : new String[]{TownText.text("yat.project.status.planned"), TownText.text("yat.project.status.building"), TownText.text("yat.project.status.completed")}));
        String dimension = stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()));
        List<String> details = List.of(TownText.text("yat.label.selection") + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + dimension);
        List<TownForm.Action> actions = new ArrayList<>();
        if (editing) {
            String id = stringValue(record, "id");
            if (status.equals("planned")) actions.add(action(TownText.text("yat.ui.start_building"), () -> perform("project.status", object("id", id, "status", "active"))));
            if (status.equals("active")) actions.add(action(TownText.text("yat.ui.mark_completed"), () -> perform("project.status", object("id", id, "status", "completed"))));
            if (has("project.approve")) actions.add(action(TownText.text("yat.ui.transfer_owner"), () -> form(TownText.text("yat.title.transfer_project"), List.of(memberChoice("leaderId", TownText.text("yat.field.new_owner"), stringValue(record, "leaderId"))), data -> { data.addProperty("id", id); perform("project.update", data); })));
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id) && !stringValue(ticket, "status").equals("resolved"))
                    actions.add(action(TownText.text("yat.title.resolve_repair_prefix") + singleLine(stringValue(ticket, "title")), () -> showRecord("tickets", ticket)));
            }
            if (!maintenanceOnly) actions.add(action(TownText.text("yat.ui.delete_project"), () -> confirm(TownText.text("yat.ui.delete_project"), TownText.text("yat.confirm.delete_project", XaeroBridge.projectName(record)), () -> perform("project.delete", object("id", id, "worldId", stringValue(record, "worldId"))))));
        }
        VersionClient.show(new TownForm(destination(), editing ? TownText.text("yat.ui.edit_project") : TownText.text("yat.ui.create_project"), fields, details, actions, maintenanceOnly ? null : data -> {
            if (editing) data.remove("status");
            data.addProperty("name", TownText.editedName(record, stringValue(data, "name")));
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
        List<TownForm.Field> fields = List.of(multiline("description", TownText.text("yat.field.report_details"), ""));
        VersionClient.show(new TownForm(destination(), TownText.text("yat.title.report_repair_prefix") + stringValue(project, "name"), fields, List.of((atProjectWorld ? TownText.text("yat.label.current_position") : TownText.text("yat.label.project_center")) + stringValue(position, "dimension") + " " + integer(position, "x") + ", " + integer(position, "y") + ", " + integer(position, "z")), List.of(), data -> {
            for (String key : List.of("dimension", "x", "y", "z")) data.add(key, position.get(key));
            data.addProperty("projectId", stringValue(project, "id")); data.addProperty("worldId", stringValue(project, "worldId")); perform("ticket.create", data);
        }));
    }

    private void connectionForm() {
        VersionClient.show(VersionClient.createConfigScreen(this));
    }
    private void createTown() {
        form(TownText.text("yat.ui.create_town"), List.of(text("name", TownText.text("yat.field.town_name"), ""), text("introduction", TownText.text("yat.field.town_description"), ""), text("logo", TownText.text("yat.hint.logo"), "minecraft:oak_sapling"), text("server", TownText.text("yat.field.server"), VersionClient.serverAddress()), text("password", TownText.text("yat.field.join_password"), "")), data -> perform("town.create", data));
    }
    private void joinTown() {
        TownForm join = new TownForm(destination(), TownText.text("yat.ui.join_town"), List.of(text("townId", TownText.text("yat.field.town_id"), "").compact(), text("password", TownText.text("yat.field.town_password"), "").compact()), List.of(TownText.text("yat.status.loading")), List.of(), data -> perform("town.join", data));
        VersionClient.show(join);
        TownGateway.Context context = gateway.context();
        gateway.request("town.discover", object()).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (!gateway.isCurrent(context) || VersionClient.currentScreen() != join) return;
            if (failure != null) { join.updateContent(List.of(TownText.text("yat.error.load_town_list")), List.of()); return; }
            join.showTowns(objects(arrayValue(result, "items")));
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
        VersionClient.show(new TownForm(this, TownText.text("yat.title.select_town"), List.of(), List.of(TownText.text("yat.hint.switch_town")), actions, null));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        JsonObject current = currentWorld();
        if (!current.isEmpty()) actions.add(action(TownText.text("yat.ui.return_world_prefix") + TownText.recordName(current, ""), () -> { gateway.followCurrentWorld(); VersionClient.show(destination()); }));
        for (JsonElement element : array("worlds")) {
            JsonObject world = element.getAsJsonObject();
            actions.add(action(TownText.recordName(world, "") + (isCurrentWorld(stringValue(world, "id")) ? TownText.text("yat.label.current_suffix") : bool(world, "archived") ? TownText.text("yat.label.historic_suffix") : TownText.text("yat.label.inactive_suffix")), () -> worldDetail(world)));
        }
        if (actions.isEmpty() && has("worlds.manage")) actions.add(action(TownText.text("yat.ui.register_world"), this::worldForm));
        VersionClient.show(new TownForm(destination(), TownText.text("yat.title.world_history"), List.of(), List.of(TownText.text("yat.hint.world_history")), actions, null));
    }
    static String joinedTime(String timestamp, ZoneId zone) {
        try { return JOINED_TIME_FORMAT.format(Instant.parse(timestamp).atZone(zone)); }
        catch (DateTimeParseException exception) { return "—"; }
    }
    private boolean canTransferMayor(JsonObject member) {
        return isMayor() && !stringValue(member, "playerId").equals(playerId());
    }
    private int memberActionsWidth(JsonObject member) {
        return (canAssignMember(member) ? 124 : 0) + (canTransferMayor(member) ? (canAssignMember(member) ? 4 : 0) + 60 : 0);
    }
    private boolean canAssignMember(JsonObject member) {
        return has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")));
    }
    private void assignMember(JsonObject member) {
        if (!canAssignMember(member)) return;
        form(TownText.text("yat.title.assign_role_prefix") + stringValue(member, "name"), List.of(roleChoice(stringValue(member, "roleId"))), data -> {
            data.addProperty("playerId", stringValue(member, "playerId")); perform("member.role", data);
        });
    }
    private void roleDetail(JsonObject role) {
        List<String> description = new ArrayList<>();
        description.add(TownText.text("yat.label.role_id") + stringValue(role, "id"));
        for (JsonElement permission : arrayValue(role, "permissions")) description.add("✓ " + permissionName(permission.getAsString()));
        if (arrayValue(role, "permissions").isEmpty()) description.add(TownText.text("yat.hint.role_read_only"));
        List<TownForm.Action> actions = new ArrayList<>();
        if (canEditRole(role)) {
            actions.add(action(List.of("newcomer", "mayor").contains(stringValue(role, "id")) ? TownText.text("yat.ui.change_icon") : TownText.text("yat.ui.edit_role"), () -> roleForm(role)));
        }
        boolean assigned = objects(array("members")).stream().anyMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")));
        if (has("roles.manage") && canGrant(role) && !assigned && !List.of("newcomer", "mayor", "member", "manager").contains(stringValue(role, "id"))) actions.add(action(TownText.text("yat.ui.delete_role"), () -> confirm(TownText.text("yat.ui.delete_role"), TownText.text("yat.confirm.delete_role"), () -> perform("role.delete", object("id", stringValue(role, "id"))))));
        if (has("roles.manage") && !canEditRole(role)) description.add(TownText.text("yat.hint.role_edit_rules"));
        VersionClient.show(new TownForm(this, TownText.recordName(role, ""), List.of(), description, actions, null));
    }
    private static String roleIcon(JsonObject role) {
        String fallback = switch (stringValue(role, "id")) { case "newcomer" -> "minecraft:dirt"; case "member" -> "minecraft:cobblestone"; case "manager" -> "minecraft:iron_block"; case "mayor" -> "minecraft:emerald_block"; default -> "minecraft:stone"; };
        return stringValue(role, "icon", fallback);
    }
    private void roleForm(JsonObject role) {
        boolean fixed = List.of("newcomer", "mayor").contains(stringValue(role, "id"));
        List<TownForm.Field> fields = new ArrayList<>();
        if (!fixed) fields.add(text("name", TownText.text("yat.field.role_name"), TownText.recordName(role, "")).compact());
        fields.add(icon("icon", TownText.text("yat.field.role_icon"), roleIcon(role)).compact());
        if (!fixed) for (JsonElement element : array("permissionCatalog")) { JsonObject permission = element.getAsJsonObject(); String id = stringValue(permission, "id"); if (isMayor() || has(id)) fields.add(toggle(id, TownText.recordName(permission, id), contains(arrayValue(role, "permissions"), id))); }
        form(fixed ? TownText.text("yat.title.change_role_icon") : TownText.text("yat.title.role_permissions"), fields, data -> {
            JsonObject payload = object("icon", stringValue(data, "icon"));
            if (!fixed) {
                JsonArray permissions = new JsonArray();
                for (TownForm.Field field : fields) if (!List.of("name", "icon").contains(field.key()) && bool(data, field.key())) permissions.add(field.key());
                payload.addProperty("name", TownText.editedName(role, stringValue(data, "name"))); payload.add("permissions", permissions);
            }
            if (role.has("id")) payload.addProperty("id", stringValue(role, "id"));
            perform("role.save", payload);
        });
    }
    private void worldForm() { worldForm(new JsonObject()); }
    private void worldForm(JsonObject world) {
        form(world.has("id") ? TownText.text("yat.ui.edit_world") : TownText.text("yat.title.register_world"), List.of(text("name", TownText.text("yat.field.world_name"), TownText.recordName(world, "")), text("serverAddress", TownText.text("yat.field.game_server"), stringValue(world, "serverAddress", VersionClient.serverAddress())), text("worldKey", TownText.text("yat.field.world_key"), stringValue(world, "worldKey"))), data -> { data.addProperty("name", TownText.editedName(world, stringValue(data, "name"))); if (world.has("id")) data.addProperty("id", stringValue(world, "id")); perform(world.has("id") ? "world.update" : "world.create", data); });
    }
    private void worldDetail(JsonObject world) {
        List<TownForm.Action> actions = new ArrayList<>();
        boolean current = isCurrentWorld(stringValue(world, "id"));
        actions.add(action(current ? TownText.text("yat.ui.return_current_world") : TownText.text("yat.ui.browse_season"), () -> {
            if (current) gateway.followCurrentWorld(); else gateway.selectWorld(stringValue(world, "id"));
            VersionClient.show(destination());
        }));
        if (isMayor() && !bool(world, "archived")) actions.add(action(TownText.text("yat.ui.set_current_map"), () -> activateWorld(world)));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action(TownText.text("yat.ui.edit_world_details"), () -> worldForm(world)));
        if (bool(world, "archived") && !XaeroBridge.currentMapKey().isBlank() && stringValue(world, "id").equals(gateway.worldId))
            actions.add(action(TownText.text("yat.ui.bind_historic_map"), () -> bindWorldMap(world)));
        String viewedDimension = XaeroBridge.currentMapDimension();
        if (bool(world, "archived") && stringValue(world, "id").equals(gateway.worldId) && !gateway.getBoundMapKey(viewedDimension).isEmpty())
            actions.add(action(TownText.text("yat.ui.clear_local_map"), () -> confirm(TownText.text("yat.ui.clear_local_map"), TownText.text("yat.hint.clear_local_map"),
                    () -> { gateway.unbindMap(viewedDimension); VersionClient.show(destination()); })));
        if (has("worlds.manage") && !bool(world, "archived") && !current) actions.add(action(TownText.text("yat.ui.archive_world"), () -> confirm(TownText.text("yat.title.archive_season"), TownText.text("yat.confirm.archive_season"), () -> perform("world.archive", object("worldId", stringValue(world, "id"))))));
        List<String> details = new ArrayList<>(List.of(TownText.text("yat.label.server") + stringValue(world, "serverAddress"), TownText.text("yat.label.world_key") + stringValue(world, "worldKey"), TownText.text("yat.label.status") + (current ? TownText.text("yat.title.current_world") : bool(world, "archived") ? TownText.text("yat.label.archived_read_only") : TownText.text("yat.status.world_not_current"))));
        if (bool(world, "archived")) {
            if (stringValue(world, "id").equals(gateway.worldId)) {
                String bound = gateway.getBoundMapKey(viewedDimension);
                details.add(TownText.text("yat.label.historic_map") + (bound.isBlank() ? TownText.text("yat.hint.historic_map_missing") : bound));
            } else details.add(TownText.text("yat.hint.browse_before_binding"));
        } else {
            String mapName = stringValue(objectValue(world, "mapNames"), viewedDimension, stringValue(world, "mapName"));
            details.add(TownText.text("yat.label.town_map_name") + (mapName.isBlank() ? TownText.text("yat.status.awaiting_mayor") : mapName));
            details.add(TownText.text("yat.hint.dimension_maps"));
            if (current && !mapName.isBlank() && !mapName.equals(XaeroBridge.currentMapName()))
                details.add(TownText.text("yat.error.map_name_mismatch"));
        }
        VersionClient.show(new TownForm(destination(), TownText.recordName(world, ""), List.of(), details, actions, null));
    }
    private void activateWorld(JsonObject world) {
        if (!isMayor() || bool(world, "archived")) { gateway.status = TownText.text("yat.error.set_world_permission"); return; }
        String reason = XaeroBridge.currentMapSetupBlockReason();
        String mapName = XaeroBridge.currentMapName();
        if (reason.isEmpty() && mapName.isBlank()) reason = TownText.text("yat.error.unnamed_xaero_map");
        if (!reason.isEmpty()) {
            gateway.status = reason;
            VersionClient.show(new TownForm(destination(), TownText.text("yat.error.set_current_world"), List.of(), List.of(reason), List.of(action(TownText.text("yat.ui.retry_setup"), () -> activateWorld(world))), null));
            return;
        }
        perform("world.activate", object("worldId", stringValue(world, "id"), "serverAddress", VersionClient.serverAddress(),
                "mapName", mapName, "dimension", XaeroBridge.currentMapDimension()));
    }
    private void bindWorldMap(JsonObject world) {
        String dimension = XaeroBridge.currentMapDimension(), mapKey = XaeroBridge.currentMapKey();
        String explanation = TownText.text("yat.label.season") + TownText.recordName(world, "") + TownText.text("yat.detail.map_dimension") + dimension + TownText.text("yat.detail.xaero_instance") + mapKey + TownText.text("yat.hint.confirm_season_instance");
        if (bool(world, "archived")) explanation += TownText.text("yat.hint.historic_instance_warning");
        confirm(TownText.text("yat.ui.bind_historic_map"), explanation, () -> { gateway.bindCurrentMap(dimension, mapKey); gateway.status = TownText.text("yat.status.historic_map_bound"); VersionClient.show(destination()); });
    }
    private TownForm.Field roleChoice(String selected) {
        JsonArray roles = new JsonArray();
        for (JsonElement element : array("roles")) {
            JsonObject role = element.getAsJsonObject();
            if (stringValue(role, "id").equals("mayor")) continue;
            if (canGrant(role)) roles.add(element);
        }
        return collectionChoice("roleId", TownText.text("yat.tab.roles"), roles, "id", "name", selected);
    }
    private TownForm.Field memberChoice(String key, String label, String selected) { return collectionChoice(key, label, array("members"), "playerId", "name", selected); }
    private TownForm.Field collectionChoice(String key, String label, JsonArray collection, String idKey, String nameKey, String selected) {
        List<String> values = new ArrayList<>(), names = new ArrayList<>();
        for (JsonElement element : collection) { JsonObject item = element.getAsJsonObject(); values.add(stringValue(item, idKey)); names.add(nameKey.equals("name") ? TownText.recordName(item, "") : stringValue(item, nameKey)); }
        if (values.isEmpty()) { values.add(""); names.add(TownText.text("yat.empty.options")); }
        return choice(key, label, values.contains(selected) ? selected : values.get(0), values.toArray(String[]::new), names.toArray(String[]::new));
    }
    private void form(String title, List<TownForm.Field> fields, Consumer<JsonObject> submit) { VersionClient.show(new TownForm(destination(), title, fields, submit)); }
    private void confirm(String title, String explanation, Runnable submit) { VersionClient.show(new TownForm(destination(), title, List.of(), List.of(explanation), List.of(), ignored -> submit.run())); }
    private TownForm.Action action(String label, Runnable run) { return new TownForm.Action(label, run); }
    private void perform(String action, JsonObject data) {
        if (mutationPending) throw new IllegalStateException(TownText.text("yat.error.request_pending"));
        mutationPending = true;
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
        })).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> mutationPending = false)));
    }

    private Button button(String label, int x, int y, int width, Runnable run) {
        Button button = TownStyle.button(label, x, y, width, () -> {
            try { run.run(); }
            catch (IllegalArgumentException | IllegalStateException exception) { TownForm.showError(this, exception.getMessage()); }
        });
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
    private String lookup(String collection, String key, String id, String fallback) { for (JsonElement element : array(collection)) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, key).equals(id)) return TownText.recordName(item, fallback); } return fallback; }
    private String projectName(String id) { return lookup("projects", "id", id, TownText.text("yat.label.unspecified")); }
    private String worldName(String id) { return lookup("worlds", "id", id, TownText.text("yat.empty.worlds")); }
    private String roleName(String id) { return lookup("roles", "id", id, id); }
    private String memberName(String id) { return lookup("members", "playerId", id, id.isEmpty() ? TownText.text("yat.label.unspecified") : id); }
    private String permissionName(String id) { for (JsonElement element : array("permissionCatalog")) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, "id").equals(id)) return TownText.recordName(item, id); } return id; }
    private String subtitle(JsonObject record) {
        if (record.has("roleId")) return roleName(stringValue(record, "roleId"));
        if (record.has("permissions")) return TownText.text("yat.label.permission_count", arrayValue(record, "permissions").size());
        if (record.has("worldKey")) return stringValue(record, "serverAddress") + " · " + stringValue(record, "worldKey");
        if (record.has("projectId")) return projectName(stringValue(record, "projectId")) + " · " + memberName(stringValue(record, "createdBy"));
        if (record.has("purpose")) return (stringValue(record, "purpose").isBlank() ? "" : singleLine(stringValue(record, "purpose")) + " · ") + memberName(stringValue(record, "leaderId"));
        return stringValue(record, "dimension") + (record.has("x") ? " · " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z") : "");
    }
    static String state(String value) { return switch (value) { case "planned" -> TownText.text("yat.project.status.planned"); case "active" -> TownText.text("yat.project.status.building"); case "completed" -> ""; case "maintenance" -> TownText.text("yat.project.status.needs_repair"); case "open" -> TownText.text("yat.ticket.status.pending"); case "working" -> TownText.text("yat.ticket.status.in_progress"); case "resolved" -> TownText.text("yat.ticket.status.resolved"); case "archived" -> TownText.text("yat.label.archived"); default -> value; }; }

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
            if (tab == 2 && section == 0) {
                String joined = card.joined();
                int textX = card.x() + 38;
                int titleY = card.y() + 6;
                int detailsY = TownStyle.centeredY(card.y() + card.height() - 23, 20, font.lineHeight);
                int actionsWidth = memberActionsWidth(card.record());
                int roleWidth = Math.max(0, card.width() - 46 - actionsWidth - (actionsWidth > 0 ? 6 : 0));
                String name = font.plainSubstrByWidth(card.title(), Math.max(0, card.width() - 52 - font.width(joined)));
                VersionClient.drawAvatar(graphics, stringValue(card.record(), "playerId"), card.title(), card.x() + 9, TownStyle.centeredY(titleY, detailsY + font.lineHeight - titleY, 20), 20);
                graphics.text(font, name, textX, titleY, TEXT, true);
                graphics.text(font, joined, textX + font.width(name) + 6, titleY, MUTED, false);
                graphics.text(font, font.plainSubstrByWidth(card.subtitle(), roleWidth), textX, detailsY, MUTED, false);
                if (mouseX >= textX && mouseX < textX + Math.max(roleWidth, font.width(name)) && mouseY >= titleY && mouseY < detailsY + font.lineHeight)
                    hoveredText = Component.literal(card.title() + "\n" + card.subtitle() + TownText.text("yat.detail.joined_at") + joined);
                continue;
            }
            boolean projectRow = tab == 1;
            int textOffset = projectRow ? 30 : 38;
            if (projectRow) VersionClient.drawLogo(graphics, ProjectHighlighter.projectIcon(stringValue(card.record(), "type")), card.x() + 11, TownStyle.centeredY(card.y() + 9, font.lineHeight, 12), 12);
            if (!projectRow) {
                int textBlockHeight = 17 + font.lineHeight;
                TownStyle.panel(graphics, card.x() + 7, TownStyle.centeredY(card.y() + 9, textBlockHeight, 23), 23, 23, false);
                if (tab == 2 && section == 1) VersionClient.drawLogo(graphics, roleIcon(card.record()), card.x() + 11, TownStyle.centeredY(card.y() + 9, textBlockHeight, 16), 16);
                else TownStyle.icon(graphics, 3, card.x() + 15, TownStyle.centeredY(card.y() + 9, textBlockHeight, 8), ACCENT);
            }
            int buttonSpace = card.repair() ? 120 : 66;
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
                graphics.text(font, font.plainSubstrByWidth(card.state(), badgeWidth - 12), badgeX + 6, TownStyle.centeredY(badgeY, 14, font.lineHeight), ACCENT, false);
            }
        }
        for (Line line : lines) graphics.text(font, font.plainSubstrByWidth(line.text(), contentLeft + contentWidth - line.x() - 8), line.x(), line.y(), line.color(), false);
        if (tab == 0) {
            for (ItemIcon icon : homeIcons) VersionClient.drawLogo(graphics, icon.item(), icon.x(), icon.y(), 16);
            VersionClient.drawAvatar(graphics, avatarX, avatarY, 28);
            if (showLogo) {
                TownStyle.panel(graphics, logoX - 4, logoY - 4, 36, 36, false);
                VersionClient.drawLogo(graphics, stringValue(objectValue(gateway.snapshot, "town"), "logo"), logoX + 2, TownStyle.centeredY(logoY + 2, 17 + font.lineHeight, 24), 24);
            }
        }
        if (showSettingsLogo) VersionClient.drawLogo(graphics, townInputs.get("logo").getValue(), settingsLogoX + 4, settingsLogoY + 4, 16);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (hoveredText != null) graphics.setTooltipForNextFrame(hoveredText, mouseX, mouseY);
    }
}
