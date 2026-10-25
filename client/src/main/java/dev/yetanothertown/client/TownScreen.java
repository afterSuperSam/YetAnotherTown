package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.PreeditEvent;
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
    private Line managementHeading;
    private final List<Card> cards = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<ItemIcon> homeIcons = new ArrayList<>();
    private record ItemIcon(String item, int x, int y, int size) {
        ItemIcon(String item, int x, int y) { this(item, x, y, 16); }
    }
    private record PreviewAvatar(String playerId, String name, int x, int y) { }
    private PreviewAvatar previewAvatar;
    private final List<Button> homeButtons = new ArrayList<>();
    private int avatarX, avatarY, logoX, logoY;
    private boolean showLogo;
    private final Map<String, EditBox> townInputs = new LinkedHashMap<>();
    private final Map<String, String> savedTownValues = new LinkedHashMap<>();
    private final Map<String, String> townFlags = new LinkedHashMap<>();
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
    private int projectQuickFilter;
    private String selectedProjectId = "";
    private boolean splitProjects;
    private int sidebarLeft, sidebarWidth;
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
    private TownStyle.LoadingState loadingState;
    private boolean contentReady;
    private boolean loadingWasBlocking;
    private TownStyle.LoadingState loading() {
        if (!settingsScopeMatches()) resetTownSettings();
        if (!gateway.context().equals(listContext)) contentReady = false;
        if (loadingState == null) loadingState = new TownStyle.LoadingState();
        loadingState.update(gateway.isLoading() || mutationPending || settingsSubmitting, System.nanoTime());
        return loadingState;
    }
    private String createdTownSetup = "";
    private long inviteCopiedUntil;
    private Button inviteButton;
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
        controller.showProject(project);
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

    @Override protected void init() { clearFocus(); pendingSearchFocus = false; contentReady = false; window = null; rebuild(); }
    @Override protected void setInitialFocus() { }
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
            if (key.equals("password") && values.containsKey("passwordEnabled")) {
                boolean enabled = Boolean.parseBoolean(values.get("passwordEnabled"));
                boolean wasEnabled = Boolean.parseBoolean(baseline.get("passwordEnabled"));
                if (!enabled && wasEnabled) changes.addProperty(key, "");
                else if (enabled && (!wasEnabled || !value.isEmpty())) changes.addProperty(key, value);
            } else if (key.equals("password") ? !value.isEmpty() : !value.equals(baseline.get(key))) changes.addProperty(key, value);
        }
        if (values.containsKey("listed") && !values.get("listed").equals(baseline.get("listed"))) changes.addProperty("listed", Boolean.parseBoolean(values.get("listed")));
        return changes;
    }
    static boolean isNavigationTarget(JsonObject record, JsonObject target) {
        return target != null && !stringValue(record, "id").isEmpty() && stringValue(record, "id").equals(stringValue(target, "id"))
                && stringValue(record, "worldId").equals(stringValue(target, "worldId"));
    }
    private boolean dirtyTownSettings() {
        return settingsScopeMatches() && (townInputs.entrySet().stream().anyMatch(entry -> !entry.getValue().getValue().equals(savedTownValues.get(entry.getKey())))
                || townFlags.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(savedTownValues.get(entry.getKey()))));
    }
    private void resetTownSettings() {
        TownGateway.Context context = gateway.context();
        settingsTownId = gateway.townId; settingsEndpoint = context.endpoint(); settingsAuthentication = context.authentication();
        townInputs.clear(); townFlags.clear(); savedTownValues.clear(); settingsSnapshot = null; settingsScroll = 0; settingsSubmitting = false;
    }
    private void leaveTownSettings(Runnable action) {
        if (settingsSubmitting) { gateway.status = TownText.text("yat.status.saving_town"); return; }
        if (dirtyTownSettings()) {
            TownForm.confirmUnsaved(this, () -> saveTownSettings(townSettingsChanges(), action), () -> { resetTownSettings(); action.run(); });
        } else action.run();
    }
    @Override public void onClose() { leaveTownSettings(() -> VersionClient.show(parent)); }
    @Override public void tick() {
        boolean blocking = loading().blocking();
        boolean loadingFinished = loadingWasBlocking && !blocking;
        loadingWasBlocking = blocking;
        if (!blocking && (loadingFinished || !contentReady || searchChanged || previousSnapshot != gateway.snapshot || previousTowns != gateway.towns || previousNavigation != VersionClient.navigation()
                || !gateway.loggedIn() && !previousConnectionStatus.equals(gateway.status))) rebuild();
        if (inviteButton != null && inviteCopiedUntil > 0 && System.currentTimeMillis() >= inviteCopiedUntil) {
            inviteCopiedUntil = 0;
            inviteButton.setMessage(Component.literal(TownText.text("yat.ui.copy_invite")));
        }
        if (!blocking && !createdTownSetup.isEmpty() && !createdTownSetup.equals(gateway.townId)) createdTownSetup = "";
        if (!blocking && !createdTownSetup.isEmpty() && !currentWorld().isEmpty()) {
            createdTownSetup = "";
            if (isMayor()) finishTownSetup();
        }
    }

    private void rebuild() {
        if (window != null && loading().blocking()) return;
        if (!settingsScopeMatches()) resetTownSettings();
        if (!gateway.context().equals(listContext)) { listContext = gateway.context(); searchInput = null; selectedProjectId = ""; page = 0; }
        if (gateway.townId.isEmpty()) tab = 0;
        String focusedSetting = townInputs.entrySet().stream().filter(entry -> entry.getValue() == getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        boolean searchFocused = searchInput != null && (pendingSearchFocus || getFocused() == searchInput);
        pendingSearchFocus = false;
        searchChanged = false;
        Button focused = getFocused() instanceof Button button ? button : null;
        clearFocus();
        showSettingsLogo = false;
        clearWidgets(); lines.clear(); cards.clear(); panels.clear(); homeButtons.clear(); homeIcons.clear(); previewAvatar = null; inviteButton = null; managementHeading = null;
        previousSnapshot = gateway.snapshot; previousTowns = gateway.towns;
        previousConnectionStatus = gateway.status;
        previousNavigation = VersionClient.navigation();
        window = TownStyle.bounds(width, height, 650, 350);
        sidebarLeft = window.left() + 12;
        sidebarWidth = window.width() >= 540 ? 86 : 62;
        contentLeft = sidebarLeft + sidebarWidth + 12;
        contentWidth = window.right() - 12 - contentLeft;
        bodyTop = window.top() + 32;
        bodyBottom = window.bottom() - 10;
        contentBottom = bodyBottom - 6;
        splitProjects = false;
        if (loading().blocking()) { contentReady = false; return; }
        contentReady = true;
        String[] tabs = {TownText.text("yat.tab.home"), TownText.text("yat.tab.projects"), TownText.text("yat.tab.manage")};
        int navigationTop = bodyTop + (bodyBottom - bodyTop >= 240 ? 94 : 8);
        for (int index = 0; index < tabs.length; index++) {
            final int selected = index;
            Button navigation = addRenderableWidget(TownStyle.tab(tabs[index], sidebarLeft + 4, navigationTop + index * 28, sidebarWidth - 8,
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
                String sectionTitle = TownStyle.fitText(sections[section] + "  " + (section + 1) + "/" + sections.length, Math.max(0, contentWidth - 56), font::width);
                managementHeading = new Line(sectionTitle, contentLeft + (contentWidth - font.width(sectionTitle)) / 2, contentTop + 6, ACCENT);
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
        if (loading().blocking()) return true;
        if (tab == 0 && maximumHomeScroll > 0 && event.x() >= contentLeft + contentWidth - 4 && event.x() <= contentLeft + contentWidth + 4 && event.y() >= bodyTop && event.y() <= bodyBottom) {
            homeScroll = (int) Math.round(maximumHomeScroll * Math.max(0, Math.min(1, (event.y() - bodyTop) / (bodyBottom - bodyTop))));
            rebuild();
            return true;
        }
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
        boolean joined = !gateway.townId.isEmpty();
        JsonObject town = objectValue(gateway.snapshot, "town");
        String townName = stringValue(town, "name", TownText.text("yat.empty.membership"));
        int heroY = y;
        int archiveWidth = mainWidth;
        int homeActionWidth = (mainWidth - 36) / 3;
        int heroHeight = 148;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 12; logoY = heroY + 12;
        int titleX = x + (showLogo ? 55 : 12);
        label(TownText.text("yat.title.town_profile") + (gateway.townId.isEmpty() ? "" : " · ID " + gateway.townId), titleX, heroY + 14, ACCENT);
        label(TownStyle.fitText(gateway.townId.isEmpty() ? TownText.text("yat.home.welcome") : townName, archiveWidth - (titleX - x) - 10, font::width), titleX, heroY + 31, TEXT);
        if (!gateway.loggedIn()) {
            List<String> connection = wrap(gateway.status, archiveWidth - 24);
            for (int index = 0; index < Math.min(3, connection.size()); index++) label(connection.get(index), x + 12, heroY + 70 + index * 13, MUTED);
            button(TownText.text("yat.ui.reconnect"), x + 12, heroY + 116, archiveWidth - 24, () -> VersionClient.connectIdentity(this::rebuild));
        } else if (!gateway.townId.isEmpty()) {
            List<String> introduction = wrap(isNewcomer() ? TownText.text("yat.hint.newcomer_read_only") : stringValue(town, "introduction", TownText.text("yat.home.tagline")), archiveWidth - 24);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 12, heroY + 54 + index * 12, TEXT);
            int membersY = heroY + 54 + Math.min(2, introduction.size()) * 12 + 3;
            label(TownStyle.fitText(TownText.text("yat.label.members_server", array("members").size(), stringValue(town, "server", VersionClient.serverAddress())), archiveWidth - 24, font::width), x + 12, membersY, MUTED);
            long maintenance = objects(array("projects")).stream().filter(item -> stringValue(item, "status").equals("maintenance")).count();
            int statisticsY = membersY + 16;
            String projectCount = TownText.text("yat.label.project_count", array("projects").size());
            int maintenanceX = x + 36 + font.width(projectCount) + 14;
            homeIcons.add(new ItemIcon("minecraft:quartz", x + 14, TownStyle.centeredY(statisticsY + 2, font.lineHeight, 20), 20));
            homeIcons.add(new ItemIcon("minecraft:redstone", maintenanceX + 2, TownStyle.centeredY(statisticsY + 2, font.lineHeight, 20), 20));
            label(projectCount, x + 38, statisticsY + 2, TEXT);
            label(TownText.text("yat.label.repair_count", maintenance), maintenanceX + 26, statisticsY + 2, maintenance > 0 ? TownStyle.DANGER : TownStyle.SUCCESS);
            button(TownText.text("yat.ui.project_map"), x + 12, heroY + 116, homeActionWidth, this::openMap);
            button(TownText.text("yat.ui.member_permissions"), x + 18 + homeActionWidth, heroY + 116, homeActionWidth, () -> { tab = 2; section = 0; rebuild(); });
            boolean setup = needsCurrentMapSetup();
            button(TownText.text(setup ? "yat.ui.use_current_map" : "yat.ui.town_worlds"), x + 24 + homeActionWidth * 2, heroY + 116, homeActionWidth,
                    () -> { if (setup) activateWorld(currentWorld()); else chooseWorld(); });
        } else {
            paragraph(TownText.text("yat.home.tagline"), x + 12, heroY + 60, archiveWidth - 24, MUTED);
            int joinWidth = (archiveWidth - 30) * 2 / 3;
            button(TownText.text("yat.ui.join_town"), x + 12, heroY + 116, joinWidth, this::joinTown);
            button(TownText.text("yat.ui.create_town"), x + 18 + joinWidth, heroY + 116, archiveWidth - 30 - joinWidth, this::createTown);
        }
        int worldY = heroY + heroHeight + 16;
        int worldHeight = 130;
        if (joined) {
            panels.add(new Panel(x, worldY, mainWidth, worldHeight, false));
            List<JsonObject> recent = recentProjects(objects(array("projects")));
            label(TownText.text(recent.stream().anyMatch(record -> bool(record, "pinned")) ? "yat.title.common_facilities" : "yat.title.recent_projects"), x + 10, worldY + 9, ACCENT);
            if (recent.isEmpty()) {
                String explanation = TownText.text("yat.empty.projects");
                int actionY = paragraph(explanation, x + 10, worldY + 32, mainWidth - 20, MUTED) + 7;
                if (joined) {
                    boolean setup = needsCurrentMapSetup();
                    button(TownText.text(setup ? "yat.ui.use_current_map" : "yat.ui.project_map"), x + 10, actionY, mainWidth - 20,
                            () -> { if (setup) activateWorld(currentWorld()); else openMap(); });
                }
            }
            for (int index = 0; index < Math.min(3, recent.size()); index++) {
                JsonObject record = recent.get(index);
                int rowY = worldY + 25 + index * 34;
                homeIcons.add(new ItemIcon(ProjectHighlighter.projectIcon(stringValue(record, "type")), x + 10, TownStyle.centeredY(rowY + 3, font.lineHeight, 16)));
                label(TownStyle.fitText(XaeroBridge.projectName(record), mainWidth - 92, font::width), x + 32, rowY + 3, projectColor(record));
                String summary = stringValue(record, "status").equals("maintenance")
                        ? String.join("；", objects(array("tickets")).stream().filter(ticket -> stringValue(ticket, "projectId").equals(stringValue(record, "id")) && !stringValue(ticket, "status").equals("resolved"))
                                .map(ticket -> stringValue(ticket, "description").isBlank() ? stringValue(ticket, "title") : stringValue(ticket, "description")).toList())
                        : stringValue(record, "purpose");
                label(TownStyle.fitText(singleLine(summary), mainWidth - 76, font::width), x + 12, rowY + 18, MUTED);
                button(TownText.text("yat.ui.view"), x + mainWidth - 52, rowY + 6, 42, () -> showProject(record)).setTooltip(Tooltip.create(Component.literal(summary)));
            }
        }
        int mainBottom = joined ? worldY + worldHeight : heroY + heroHeight;
        int profileY = wide ? y : mainBottom + 8;
        int profileHeight = joined ? (wide ? 130 : 104) : 78;
        panels.add(new Panel(profileX, profileY, sideWidth, profileHeight, false));
        avatarX = profileX + 10; avatarY = profileY + 10;
        int nameWidth = sideWidth - 57;
        label(TownStyle.fitText(VersionClient.playerName(), nameWidth, font::width), profileX + 47, TownStyle.centeredY(avatarY, 28, font.lineHeight), TEXT);
        int settingsX = profileX + 10;
        int settingsWidth = sideWidth - 20;
        button(TownText.text("yat.title.client_settings"), settingsX, profileY + 48, settingsWidth, this::connectionForm);
        if (joined) {
            button(TownText.text("yat.ui.switch_town"), settingsX, profileY + 74, wide ? settingsWidth : (settingsWidth - 6) / 2, this::chooseTown).active = gateway.loggedIn();
            inviteButton = button(TownText.text(System.currentTimeMillis() < inviteCopiedUntil ? "yat.ui.invite_copied" : "yat.ui.copy_invite"), wide ? settingsX : settingsX + (settingsWidth - 6) / 2 + 6, profileY + (wide ? 100 : 74), wide ? settingsWidth : settingsWidth - (settingsWidth - 6) / 2 - 6, () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(invitationText(objectValue(gateway.snapshot, "town"), gateway.config().endpoint));
                inviteCopiedUntil = System.currentTimeMillis() + 1800;
                inviteButton.setMessage(Component.literal(TownText.text("yat.ui.invite_copied")));
            });
            inviteButton.active = !town.isEmpty();
        }
        int bottom = Math.max(mainBottom, profileY + profileHeight);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - contentBottom);
    }

    private void platform() {
        boolean compact = contentWidth >= 400;
        int actionWidth = compact ? 100 : (contentWidth - 8) / 2;
        int y = contentTop;
        button(TownText.text("yat.ui.open_project_map"), contentLeft, y, actionWidth, this::openMap);
        String[] types = {TownText.text("yat.filter.all_types"), TownText.text("yat.project.type.building"), TownText.text("yat.project.type.machine"), TownText.text("yat.project.type.other")};
        button(types[projectType], contentLeft + contentWidth - actionWidth, y, actionWidth, () -> { projectType = (projectType + 1) % types.length; page = 0; rebuild(); });
        int searchLeft = compact ? contentLeft + actionWidth + 6 : contentLeft;
        int searchTop = compact ? y : y + 26;
        int searchWidth = compact ? contentWidth - 2 * (actionWidth + 6) : contentWidth;
        if (searchInput == null) {
            searchInput = new EditBox(font, searchLeft, searchTop, searchWidth, 20, Component.literal(TownText.text("yat.ui.search"))) {
                @Override public int getInnerWidth() { return Math.max(0, super.getInnerWidth() - 20); }
                @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
                    if (event.x() >= getX() + getWidth() - 22) { setValue(""); setFocused(true); pendingSearchFocus = true; }
                    else super.onClick(event, doubleClick);
                }
                @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                    super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
                    if (!getValue().isEmpty()) {
                        boolean hovered = mouseX >= getX() + getWidth() - 22 && mouseX < getX() + getWidth() && mouseY >= getY() && mouseY < getY() + getHeight();
                        graphics.text(font, "×", getX() + getWidth() - 13, TownStyle.centeredY(getY(), getHeight(), font.lineHeight), hovered ? ACCENT : MUTED, false);
                        if (hovered) graphics.setTooltipForNextFrame(font, Component.literal(TownText.text("yat.ui.clear_search")), mouseX, mouseY);
                    }
                }
            };
            searchInput.setMaxLength(100);
            searchInput.setResponder(value -> { page = 0; searchChanged = true; });
        }
        searchInput.setX(searchLeft); searchInput.setY(searchTop); searchInput.setWidth(searchWidth);
        String hint = TownText.text("yat.hint.search_projects");
        searchInput.setHint(Component.literal(hint));
        addRenderableWidget(searchInput);
        String[] quickFilters = {"all", "favorites", "mine", "maintenance"};
        String[] quickLabels = {"yat.filter.all", "yat.filter.favorites", "yat.filter.mine", "yat.filter.maintenance"};
        int filterTop = y + (compact ? 26 : 52);
        int filterWidth = (contentWidth - 12) / quickFilters.length;
        for (int index = 0; index < quickFilters.length; index++) {
            int selected = index;
            String label = TownText.text(quickLabels[index]);
            addRenderableWidget(TownStyle.tab(label, contentLeft + index * (filterWidth + 4), filterTop,
                    index == quickFilters.length - 1 ? contentWidth - index * (filterWidth + 4) : filterWidth,
                    () -> { projectQuickFilter = selected; page = 0; rebuild(); }, selected == projectQuickFilter));
        }
        List<JsonObject> records = objects(array("projects"));
        records.removeIf(record -> !gateway.matchesProjectFilter(record, quickFilters[projectQuickFilter], switch (projectType) { case 1 -> "building"; case 2 -> "machine"; case 3 -> "other"; default -> "all"; }));
        if (!searchInput.getValue().isBlank()) records.removeIf(record -> !matchesSearch(record, subtitle(record), searchInput.getValue()));
        int listTop = filterTop + 26;
        splitProjects = contentWidth >= 500 && contentBottom - listTop >= 190;
        int listWidth = splitProjects ? (contentWidth - 10) * 3 / 5 : contentWidth;
        list(records, listTop, this::showProject, listWidth);
        if (splitProjects) {
            JsonObject selected = records.stream().filter(record -> stringValue(record, "id").equals(selectedProjectId)).findFirst().orElse(null);
            if (selected != null) projectPreview(selected, contentLeft + listWidth + 10, listTop, contentWidth - listWidth - 10);
        }
    }

    /** 宽屏直接预览当前项目；完整说明和不常用操作仍由详情页承载。 */
    private void projectPreview(JsonObject record, int x, int y, int width) {
        panels.add(new Panel(x, y, width, contentBottom - y, false));
        String leaderId = stringValue(record, "leaderId");
        String leaderName = memberName(leaderId);
        previewAvatar = new PreviewAvatar(leaderId, leaderName, x + 12, TownStyle.centeredY(y + 12, 15 + font.lineHeight, 26));
        int textX = x + 46, textWidth = width - 58;
        homeIcons.add(new ItemIcon(ProjectHighlighter.projectIcon(stringValue(record, "type")), textX, TownStyle.centeredY(y + 12, font.lineHeight, 16)));
        label(TownStyle.fitText(XaeroBridge.projectName(record), textWidth - 20, font::width), textX + 20, y + 12, projectColor(record));
        String state = state(stringValue(record, "status"));
        int stateWidth = state.isEmpty() ? 0 : Math.min(font.width(state), textWidth / 2);
        label(TownStyle.fitText(leaderName, textWidth - (stateWidth > 0 ? stateWidth + 8 : 0), font::width), textX, y + 27, MUTED);
        if (stateWidth > 0) label(TownStyle.fitText(state, stateWidth, font::width), x + width - 12 - stateWidth, y + 27, projectColor(record));
        int summaryY = y + 51;
        int actionsY = contentBottom - 60;
        List<String> summary = wrap(stringValue(record, "purpose"), width - 24);
        int visibleLines = Math.max(0, (actionsY - summaryY - 10) / 12);
        boolean moreDetails = summary.size() > visibleLines || !stringValue(record, "notes").isBlank() || !arrayValue(record, "overlapIds").isEmpty()
                || objects(array("tickets")).stream().anyMatch(ticket -> stringValue(ticket, "projectId").equals(stringValue(record, "id")) && !stringValue(ticket, "status").equals("resolved"));
        for (int index = 0; index < Math.min(summary.size(), visibleLines); index++) {
            String line = summary.get(index);
            if (index == visibleLines - 1 && summary.size() > visibleLines) line = font.plainSubstrByWidth(line, width - 24 - font.width("…")) + "…";
            label(line, x + 12, summaryY + index * 12, TEXT);
        }
        int half = (width - 30) / 2;
        boolean canEdit = !archived() && (canManageProject(record) || has("ticket.manage") && stringValue(record, "status").equals("maintenance"));
        button(TownText.text("yat.ui.view_map"), x + 12, actionsY, canEdit ? half : width - 24, () -> locateProject(record));
        if (canEdit) button(TownText.text("yat.ui.edit_project"), x + 18 + half, actionsY, half,
                () -> projectForm(record, objectValue(record, "chunks")));
        boolean canPin = !archived() && (isMayor() || has("town.manage"));
        Button navigate = button(TownText.text(isNavigationTarget(record, VersionClient.navigation()) ? "yat.ui.stop_navigation" : "yat.ui.navigate_here"), x + 12, actionsY + 26, moreDetails || canPin ? half : width - 24, () -> {
            if (isNavigationTarget(record, VersionClient.navigation())) VersionClient.stopNavigation();
            else if (!VersionClient.navigate(record)) throw new IllegalStateException(gateway.status);
            rebuild();
        });
        navigate.active = !archived();
        if (moreDetails) button(TownText.text("yat.ui.more_details"), x + 18 + half, actionsY + 26, half, () -> showProject(record));
        else if (canPin) button(TownText.text(bool(record, "pinned") ? "yat.ui.unpin_project" : "yat.ui.pin_project"), x + 18 + half, actionsY + 26, half,
                () -> perform("project.pin", object("projectId", stringValue(record, "id"), "pinned", !bool(record, "pinned"))));
    }

    /** 只搜索可读业务文本，多个关键词需全部匹配。 */
    static boolean matchesSearch(JsonObject record, String related, String query) {
        if (query.isBlank()) return true;
        String text = (XaeroBridge.projectName(record) + " " + stringValue(record, "purpose") + " " + stringValue(record, "notes") + " " + stringValue(record, "dimension") + " " + state(stringValue(record, "status")) + " " + related).toLowerCase(Locale.ROOT);
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) if (!text.contains(word)) return false;
        return true;
    }

    static List<JsonObject> recentProjects(List<JsonObject> records) {
        List<JsonObject> sorted = new ArrayList<>(records);
        sorted.sort(java.util.Comparator.comparingInt((JsonObject record) -> bool(record, "pinned") ? 0 : 1)
                .thenComparingInt(record -> stringValue(record, "status").equals("maintenance") ? 0 : 1)
                .thenComparing(record -> stringValue(record, "updatedAt", stringValue(record, "createdAt")), java.util.Comparator.reverseOrder()));
        return sorted;
    }

    static String invitationText(JsonObject town, String endpoint) {
        return TownText.text("yat.invite.text", stringValue(town, "name"), stringValue(town, "id"), stringValue(town, "server"), endpoint);
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

    /** 设置与开关保留草稿，由保存按钮统一提交；启用密码时空值保持原密码。 */
    private void inlineTownSettings(int y) {
        JsonObject town = objectValue(gateway.snapshot, "town");
        if (!validSettingsTown(gateway.snapshot, gateway.townId)) {
            label(TownText.text("yat.error.town_not_loaded"), contentLeft, y + 3, MUTED);
            return;
        }
        boolean synchronize = !settingsSubmitting && settingsSnapshot != gateway.snapshot;
        boolean editable = has("town.manage") && !settingsSubmitting;
        int formWidth = Math.min(360, contentWidth);
        int labelWidth = Math.min(100, formWidth / 3);
        int fieldX = contentLeft + labelWidth + 8;
        int fieldWidth = formWidth - labelWidth - 8;
        int fullHeight = 196;
        settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, y + fullHeight - contentBottom)));
        y -= settingsScroll;
        label(TownText.text("yat.label.town_id") + gateway.townId, contentLeft, y + 3, MUTED);
        for (String key : List.of("passwordEnabled", "listed")) {
            String remote = Boolean.toString(bool(town, key));
            if (!townFlags.containsKey(key)) { townFlags.put(key, remote); savedTownValues.put(key, remote); }
            else if (synchronize) {
                boolean clean = townFlags.get(key).equals(savedTownValues.get(key));
                townFlags.put(key, synchronizedSettingValue(townFlags.get(key), savedTownValues.get(key), remote));
                if (clean) savedTownValues.put(key, remote);
            }
        }
        String[][] definitions = {{"name", TownText.text("yat.field.town_name")}, {"introduction", TownText.text("yat.field.town_introduction")}, {"logo", TownText.text("yat.field.town_logo")}, {"password", TownText.text("yat.field.change_password")}};
        for (int index = 0; index < definitions.length; index++) {
            String key = definitions[index][0], title = definitions[index][1];
            int fieldY = y + 20 + (index == 3 ? 4 : index) * 26;
            label(TownStyle.fitText(title, labelWidth, font::width), contentLeft, TownStyle.centeredY(fieldY, 20, font.lineHeight), TEXT);
            int inputWidth = fieldWidth - (key.equals("logo") ? 28 : 0);
            EditBox input = townInputs.get(key);
            if (input == null) { input = new EditBox(font, fieldX, fieldY, inputWidth, 20, Component.literal(title)); input.setMaxLength(key.equals("password") ? 128 : 2048); input.setValue(key.equals("password") ? "" : stringValue(town, key)); townInputs.put(key, input); savedTownValues.put(key, input.getValue()); }
            else if (synchronize && !key.equals("password")) {
                boolean clean = input.getValue().equals(savedTownValues.get(key));
                String synchronizedValue = synchronizedSettingValue(input.getValue(), savedTownValues.get(key), stringValue(town, key));
                if (!input.getValue().equals(synchronizedValue)) input.setValue(synchronizedValue);
                if (clean) savedTownValues.put(key, input.getValue());
            }
            boolean inputEditable = editable && (!key.equals("password") || Boolean.parseBoolean(townFlags.get("passwordEnabled")));
            input.setX(fieldX); input.setY(fieldY); input.setWidth(inputWidth); input.setEditable(inputEditable); input.active = inputEditable;
            input.visible = fieldY >= contentTop && fieldY + 20 <= contentBottom;
            if (key.equals("password")) input.setTooltip(Tooltip.create(Component.literal(TownText.text("yat.field.new_password"))));
            addRenderableWidget(input);
            if (key.equals("logo")) { settingsLogoX = fieldX + inputWidth + 4; settingsLogoY = fieldY - 2; showSettingsLogo = input.visible; }
        }
        if (synchronize) settingsSnapshot = gateway.snapshot;
        int passwordY = y + 98;
        label(TownStyle.fitText(TownText.text("yat.field.town_password"), labelWidth, font::width), contentLeft, TownStyle.centeredY(passwordY, 20, font.lineHeight), TEXT);
        Button password = button(TownText.text(Boolean.parseBoolean(townFlags.get("passwordEnabled")) ? "yat.ui.on" : "yat.ui.off"), fieldX, passwordY, fieldWidth, () -> {
            townFlags.put("passwordEnabled", Boolean.toString(!Boolean.parseBoolean(townFlags.get("passwordEnabled"))));
            rebuild();
        });
        password.active = editable; password.visible = passwordY >= contentTop && passwordY + 20 <= contentBottom;
        int listedY = y + 150;
        label(TownStyle.fitText(TownText.text("yat.field.town_listing"), labelWidth, font::width), contentLeft, TownStyle.centeredY(listedY, 20, font.lineHeight), TEXT);
        Button listed = button(TownText.text(Boolean.parseBoolean(townFlags.get("listed")) ? "yat.ui.on" : "yat.ui.off"), fieldX, listedY, fieldWidth, () -> {
            townFlags.put("listed", Boolean.toString(!Boolean.parseBoolean(townFlags.get("listed"))));
            rebuild();
        });
        listed.active = editable; listed.visible = listedY >= contentTop && listedY + 20 <= contentBottom;
        int saveY = y + 176;
        Button save = button(TownText.text("yat.ui.save_town_settings"), fieldX, saveY, fieldWidth, () -> {
            if (!has("town.manage")) { TownForm.showError(this, TownText.text("yat.error.manage_town_permission")); return; }
            JsonObject data = townSettingsChanges();
            if (data.isEmpty()) { resetTownSettings(); gateway.status = TownText.text("yat.status.town_unchanged"); rebuild(); return; }
            saveTownSettings(data);
        });
        save.active = editable; save.visible = saveY >= contentTop && saveY + 20 <= contentBottom;
    }

    private JsonObject townSettingsChanges() {
        Map<String, String> values = new LinkedHashMap<>();
        townInputs.forEach((key, input) -> values.put(key, input.getValue()));
        values.putAll(townFlags);
        return changedTownSettings(values, savedTownValues, gateway.snapshot, gateway.townId);
    }

    private void saveTownSettings(JsonObject data) { saveTownSettings(data, null); }

    private void saveTownSettings(JsonObject data, Runnable afterSave) {
        if (settingsSubmitting || !has("town.manage")) return;
        if (data.isEmpty()) { resetTownSettings(); if (afterSave != null) afterSave.run(); return; }
        if (data.has("password") && Boolean.parseBoolean(townFlags.get("passwordEnabled")) && stringValue(data, "password").length() < 6) {
            TownForm.showError(this, TownText.text("yat.error.password_short")); return;
        }
        settingsSubmitting = true;
        data.addProperty("townId", gateway.townId);
        TownGateway.Context context = gateway.context();
        rebuild();
        gateway.request("town.update", data).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (!sameSettingsScope(stringValue(data, "townId"), context.endpoint(), context.authentication(), gateway.townId, gateway.context())) return;
            settingsSubmitting = false;
            if (failure == null) {
                JsonObject town = objectValue(gateway.snapshot, "town");
                for (String key : data.keySet()) if (!key.equals("townId") && !key.equals("password")) town.add(key, data.get(key).deepCopy());
                if (data.has("password")) town.addProperty("passwordEnabled", !stringValue(data, "password").isEmpty());
                for (String key : townFlags.keySet()) { String value = Boolean.toString(bool(town, key)); townFlags.put(key, value); savedTownValues.put(key, value); }
                gateway.status = TownText.text("yat.status.town_saved");
                for (String key : data.keySet()) if (townInputs.containsKey(key)) {
                    String value = key.equals("password") ? "" : stringValue(data, key);
                    townInputs.get(key).setValue(value); savedTownValues.put(key, value);
                }
                if (townInputs.containsKey("password")) { townInputs.get("password").setValue(""); savedTownValues.put("password", ""); }
                settingsSnapshot = gateway.snapshot;
                gateway.refresh();
            }
            if (VersionClient.currentScreen() == this) {
                if (failure == null && afterSave != null) { afterSave.run(); return; }
                rebuild();
                if (failure != null) TownForm.showError(this, gateway.status);
            }
        }));
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details) {
        list(records, top, details, contentWidth);
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details, int listWidth) {
        boolean members = tab == 2 && section == 0;
        int rowStep = tab == 1 ? 40 : members || tab == 2 && section == 1 ? 44 : 48;
        int available = Math.max(0, (contentBottom - top - 24 + 4) / rowStep);
        if (tab == 1) available = Math.min(5, available);
        else if (tab == 2 && section == 1) available = Math.min(4, available);
        if (available == 0) {
            if (contentBottom - top >= 14) label(TownText.text("yat.hint.reduce_gui_scale"), contentLeft + 4, top + 3, MUTED);
            return;
        }
        int pages = Math.max(1, (records.size() + available - 1) / available);
        page = Math.max(0, Math.min(page, pages - 1));
        if (splitProjects && !records.isEmpty()) {
            boolean selectedVisible = records.subList(page * available, Math.min(records.size(), (page + 1) * available)).stream()
                    .anyMatch(record -> stringValue(record, "id").equals(selectedProjectId));
            if (!selectedVisible) selectedProjectId = stringValue(records.get(page * available), "id");
        }
        if (records.isEmpty()) {
            int emptyHeight = Math.min(70, contentBottom - top - 24);
            panels.add(new Panel(contentLeft, top, listWidth, emptyHeight, false));
            boolean searching = tab == 1 && searchInput != null && !searchInput.getValue().isBlank();
            String emptyKey = "yat.empty.records";
            if (gateway.snapshot.isEmpty()) emptyKey = "yat.empty.data_loading";
            else if (searching) emptyKey = "yat.empty.search_results";
            else if (tab == 1) {
                if (projectQuickFilter > 0 && projectType > 0) emptyKey = "yat.empty.filtered_projects";
                else if (projectQuickFilter > 0) emptyKey = switch (projectQuickFilter) {
                    case 1 -> "yat.empty.favorite_projects";
                    case 2 -> "yat.empty.my_projects";
                    default -> "yat.empty.maintenance_projects";
                };
                else emptyKey = projectType > 0 ? "yat.empty.project_type" : "yat.empty.season_projects";
            }
            label(TownText.text(emptyKey), contentLeft + 12, top + 12, TEXT);
            if (emptyHeight >= 45 && searching) label(TownText.text("yat.hint.no_search_results"), contentLeft + 12, top + 30, MUTED);
            else if (emptyHeight >= 58 && tab == 1 && !gateway.snapshot.isEmpty())
                button(TownText.text(needsCurrentMapSetup() ? "yat.ui.use_current_map" : "yat.ui.project_map"), contentLeft + 12, top + 34, listWidth - 24,
                        () -> { if (needsCurrentMapSetup()) activateWorld(currentWorld()); else openMap(); });
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * rowStep;
            String title = tab == 1 ? XaeroBridge.projectName(record) : TownText.recordName(record, stringValue(record, "title", TownText.text("yat.label.record")));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            if (record.has("worldKey") && isCurrentWorld(stringValue(record, "id"))) status = TownText.text("yat.title.current_world");
            boolean repair = tab == 1 && List.of("completed", "maintenance").contains(stringValue(record, "status"));
            cards.add(new Card(contentLeft, y, listWidth, rowStep - 4, title, subtitle, status,
                    members ? joinedTime(stringValue(record, "joinedAt"), ZoneId.systemDefault()) : "", repair, record));
            if (members) {
                int actionY = y + (rowStep - 4 - 20) / 2;
                int actionX = contentLeft + listWidth - 7 - memberActionsWidth(record);
                if (canAssignMember(record)) {
                    int assignWidth = memberActionWidth(record, 72), removeWidth = memberActionWidth(record, 48);
                    button(TownText.text("yat.ui.assign_role"), actionX, actionY, assignWidth, () -> assignMember(record))
                            .setTooltip(Tooltip.create(Component.literal(TownText.text("yat.ui.assign_role") + " · " + title)));
                    button(TownText.text("yat.ui.remove_from_town"), actionX + assignWidth + 4, actionY, removeWidth, () -> confirm(TownText.text("yat.title.remove_member"), TownText.text("yat.confirm.remove_member", stringValue(record, "name")), () -> perform("member.remove", object("playerId", stringValue(record, "playerId")))))
                            .setTooltip(Tooltip.create(Component.literal(TownText.text("yat.ui.remove_from_town") + " · " + title)));
                    actionX += assignWidth + removeWidth + 8;
                }
                if (canTransferMayor(record)) button(TownText.text("yat.ui.transfer_mayor"), actionX, actionY, memberActionWidth(record, 60), () -> confirm(TownText.text("yat.ui.transfer_mayor"), TownText.text("yat.confirm.transfer_mayor", stringValue(record, "name")), () -> perform("town.transfer", object("playerId", stringValue(record, "playerId")))))
                        .setTooltip(Tooltip.create(Component.literal(TownText.text("yat.ui.transfer_mayor") + " · " + title)));
                continue;
            }
            int buttonY = y + (rowStep - 4 - 20) / 2;
            if (tab == 1) {
                Button favorite = new Button(contentLeft + 7, y + 2, 20, rowStep - 8, Component.literal(TownText.text(gateway.isFavorite(record) ? "yat.ui.unfavorite_project" : "yat.ui.favorite_project")), ignored -> {
                    try { gateway.toggleFavorite(record); gateway.completeHint("favorite"); ProjectHighlighter.invalidate(); rebuild(); }
                    catch (IllegalArgumentException | IllegalStateException exception) { TownForm.showError(this, exception.getMessage()); }
                }, narration -> narration.get()) {
                    private TownStyle.InteractionHighlight highlight;
                    @Override public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                        if (highlight == null) highlight = new TownStyle.InteractionHighlight();
                        highlight.draw(graphics, getX(), getY(), getWidth(), getHeight(), active && isHovered(), active && isFocused());
                        VersionClient.drawLogo(graphics, ProjectHighlighter.projectIcon(stringValue(record, "type")), getX() + 4, getY() + 4, 12);
                        graphics.text(font, "★", getX() + (getWidth() - font.width("★")) / 2, getY() + 19, gateway.isFavorite(record) ? ACCENT : MUTED, false);
                    }
                };
                favorite.setTooltip(Tooltip.create(Component.literal(favorite.getMessage().getString() + " · " + title
                        + (gateway.hasCompletedHint("favorite") ? "" : "\n" + TownText.text("yat.hint.favorite_once")))));
                addRenderableWidget(favorite);
            }
            if (repair) {
                boolean maintenance = stringValue(record, "status").equals("maintenance");
                Button report = button(maintenance ? TownText.text("yat.ui.complete") : TownText.text("yat.ui.report_repair"), contentLeft + listWidth - 55, buttonY, 48,
                        () -> { if (maintenance) perform("project.repair.complete", object("id", stringValue(record, "id"))); else ticketForm(record); });
                report.setTooltip(Tooltip.create(Component.literal(report.getMessage().getString() + " · " + title)));
                report.active = !archived() && (maintenance ? canManageProject(record) || has("ticket.manage") : has("ticket.create"));
            }
            if (splitProjects) {
                Button select = projectSelectionButton(title, contentLeft + 30, y + 2, listWidth - 38 - (repair ? 58 : 0), rowStep - 8,
                        () -> { selectedProjectId = stringValue(record, "id"); rebuild(); });
                addRenderableWidget(select);
            } else {
                String detailLabel = tab == 2 && section == 1 && canEditRole(record)
                        ? List.of("newcomer", "mayor").contains(stringValue(record, "id")) ? "yat.ui.change_icon" : "yat.ui.edit_role"
                        : "yat.ui.details";
                button(TownText.text(detailLabel), contentLeft + listWidth - (repair ? 109 : 55), buttonY, 48, () -> details.accept(record))
                        .setTooltip(Tooltip.create(Component.literal(TownText.text(detailLabel) + " · " + title)));
            }
        }
        int footer = contentBottom - 20;
        if (pages > 1) {
            String position = (page + 1) + " / " + pages;
            Button previous = button("‹", contentLeft, footer, 24, () -> { page--; rebuild(); });
            Button next = button("›", contentLeft + 40 + font.width(position), footer, 24, () -> { page++; rebuild(); });
            previous.active = page > 0;
            next.active = page < pages - 1;
            label(position, contentLeft + 32, TownStyle.centeredY(footer, 20, font.lineHeight), MUTED);
        }
        boolean history = tab == 1 && archived();
        if (history) label(TownText.text("yat.label.historic_season"), contentLeft + listWidth - 96, TownStyle.centeredY(footer, 20, font.lineHeight), ACCENT);
    }

    private void openMap() {
        if (externalParent && returnScreen != null) { VersionClient.show(returnScreen); return; }
        if (!XaeroBridge.openMap(destination())) VersionClient.show(new TownMapScreen(destination()));
    }

    private void locateProject(JsonObject project) {
        if (!XaeroBridge.openProjectMap(VersionClient.currentScreen(), project)) throw new IllegalStateException(gateway.status);
    }

    private void showProject(JsonObject record) {
        List<String> description = new ArrayList<>();
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        actions.add(action(TownText.text("yat.ui.view_map"), () -> locateProject(record)));
        if ((canManageProject(record) || has("ticket.manage") && stringValue(record, "status").equals("maintenance")) && writable) {
            actions.add(action(TownText.text("yat.ui.edit_project"), () -> projectForm(record, objectValue(record, "chunks"))));
        }
        if ((isMayor() || has("town.manage")) && writable) actions.add(action(TownText.text(bool(record, "pinned") ? "yat.ui.unpin_project" : "yat.ui.pin_project"),
                () -> perform("project.pin", object("projectId", id, "pinned", !bool(record, "pinned")))));
        for (JsonElement element : array("tickets")) {
            JsonObject ticket = element.getAsJsonObject();
            if (stringValue(ticket, "projectId").equals(id) && !stringValue(ticket, "status").equals("resolved")) description.add(TownText.text("yat.label.repair_needed") + stringValue(ticket, "description", stringValue(ticket, "title")));
        }
        if (!stringValue(record, "notes").isBlank()) description.add(stringValue(record, "notes"));
        if (!arrayValue(record, "overlapIds").isEmpty()) description.add(TownText.text("yat.label.overlaps", arrayValue(record, "overlapIds").size()));
        if (record.has("dimension") && writable) {
            actions.add(new TownForm.Action(TownText.text("yat.ui.navigate_here"), () -> {
                JsonObject currentNavigation = VersionClient.navigation();
                if (isNavigationTarget(record, currentNavigation)) VersionClient.stopNavigation();
                else if (!VersionClient.navigate(record)) throw new IllegalStateException(gateway.status);
                showProject(record);
            }, () -> isNavigationTarget(record, VersionClient.navigation()) ? TownText.text("yat.ui.stop_navigation") : TownText.text("yat.ui.navigate_here")));
        }
        VersionClient.show(new TownForm(destination(), XaeroBridge.projectName(record), List.of(), description, actions, null)
                .withProjectTitle(record, memberName(stringValue(record, "leaderId"))));
    }

    private void projectForm(JsonObject record, JsonObject chunks) {
        boolean editing = record.has("id");
        boolean maintenanceOnly = editing && !canManageProject(record) && has("ticket.manage") && stringValue(record, "status").equals("maintenance");
        if (archived() || gateway.worldId.isEmpty() || !(editing ? canManageProject(record) || maintenanceOnly : has("project.create"))) {
            TownForm.showError(VersionClient.currentScreen(), TownText.text("yat.error.project_read_only"));
            return;
        }
        String status = stringValue(record, "status", "planned");
        boolean repairHistory = editing && objects(array("tickets")).stream().anyMatch(ticket -> stringValue(ticket, "projectId").equals(stringValue(record, "id")));
        String[] statuses = status.equals("maintenance") || repairHistory ? new String[]{status} : new String[]{"planned", "active", "completed"};
        String[] statusLabels = status.equals("maintenance") ? new String[]{TownText.text("yat.project.status.repair_managed")} : repairHistory ? new String[]{TownText.text("yat.project.status.completed")} : new String[]{TownText.text("yat.project.status.planned"), TownText.text("yat.project.status.building"), TownText.text("yat.project.status.completed")};
        List<TownForm.Field> fields = maintenanceOnly ? List.of() : List.of(text("name", TownText.text("yat.field.project_name"), TownText.recordName(record, "")).compact(), choice("type", TownText.text("yat.field.type"), stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{TownText.text("yat.project.type.building"), TownText.text("yat.project.type.machine"), TownText.text("yat.project.type.other")}), multiline("purpose", TownText.text("yat.field.summary"), stringValue(record, "purpose")), choice("status", TownText.text("yat.field.status"), status, statuses, statusLabels));
        String dimension = stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()));
        List<String> details = List.of(TownText.text("yat.label.selection") + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + dimension);
        List<TownForm.Action> actions = new ArrayList<>();
        if (editing) {
            String id = stringValue(record, "id");
            if (!maintenanceOnly) actions.add(action(TownText.text("yat.ui.expand_project"), () -> XaeroBridge.startProjectExpansion(VersionClient.currentScreen(), record)));
            if (has("project.approve")) actions.add(action(TownText.text("yat.ui.transfer_owner"), () -> form(TownText.text("yat.title.transfer_project"), List.of(memberChoice("leaderId", TownText.text("yat.field.new_owner"), stringValue(record, "leaderId"))), data -> { data.addProperty("id", id); perform("project.update", data); })));
            if (status.equals("maintenance")) actions.add(action(TownText.text("yat.ui.finish_repair"), () -> perform("project.repair.complete", object("id", id))));
            if (!maintenanceOnly) actions.add(action(TownText.text("yat.ui.delete_project"), () -> confirm(TownText.text("yat.ui.delete_project"), TownText.text("yat.confirm.delete_project", XaeroBridge.projectName(record)), () -> perform("project.delete", object("id", id, "worldId", stringValue(record, "worldId"))))));
        }
        VersionClient.show(new TownForm(VersionClient.currentScreen(), editing ? TownText.text("yat.ui.edit_project") : TownText.text("yat.ui.create_project"), fields, details, actions, maintenanceOnly ? null : data -> {
            perform(editing ? "project.update" : "project.create", projectFormData(record, chunks, dimension, gateway.worldId, data));
        }).withGuardedActions());
    }

    static JsonObject projectFormData(JsonObject record, JsonObject chunks, String dimension, String worldId, JsonObject data) {
        boolean editing = record.has("id");
        data.addProperty("name", TownText.editedName(record, stringValue(data, "name")));
        if (editing && stringValue(data, "status").equals(stringValue(record, "status"))) data.remove("status");
        if (!editing) { data.add("chunks", chunks.deepCopy()); data.addProperty("dimension", dimension); }
        data.addProperty("notes", stringValue(record, "notes"));
        if (editing) data.addProperty("id", stringValue(record, "id")); else data.addProperty("worldId", worldId);
        return data;
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
        VersionClient.show(new TownForm(VersionClient.currentScreen(), TownText.text("yat.title.report_repair_prefix") + XaeroBridge.projectName(project), fields, List.of(), List.of(), data -> {
            for (String key : List.of("dimension", "x", "y", "z")) data.add(key, position.get(key));
            data.addProperty("projectId", stringValue(project, "id")); data.addProperty("worldId", stringValue(project, "worldId")); perform("ticket.create", data);
        }).withSubmitLabel(TownText.text("yat.ui.report_repair")));
    }

    private void connectionForm() {
        VersionClient.show(VersionClient.createConfigScreen(this));
    }
    private void createTown() {
        form(TownText.text("yat.ui.create_town"), List.of(text("name", TownText.text("yat.field.town_name"), "")), data -> perform("town.create", data));
    }
    private void finishTownSetup() {
        JsonObject world = currentWorld();
        VersionClient.show(new TownForm(destination(), TownText.text("yat.title.finish_town_setup"), List.of(), List.of(TownText.text("yat.hint.finish_town_setup")),
                List.of(action(TownText.text("yat.ui.use_current_map"), () -> activateWorld(world)),
                        action(TownText.text("yat.ui.enter_town"), () -> VersionClient.show(destination()))), null));
    }
    private void joinTown() {
        TownForm join = new TownForm(destination(), TownText.text("yat.ui.join_town"), List.of(text("townId", TownText.text("yat.field.town_id"), "").compact(), text("password", TownText.text("yat.field.town_password"), "").compact()), List.of(), List.of(), data -> perform("town.join", data)).withGlobalBreadcrumbTitle().withTownDirectory().withLoading(true);
        VersionClient.show(join);
        TownGateway.Context context = gateway.context();
        gateway.request("town.discover", object()).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (!gateway.isCurrent(context)) { join.withLoading(false); return; }
            join.withLoading(false);
            if (failure != null) { join.updateContent(List.of(TownText.text("yat.error.load_town_list")), List.of()); return; }
            join.showTowns(objects(arrayValue(result, "items")));
        }));
    }
    private void chooseTown() {
        TownForm chooser = new TownForm(this, TownText.text("yat.ui.switch_town"), List.of(), List.of(),
                List.of(action(TownText.text("yat.ui.create_town"), () -> leaveTownSettings(this::createTown)),
                        action(TownText.text("yat.ui.join_town"), () -> leaveTownSettings(this::joinTown))), null)
                .withGlobalBreadcrumbTitle().withTownSelection(town -> leaveTownSettings(() -> {
                    gateway.selectTown(stringValue(town, "id")); homeScroll = 0; tab = 0; VersionClient.show(this);
                }));
        VersionClient.show(chooser);
        chooser.showTowns(objects(gateway.towns));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : array("worlds")) {
            JsonObject world = element.getAsJsonObject();
            actions.add(action(TownText.recordName(world, "") + (isCurrentWorld(stringValue(world, "id")) ? TownText.text("yat.label.current_suffix") : bool(world, "archived") ? TownText.text("yat.label.historic_suffix") : TownText.text("yat.label.inactive_suffix")), () -> browseWorld(world)));
        }
        if (actions.isEmpty() && has("worlds.manage")) actions.add(action(TownText.text("yat.ui.register_world"), this::worldForm));
        VersionClient.show(new TownForm(destination(), TownText.text("yat.title.world_history"), List.of(), List.of(TownText.text(array("worlds").isEmpty() ? "yat.empty.worlds" : "yat.hint.world_history")), actions, null));
    }
    private void browseWorld(JsonObject world) {
        if (isCurrentWorld(stringValue(world, "id"))) gateway.followCurrentWorld();
        else gateway.selectWorld(stringValue(world, "id"));
        if (!externalParent) { tab = 1; page = 0; searchInput = null; projectQuickFilter = 0; projectType = 0; }
        VersionClient.show(destination());
    }
    static String joinedTime(String timestamp, ZoneId zone) {
        try { return JOINED_TIME_FORMAT.format(Instant.parse(timestamp).atZone(zone)); }
        catch (DateTimeParseException exception) { return "—"; }
    }
    private boolean canTransferMayor(JsonObject member) {
        return isMayor() && !stringValue(member, "playerId").equals(playerId());
    }
    private int memberActionsWidth(JsonObject member) {
        return (canAssignMember(member) ? memberActionWidth(member, 72) + memberActionWidth(member, 48) + 4 : 0)
                + (canTransferMayor(member) ? (canAssignMember(member) ? 4 : 0) + memberActionWidth(member, 60) : 0);
    }
    private int memberActionWidth(JsonObject member, int preferred) {
        int count = (canAssignMember(member) ? 2 : 0) + (canTransferMayor(member) ? 1 : 0);
        return Math.min(preferred, Math.max(18, ((contentWidth - 14) / 2 - Math.max(0, count - 1) * 4) / Math.max(1, count)));
    }
    private boolean canAssignMember(JsonObject member) {
        return has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")));
    }
    private void assignMember(JsonObject member) {
        if (!canAssignMember(member)) return;
        String currentRoleId = stringValue(member, "roleId");
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonObject targetRole : objects(array("roles"))) {
            String roleId = stringValue(targetRole, "id");
            if (roleId.equals("mayor") || roleId.equals(currentRoleId) || !canGrant(targetRole)) continue;
            actions.add(action(TownText.recordName(targetRole, ""), () -> {
                JsonObject currentMember = objects(array("members")).stream().filter(item -> stringValue(item, "playerId").equals(stringValue(member, "playerId"))).findFirst().orElse(new JsonObject());
                if (currentMember.isEmpty() || !canAssignMember(currentMember) || !canGrant(role(roleId)))
                    throw new IllegalStateException(TownText.text("yat.error.role_assignment_denied"));
                if (!roleId.equals(stringValue(currentMember, "roleId"))) perform("member.role", object("playerId", stringValue(member, "playerId"), "roleId", roleId));
            }));
        }
        List<String> description = new ArrayList<>(List.of(TownText.text("yat.label.current_role", roleName(currentRoleId))));
        if (actions.isEmpty()) description.add(TownText.text("yat.hint.no_assignable_roles"));
        VersionClient.show(new TownForm(this, TownText.text("yat.title.assign_role_prefix") + stringValue(member, "name"), List.of(), description, actions, null));
    }
    private void roleDetail(JsonObject role) {
        if (canEditRole(role)) { roleForm(role); return; }
        List<String> description = new ArrayList<>();
        description.add(TownText.text("yat.label.role_id") + stringValue(role, "id"));
        for (JsonElement permission : arrayValue(role, "permissions")) description.add("✓ " + permissionName(permission.getAsString()));
        if (arrayValue(role, "permissions").isEmpty()) description.add(TownText.text("yat.hint.role_read_only"));
        if (has("roles.manage") && !canEditRole(role)) description.add(TownText.text("yat.hint.role_edit_rules"));
        VersionClient.show(new TownForm(this, TownText.recordName(role, ""), List.of(), description, List.of(), null));
    }
    private static String roleIcon(JsonObject role) {
        String fallback = switch (stringValue(role, "id")) { case "newcomer" -> "minecraft:dirt"; case "member" -> "minecraft:cobblestone"; case "manager" -> "minecraft:iron_block"; case "mayor" -> "minecraft:emerald_block"; default -> "minecraft:stone"; };
        return stringValue(role, "icon", fallback);
    }
    private void roleForm(JsonObject role) {
        if (role.has("id") ? !canEditRole(role(stringValue(role, "id"))) : !has("roles.manage"))
            throw new IllegalStateException(TownText.text("yat.error.role_management_denied"));
        boolean fixed = List.of("newcomer", "mayor").contains(stringValue(role, "id"));
        List<TownForm.Field> fields = new ArrayList<>();
        if (!fixed) fields.add(text("name", TownText.text("yat.field.role_name"), TownText.recordName(role, "")).compact());
        fields.add(icon("icon", TownText.text("yat.field.role_icon"), roleIcon(role)).compact());
        if (!fixed) for (JsonElement element : array("permissionCatalog")) { JsonObject permission = element.getAsJsonObject(); String id = stringValue(permission, "id"); if (isMayor() || has(id)) fields.add(toggle(id, TownText.recordName(permission, id), contains(arrayValue(role, "permissions"), id))); }
        List<TownForm.Action> actions = new ArrayList<>();
        if (canDeleteRole(role)) actions.add(action(TownText.text("yat.ui.delete_role"), () -> confirm(TownText.text("yat.ui.delete_role"), TownText.text("yat.confirm.delete_role"), () -> {
            if (!canDeleteRole(role(stringValue(role, "id")))) throw new IllegalStateException(TownText.text("yat.error.role_management_denied"));
            perform("role.delete", object("id", stringValue(role, "id")));
        })));
        VersionClient.show(new TownForm(VersionClient.currentScreen(), fixed ? TownText.text("yat.title.change_role_icon") : TownText.text("yat.title.role_permissions"), fields, List.of(), actions, data -> {
            if (role.has("id") ? !canEditRole(role(stringValue(role, "id"))) : !has("roles.manage"))
                throw new IllegalStateException(TownText.text("yat.error.role_management_denied"));
            JsonObject payload = object("icon", stringValue(data, "icon"));
            if (!fixed) {
                JsonArray permissions = new JsonArray();
                for (TownForm.Field field : fields) if (!List.of("name", "icon").contains(field.key()) && bool(data, field.key())) permissions.add(field.key());
                payload.addProperty("name", TownText.editedName(role, stringValue(data, "name"))); payload.add("permissions", permissions);
            }
            if (role.has("id")) payload.addProperty("id", stringValue(role, "id"));
            perform("role.save", payload);
        }).withGuardedActions());
    }
    private boolean canDeleteRole(JsonObject role) {
        return has("roles.manage") && canGrant(role) && !List.of("newcomer", "mayor", "member", "manager").contains(stringValue(role, "id"))
                && objects(array("members")).stream().noneMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")));
    }
    private void worldForm() { worldForm(new JsonObject()); }
    private void worldForm(JsonObject world) {
        form(world.has("id") ? TownText.text("yat.ui.edit_world") : TownText.text("yat.title.register_world"), List.of(text("name", TownText.text("yat.field.world_name"), TownText.recordName(world, "")), text("worldKey", TownText.text("yat.field.world_key"), stringValue(world, "worldKey"))), data -> { data.addProperty("name", TownText.editedName(world, stringValue(data, "name"))); if (world.has("id")) data.addProperty("id", stringValue(world, "id")); perform(world.has("id") ? "world.update" : "world.create", data); });
    }
    private void worldDetail(JsonObject world) {
        List<TownForm.Action> actions = new ArrayList<>();
        boolean current = isCurrentWorld(stringValue(world, "id"));
        actions.add(action(current ? TownText.text("yat.ui.return_current_world") : TownText.text("yat.ui.browse_season"), () -> browseWorld(world)));
        if (isMayor() && !bool(world, "archived")) actions.add(action(TownText.text("yat.ui.set_current_map"), () -> activateWorld(world)));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action(TownText.text("yat.ui.edit_world_details"), () -> worldForm(world)));
        if (bool(world, "archived") && !XaeroBridge.currentMapKey().isBlank() && stringValue(world, "id").equals(gateway.worldId))
            actions.add(action(TownText.text("yat.ui.bind_historic_map"), () -> bindWorldMap(world)));
        String viewedDimension = XaeroBridge.currentMapDimension();
        if (bool(world, "archived") && stringValue(world, "id").equals(gateway.worldId) && !gateway.getBoundMapKey(viewedDimension).isEmpty())
            actions.add(action(TownText.text("yat.ui.clear_local_map"), () -> confirm(TownText.text("yat.ui.clear_local_map"), TownText.text("yat.hint.clear_local_map"),
                    () -> { gateway.unbindMap(viewedDimension); VersionClient.show(destination()); })));
        if (has("worlds.manage") && !bool(world, "archived") && !current) actions.add(action(TownText.text("yat.ui.archive_world"), () -> confirm(TownText.text("yat.title.archive_season"), TownText.text("yat.confirm.archive_season"), () -> perform("world.archive", object("worldId", stringValue(world, "id"))))));
        if (isMayor() && !current) actions.add(action(TownText.text("yat.ui.delete_world"), () -> {
            confirm(TownText.text("yat.ui.delete_world"), TownText.text("yat.confirm.delete_world", TownText.recordName(world, "")),
                    () -> perform("world.delete", object("worldId", stringValue(world, "id"))));
        }));
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
        if (!isMayor() || bool(world, "archived")) {
            TownForm.showError(VersionClient.currentScreen(), TownText.text("yat.error.set_world_permission"));
            return;
        }
        String reason = XaeroBridge.currentMapSetupBlockReason();
        String mapName = XaeroBridge.currentMapName();
        if (reason.isEmpty() && mapName.isBlank()) reason = TownText.text("yat.error.unnamed_xaero_map");
        if (!reason.isEmpty()) {
            gateway.status = reason;
            VersionClient.show(new TownForm(destination(), TownText.text("yat.error.set_current_world"), List.of(), List.of(reason), List.of(action(TownText.text("yat.ui.retry_setup"), () -> activateWorld(world)), action(TownText.text("yat.ui.enter_town"), () -> VersionClient.show(destination()))), null));
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
    private TownForm.Field memberChoice(String key, String label, String selected) { return collectionChoice(key, label, array("members"), "playerId", "name", selected); }
    private TownForm.Field collectionChoice(String key, String label, JsonArray collection, String idKey, String nameKey, String selected) {
        List<String> values = new ArrayList<>(), names = new ArrayList<>();
        for (JsonElement element : collection) { JsonObject item = element.getAsJsonObject(); values.add(stringValue(item, idKey)); names.add(nameKey.equals("name") ? TownText.recordName(item, "") : stringValue(item, nameKey)); }
        if (values.isEmpty()) { values.add(""); names.add(TownText.text("yat.empty.options")); }
        return choice(key, label, values.contains(selected) ? selected : values.get(0), values.toArray(String[]::new), names.toArray(String[]::new));
    }
    private void form(String title, List<TownForm.Field> fields, Consumer<JsonObject> submit) { VersionClient.show(new TownForm(VersionClient.currentScreen(), title, fields, submit)); }
    private void confirm(String title, String explanation, Runnable submit) { VersionClient.show(new TownForm(VersionClient.currentScreen(), title, List.of(), List.of(explanation), List.of(), ignored -> submit.run()).withSubmitLabel(title)); }
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
                if (VersionClient.currentScreen() == origin) {
                    gateway.selectTown(stringValue(result, "townId")); tab = 0; section = 0; page = 0;
                    if (action.equals("town.create")) createdTownSetup = gateway.townId;
                }
            } else if (action.equals("world.activate") || action.equals("world.delete") && gateway.worldId.equals(stringValue(data, "worldId"))) gateway.followCurrentWorld();
            else gateway.refresh();
            if (VersionClient.currentScreen() == origin) VersionClient.show(destination());
            if (action.equals("project.create")) {
                try { gateway.completeHint("map_selection"); }
                catch (IllegalArgumentException | IllegalStateException exception) { TownForm.showError(VersionClient.currentScreen(), exception.getMessage()); }
            }
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
    private boolean isNewcomer() {
        return objects(array("members")).stream().anyMatch(member -> stringValue(member, "playerId").equals(playerId()) && stringValue(member, "roleId").equals("newcomer"));
    }
    private boolean needsCurrentMapSetup() {
        JsonObject world = currentWorld();
        return isMayor() && !world.isEmpty() && stringValue(objectValue(world, "mapNames"), XaeroBridge.currentMapDimension(), stringValue(world, "mapName")).isBlank();
    }
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

    static Button projectSelectionButton(String title, int x, int y, int width, int height, Runnable action) {
        Button select = new Button(x, y, width, height, Component.literal(title), ignored -> action.run(), narration -> narration.get()) {
            private TownStyle.InteractionHighlight highlight;
            @Override public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                if (highlight == null) highlight = new TownStyle.InteractionHighlight();
                highlight.draw(graphics, getX(), getY(), getWidth(), getHeight(), active && isHovered(), active && isFocused());
            }
        };
        return select;
    }

    static String projectSummary(String state, String purpose, String owner, int width, java.util.function.ToIntFunction<String> measure) {
        String identity = state.isEmpty() ? owner : state + " · " + owner;
        if (measure.applyAsInt(identity) > width) return TownStyle.fitText(owner, width, measure);
        if (purpose.isBlank()) return TownStyle.fitText(identity, width, measure);
        String prefix = state.isEmpty() ? "" : state + " · ";
        String suffix = " · " + owner;
        int descriptionWidth = width - measure.applyAsInt(prefix + suffix);
        if (descriptionWidth < measure.applyAsInt("…")) return TownStyle.fitText(identity, width, measure);
        return prefix + TownStyle.fitText(singleLine(purpose), descriptionWidth, measure) + suffix;
    }

    static void background(GuiGraphicsExtractor graphics, int width, int height) { TownStyle.background(graphics, width, height); }
    private void moveScroll(int direction, boolean pageStep) {
        if (tab == 2 && section == 3) settingsScroll = Math.max(0, settingsScroll + direction * (pageStep ? Math.max(32, bodyBottom - bodyTop - 20) : 32));
        else if (tab == 0) homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + direction * (pageStep ? Math.max(32, bodyBottom - bodyTop - 20) : 32)));
        else page += direction;
        rebuild();
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { return loading().blocking() || super.mouseReleased(event); }
    @Override public boolean mouseDragged(MouseButtonEvent event, double horizontal, double vertical) { return loading().blocking() || super.mouseDragged(event, horizontal, vertical); }
    @Override public boolean keyReleased(KeyEvent event) { return loading().blocking() || super.keyReleased(event); }
    @Override public boolean charTyped(CharacterEvent event) { return loading().blocking() || super.charTyped(event); }
    @Override public boolean preeditUpdated(PreeditEvent event) { return loading().blocking() || super.preeditUpdated(event); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (loading().blocking()) return true;
        if (x < contentLeft || x > contentLeft + contentWidth || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        moveScroll(vertical < 0 ? 1 : -1, false);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (loading().blocking()) { if (event.key() == InputConstants.KEY_ESCAPE) onClose(); return true; }
        if ((tab == 0 || tab == 2 && section == 3) && event.isCycleFocus()) {
            int direction = event.hasShiftDown() ? -1 : 1;
            int index = children().indexOf(getFocused());
            if (index < 0) index = direction > 0 ? -1 : 0;
            for (int step = 0; step < children().size(); step++) {
                index = Math.floorMod(index + direction, children().size());
                if (!(children().get(index) instanceof AbstractWidget target) || !target.active) continue;
                if (!target.visible) {
                    if (tab == 0 && homeButtons.contains(target)) {
                        int offset = target.getY() < bodyTop ? target.getY() - bodyTop : target.getY() + target.getHeight() - bodyBottom;
                        homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + offset));
                    } else if (tab == 2 && section == 3) {
                        int top = target.getY() - (townInputs.containsValue(target) ? 11 : 0);
                        int bottom = contentBottom - 28;
                        int offset = top < contentTop ? top - contentTop : target.getY() + target.getHeight() - bottom;
                        settingsScroll = Math.max(0, settingsScroll + offset);
                    } else continue;
                    rebuild();
                }
                if (children().get(index) instanceof AbstractWidget visible && visible.visible) setInitialFocus(visible);
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
        TownStyle.heading(graphics, font, TownStyle.title(), sidebarLeft, window.top() + 10, window.width() - 52);
        TownStyle.LoadingState loading = loading();
        if (!contentReady) {
            if (loading.visible()) TownStyle.loadingOverlay(graphics, font, window, loading.startedAt());
            return;
        }
        if (loading.blocking()) { mouseX = -1; mouseY = -1; }
        Component hoveredText = renderSidebar(graphics, mouseX, mouseY);
        if (managementHeading != null) graphics.text(font, managementHeading.text(), managementHeading.x(), managementHeading.y(), managementHeading.color(), false);
        graphics.enableScissor(contentLeft, tab == 2 && section == 3 ? contentTop : bodyTop, contentLeft + contentWidth, bodyBottom);
        for (Panel panel : panels) TownStyle.panel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), panel.accented());
        for (Card card : cards) {
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), card.height(), splitProjects && stringValue(card.record(), "id").equals(selectedProjectId));
            if (tab == 2 && section == 0) {
                String joined = card.joined();
                int textX = card.x() + 38;
                int titleY = TownStyle.centeredY(card.y(), card.height(), font.lineHeight * 2 + 3);
                int detailsY = titleY + font.lineHeight + 3;
                int actionsWidth = memberActionsWidth(card.record());
                int roleWidth = Math.max(0, card.width() - 46 - actionsWidth - (actionsWidth > 0 ? 6 : 0));
                int joinedWidth = Math.min(font.width(joined), Math.max(0, roleWidth * 2 / 3));
                int nameWidth = Math.max(0, roleWidth - joinedWidth - (joined.isEmpty() ? 0 : 6));
                String name = TownStyle.fitText(card.title(), nameWidth, font::width);
                VersionClient.drawAvatar(graphics, stringValue(card.record(), "playerId"), card.title(), card.x() + 9, TownStyle.centeredY(card.y(), card.height(), 20), 20);
                graphics.text(font, name, textX, titleY, TEXT, true);
                String joinedLabel = TownStyle.fitText(joined, Math.max(0, roleWidth - font.width(name) - 6), font::width);
                graphics.text(font, joinedLabel, textX + font.width(name) + 6, titleY, MUTED, false);
                String role = TownStyle.fitText(card.subtitle(), roleWidth, font::width);
                graphics.text(font, role, textX, detailsY, MUTED, false);
                if ((!name.equals(card.title()) || !role.equals(card.subtitle()) || !joinedLabel.equals(joined)) && mouseX >= textX && mouseX < textX + Math.max(roleWidth, font.width(name)) && mouseY >= titleY && mouseY < card.y() + card.height())
                    hoveredText = Component.literal(card.title() + "\n" + card.subtitle() + "\n" + TownText.text("yat.detail.joined_at") + joined);
                continue;
            }
            boolean projectRow = tab == 1;
            int textOffset = projectRow ? 30 : 38;
            if (!projectRow) {
                int textBlockHeight = 17 + font.lineHeight;
                TownStyle.panel(graphics, card.x() + 7, TownStyle.centeredY(card.y() + 9, textBlockHeight, 23), 23, 23, false);
                if (tab == 2 && section == 1) VersionClient.drawLogo(graphics, roleIcon(card.record()), card.x() + 11, TownStyle.centeredY(card.y() + 9, textBlockHeight, 16), 16);
                else TownStyle.icon(graphics, 3, card.x() + 15, TownStyle.centeredY(card.y() + 9, textBlockHeight, 8), ACCENT);
            }
            int buttonSpace = splitProjects ? card.repair() ? 66 : 10 : card.repair() ? 120 : 66;
            int titleY = card.y() + (projectRow ? 7 : 9);
            int badgeWidth = splitProjects || card.state().isEmpty() ? 0 : Math.min(79, font.width(card.state()) + 12);
            if (card.width() - textOffset - buttonSpace - badgeWidth - 8 < 80) badgeWidth = 0;
            int textWidth = Math.max(0, card.width() - textOffset - buttonSpace - (badgeWidth > 0 ? badgeWidth + 8 : 0));
            String subtitle = badgeWidth == 0 && !card.state().isEmpty() ? card.state() + (card.subtitle().isEmpty() ? "" : " · " + card.subtitle()) : card.subtitle();
            String displayedTitle = TownStyle.fitText(card.title(), textWidth, font::width);
            String displayedSubtitle = projectRow ? projectSummary(badgeWidth == 0 ? card.state() : "", stringValue(card.record(), "purpose"), memberName(stringValue(card.record(), "leaderId")), textWidth, font::width) : TownStyle.fitText(subtitle, textWidth, font::width);
            graphics.text(font, displayedTitle, card.x() + textOffset, titleY, projectRow ? projectColor(card.record()) : TEXT, true);
            graphics.text(font, displayedSubtitle, card.x() + textOffset, card.y() + (projectRow ? 22 : 26), MUTED, false);
            if ((!displayedTitle.equals(card.title()) || !displayedSubtitle.equals(subtitle) || projectRow && stringValue(card.record(), "purpose").contains("\n"))
                    && mouseX >= card.x() + textOffset && mouseX < card.x() + textOffset + textWidth && mouseY >= titleY - 3 && mouseY < card.y() + 38 && mouseY >= bodyTop && mouseY < bodyBottom)
                hoveredText = projectRow ? projectTooltip(card.title(), card.state(), memberName(stringValue(card.record(), "leaderId")), stringValue(card.record(), "purpose"))
                        : Component.literal(card.title() + (subtitle.isEmpty() ? "" : "\n" + subtitle));
            if (badgeWidth > 0) {
                int badgeX = card.x() + card.width() - buttonSpace - badgeWidth;
                int badgeY = card.y() + (card.height() - 14) / 2;
                graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 14, TownStyle.RAISED);
                graphics.text(font, TownStyle.fitText(card.state(), badgeWidth - 12, font::width), badgeX + 6, TownStyle.centeredY(badgeY, 14, font.lineHeight), ACCENT, false);
            }
        }
        for (Line line : lines) graphics.text(font, TownStyle.fitText(line.text(), contentLeft + contentWidth - line.x() - 8, font::width), line.x(), line.y(), line.color(), false);
        for (ItemIcon icon : homeIcons) VersionClient.drawLogo(graphics, icon.item(), icon.x(), icon.y(), icon.size());
        if (previewAvatar != null) VersionClient.drawAvatar(graphics, previewAvatar.playerId(), previewAvatar.name(), previewAvatar.x(), previewAvatar.y(), 26);
        if (tab == 0) {
            VersionClient.drawAvatar(graphics, avatarX, avatarY, 28);
            if (showLogo) {
                TownStyle.panel(graphics, logoX - 4, logoY - 4, 36, 36, false);
                VersionClient.drawLogo(graphics, stringValue(objectValue(gateway.snapshot, "town"), "logo"), logoX + 2, TownStyle.centeredY(logoY + 2, 17 + font.lineHeight, 24), 24);
            }
        }
        if (showSettingsLogo) VersionClient.drawLogo(graphics, townInputs.get("logo").getValue(), settingsLogoX + 4, settingsLogoY + 4, 16);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (tab == 0 && maximumHomeScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int thumbHeight = Math.max(18, trackHeight * trackHeight / (trackHeight + maximumHomeScroll));
            int thumbY = bodyTop + (trackHeight - thumbHeight) * homeScroll / maximumHomeScroll;
            graphics.fill(contentLeft + contentWidth - 2, bodyTop, contentLeft + contentWidth, bodyBottom, TownStyle.RAISED);
            graphics.fill(contentLeft + contentWidth - 2, thumbY, contentLeft + contentWidth, thumbY + thumbHeight, MUTED);
        }
        if (loading.visible()) TownStyle.loadingOverlay(graphics, font, window, loading.startedAt());
        if (hoveredText != null && !loading.blocking()) {
            List<net.minecraft.util.FormattedCharSequence> tooltipLines = new ArrayList<>();
            for (String line : hoveredText.getString().replace("\\n", "\n").split("\n", -1))
                tooltipLines.addAll(font.split(Component.literal(line), Math.max(40, Math.min(320, width - 24))));
            graphics.setTooltipForNextFrame(tooltipLines, mouseX, mouseY);
        }
    }

    static Component projectTooltip(String title, String state, String owner, String purpose) {
        return Component.literal(title + (state.isEmpty() ? "" : "\n" + state) + "\n" + owner + (purpose.isBlank() ? "" : "\n" + purpose));
    }

    private Component renderSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Component hoveredText = null;
        graphics.enableScissor(sidebarLeft, bodyTop, contentLeft - 3, bodyBottom);
        graphics.fill(sidebarLeft, bodyTop + 4, sidebarLeft + sidebarWidth, bodyBottom - 4, TownStyle.RAISED);
        boolean spacious = bodyBottom - bodyTop >= 240;
        if (spacious) {
            JsonObject town = objectValue(gateway.snapshot, "town");
            VersionClient.drawLogo(graphics, stringValue(town, "logo", "minecraft:oak_sapling"), sidebarLeft + (sidebarWidth - 24) / 2, bodyTop + 14, 24);
            String fullName = stringValue(town, "name", "Yet Another Town");
            List<String> nameLines = TownStyle.nameLines(fullName, sidebarWidth - 12, font::width);
            for (int index = 0; index < nameLines.size(); index++) {
                String name = nameLines.get(index);
                graphics.text(font, name, sidebarLeft + (sidebarWidth - font.width(name)) / 2, bodyTop + 42 + index * font.lineHeight, TEXT, false);
            }
            if (nameLines.getLast().endsWith("…") && mouseX >= sidebarLeft + 6 && mouseX < sidebarLeft + sidebarWidth - 6 && mouseY >= bodyTop + 42 && mouseY < bodyTop + 42 + nameLines.size() * font.lineHeight)
                hoveredText = Component.literal(fullName);
            String fullWorld = TownText.recordName(currentWorld(), "—");
            String world = TownStyle.fitText(fullWorld, sidebarWidth - 16, font::width);
            graphics.fill(sidebarLeft + 6, bodyTop + 63, sidebarLeft + sidebarWidth - 6, bodyTop + 81, TownStyle.SURFACE);
            graphics.text(font, world, sidebarLeft + (sidebarWidth - font.width(world)) / 2, TownStyle.centeredY(bodyTop + 63, 18, font.lineHeight), ACCENT, false);
            if (!world.equals(fullWorld) && mouseX >= sidebarLeft + 6 && mouseX < sidebarLeft + sidebarWidth - 6 && mouseY >= bodyTop + 63 && mouseY < bodyTop + 81)
                hoveredText = Component.literal(fullWorld);
        }
        if (!gateway.loggedIn() && bodyBottom - bodyTop >= 150) {
            int bottom = bodyBottom - 22;
            String status = TownStyle.fitText(gateway.status, sidebarWidth - 16, font::width);
            graphics.text(font, status, sidebarLeft + 8, bottom, TownStyle.DANGER, false);
            if (mouseX >= sidebarLeft && mouseX < contentLeft - 3 && mouseY >= bottom && mouseY < bottom + font.lineHeight)
                hoveredText = Component.literal(gateway.status);
        }
        graphics.disableScissor();
        return hoveredText;
    }
}
