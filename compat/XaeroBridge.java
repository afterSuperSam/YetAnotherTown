package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.highlight.HighlighterRegistry;
import xaero.map.world.MapConnectionNode;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;
import xaero.hud.HudSession;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.set.WaypointSet;

import static dev.yetanothertown.client.TownScreen.*;

/** 接入 Xaero 原生高亮、右键区块选区与个人历史底图。 */
public final class XaeroBridge {
    public static void renderMapTooltip(Object screen, Object graphics, Object mouseX, Object mouseY) {
        ProjectHighlighter.renderTooltip((GuiMap) screen, (net.minecraft.client.gui.GuiGraphicsExtractor) graphics, (Integer) mouseX, (Integer) mouseY);
        GuiMap map = (GuiMap) screen;
        TownGateway gateway = TownGateway.get();
        if (mapHintUnavailable || mapUiHidden() || !gateway.loggedIn() || gateway.hasCompletedHint("map_selection") || map.width < 200 || map.height < 140) return;
        try {
            if (mapHintMenuField == null) {
                mapHintMenuField = mapField("rightClickMenu");
                mapHintWaypointsField = mapField("overWaypointsMenu");
                mapHintPlayersField = mapField("overPlayersMenu");
            }
            if (mapHintMenuField.get(map) != null || mapHintWaypointsField.getBoolean(map) || mapHintPlayersField.getBoolean(map)) return;
            for (var child : map.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible && widget.isMouseOver((Integer) mouseX, (Integer) mouseY)) return;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            mapHintUnavailable = true;
            LOGGER.log(System.Logger.Level.WARNING, "Xaero map hint interface unavailable; disabling first-use map hint", exception);
            return;
        }
        String reason = creationBlockReason();
        String hint = reason.isEmpty() ? TownText.text("yat.hint.map_selection_once") : reason;
        var renderer = (GuiGraphicsExtractor) graphics;
        var font = Minecraft.getInstance().font;
        String line = font.plainSubstrByWidth(hint, Math.max(0, map.width - 40));
        renderer.text(font, line, (map.width - font.width(line)) / 2, map.height - 36, 0xFFD6A45D, true);
    }
    private static final System.Logger LOGGER = System.getLogger("Yet Another Town");
    private static Waypoint navigationWaypoint;
    private static WaypointSet navigationSet;
    private static Field selectionField;
    private static Field mapHintMenuField, mapHintWaypointsField, mapHintPlayersField;
    private static Field mapHiddenUiField;
    private static boolean mapHiddenUiChecked;
    private static Field rightClickDimensionField;
    private static boolean menuUnavailable;
    private static boolean mapHintUnavailable;
    private static BoundMap suspendedMap;
    private XaeroBridge() { }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Screen screen = VersionClient.currentScreen();
            if (screen instanceof BoundMap active && !active.sameSelection()) active.finishViewing();
            BoundMap suspended = suspendedMap;
            if (suspended != null) {
                if (screen == suspended) {
                    suspended.suspendedForForm = false;
                    suspendedMap = null;
                } else if (client.player == null || !suspended.sameSelection() || !(screen instanceof TownForm)) suspended.finishViewing();
            }
            ProjectHighlighter.refresh();
        });
    }

    public static void registerHighlighter(Object registry) {
        HighlighterRegistry nativeRegistry = (HighlighterRegistry) registry;
        if (nativeRegistry.getHighlighters().stream().noneMatch(ProjectHighlighter.class::isInstance))
            nativeRegistry.register(new ProjectHighlighter(nativeRegistry));
    }

    public static String currentMapKey() {
        MapProcessor processor = processor();
        if (processor == null || processor.getMapWorld() == null || processor.getMapWorld().getMainId() == null || processor.getMapWorld().getCurrentMultiworld() == null) return "";
        MapWorld world = processor.getMapWorld();
        return world.getMainId() + "|" + world.getCurrentMultiworld();
    }

    public static String playerMapKey() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? "" : playerMapKey(client.level.dimension().identifier().toString());
    }

    /** 玩家身份取 Xaero 主线程世界；后台绘图切换和历史底图不参与身份判断。 */
    public static String playerMapKey(String expectedDimension) {
        Minecraft client = Minecraft.getInstance();
        MapProcessor processor = processor();
        if (client.player == null || client.level == null || processor == null || !processor.isMapWorldUsable()
                || processor.mainWorld != client.level || client.player.level() != client.level
                || !client.level.dimension().identifier().toString().equals(expectedDimension)) return "";
        MapWorld world = processor.getMapWorld();
        MapConnectionNode playerMap = world == null ? null : world.getPlayerMapKey();
        return playerMap == null || playerMap.getDimId() == null || !playerMap.getDimId().identifier().toString().equals(expectedDimension)
                || playerMap.getMw() == null || world.getMainId() == null ? "" : world.getMainId() + "|" + playerMap.getMw();
    }

    public static String currentMapDimension() {
        MapProcessor processor = processor();
        return processor == null || processor.getMapWorld() == null || processor.getMapWorld().getCurrentDimensionId() == null ? "" : processor.getMapWorld().getCurrentDimensionId().identifier().toString();
    }

    /** 共享名称使用 Xaero 原始名称，避免不同客户端语言影响匹配。 */
    public static String currentMapName() {
        MapProcessor processor = processor();
        MapWorld world = processor == null ? null : processor.getMapWorld();
        MapDimension dimension = world == null ? null : world.getCurrentDimension();
        return mapName(dimension, dimension == null ? null : dimension.getCurrentMultiworld());
    }

    private static String mapName(MapDimension dimension, String map) {
        return dimension == null || map == null ? "" : dimension.getMultiworldName(map).strip();
    }

    public static boolean isCurrentWorld() {
        JsonObject world = selectedWorld();
        MapProcessor processor = processor();
        if (world.isEmpty() || archived(world) || processor == null || !processor.isMapWorldUsable()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;
        String dimension = client.level.dimension().identifier().toString();
        if (playerMapKey(dimension).isEmpty()) return false;
        MapWorld nativeWorld = processor.getMapWorld();
        MapConnectionNode playerMap = nativeWorld.getPlayerMapKey();
        return TownGateway.get().matchesSharedMap(VersionClient.serverAddress(), dimension,
                mapName(nativeWorld.getDimension(playerMap.getDimId()), playerMap.getMw()));
    }

    static boolean matchingMap(ResourceKey<Level> dimension) {
        MapProcessor processor = processor();
        if (dimension == null || processor == null || !processor.isMapWorldUsable() || selectedWorld().isEmpty()) return false;
        MapWorld world = processor.getMapWorld();
        MapDimension mapDimension = world == null ? null : world.getDimension(dimension);
        return mapDimension != null && !resolvedMapKey(mapDimension).isEmpty();
    }

    /** 当前周目按全镇规则识别；历史底图继续使用个人缓存。 */
    static String resolvedMapKey(MapDimension dimension) {
        MapWorld world = dimension.getMapWorld();
        String map = dimension.getCurrentMultiworld();
        if (world.getMainId() == null || map == null) return "";
        String id = dimension.getDimId().identifier().toString(), key = world.getMainId() + "|" + map;
        TownGateway gateway = TownGateway.get();
        if (archived(selectedWorld())) return key.equals(gateway.getBoundMapKey(id)) ? key : "";
        return gateway.matchesSharedMap(VersionClient.serverAddress(), id, mapName(dimension, map)) ? key : "";
    }

    /** 匹配镇长指定的名称后自动确认原生地图，成员无需逐人绑定。 */
    public static void synchronizeSharedMap() {
        TownGateway gateway = TownGateway.get();
        if (!currentMapSetupBlockReason().isEmpty() || suspendedMap != null || VersionClient.currentScreen() instanceof BoundMap
                || !gateway.matchesSharedMap(VersionClient.serverAddress(), currentMapDimension(), currentMapName())) return;
        MapProcessor processor = processor();
        if (!isCurrentWorld() && !confirmAssociation(processor, processor.getMapWorld().getCurrentDimension(), currentMapKey())) return;
        gateway.autoBindMap(VersionClient.serverAddress(), currentMapDimension(), playerMapKey(), currentMapName());
    }

    @SuppressWarnings("unchecked")
    public static void addMapOptions(Object screen, Object menuOptions) {
        if (menuUnavailable) return;
        GuiMap map = (GuiMap) screen;
        ArrayList<RightClickOption> options = (ArrayList<RightClickOption>) menuOptions;
        try {
            if (selectionField == null) {
                selectionField = mapField("mapTileSelection");
                rightClickDimensionField = mapField("rightClickDim");
            }
            TownGateway gateway = TownGateway.get();
            MapTileSelection selection = (MapTileSelection) selectionField.get(map);
            ResourceKey<Level> dimension = (ResourceKey<Level>) rightClickDimensionField.get(map);
            JsonObject chunks = selection == null || dimension == null ? null : selectionChunks(selection, dimension.identifier().toString());
            boolean matching = chunks != null && map.getMapProcessor() == processor() && matchingMap(dimension);
            if (matching && creationBlockReason().isEmpty() && (selection.getLeft() != selection.getRight() || selection.getTop() != selection.getBottom())) {
                try { gateway.completeHint("map_selection"); }
                catch (IllegalStateException exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "Could not save completed map selection hint", exception);
                    gateway.status = exception.getMessage();
                }
            }
            TownGateway.Context context = gateway.context();
            MapWorld world = matching ? map.getMapProcessor().getMapWorld() : null;
            String mapKey = matching ? currentMapKey() : "";
            if (matching && map instanceof BoundMap bound && bound.expandingProject != null) {
                addExpansionOptions(bound, options, chunks, context, world, mapKey, dimension);
                return;
            }
            options.add(option(map, options.size(), gateway.config().projectOverlayEnabled ? TownText.text("yat.map.hide_projects") : TownText.text("yat.map.show_projects"), () -> {
                gateway.setProjectOverlayEnabled(!gateway.config().projectOverlayEnabled);
                ProjectHighlighter.refresh();
            }));
            if (chunks == null) return;
            if (!matching) {
                addAssociationOption(map, options);
                return;
            }
            List<JsonObject> overlaps = projectsInSelection(chunks);
            if (overlaps.isEmpty()) {
                if (creationBlockReason().isEmpty()) options.add(option(map, options.size(), TownText.text("yat.map.create_in_selection"), () -> {
                    if (!validMenu(map, context, world, mapKey, dimension) || !creationBlockReason().isEmpty()) return;
                    if (!projectsInSelection(chunks).isEmpty()) throw new IllegalStateException(TownText.text("yat.error.selection_overlap"));
                    gateway.setProjectOverlayEnabled(true);
                    ProjectHighlighter.refresh();
                    if (map instanceof BoundMap bound) bound.suspendForForm();
                    TownScreen.createProject(map, chunks.deepCopy());
                }));
                return;
            }
            if (overlaps.size() > 1) {
                options.add(option(map, options.size(), TownText.text("yat.map.selection_projects", overlaps.size()), () -> {
                    if (!validMenu(map, context, world, mapKey, dimension)) return;
                    List<TownForm.Action> actions = new ArrayList<>();
                    for (JsonObject project : projectsInSelection(chunks)) {
                        String id = stringValue(project, "id");
                        actions.add(new TownForm.Action(projectName(project), () -> {
                            if (!validMenu(map, context, world, mapKey, dimension)) return;
                            for (JsonObject current : projectsInSelection(chunks)) if (id.equals(stringValue(current, "id"))) {
                                TownScreen.openProject(VersionClient.currentScreen(), current);
                                return;
                            }
                        }));
                    }
                    if (map instanceof BoundMap bound) bound.suspendForForm();
                    VersionClient.show(new TownForm(map, TownText.text("yat.map.selection_projects", actions.size()), List.of(), List.of(), actions, null));
                }));
                return;
            }
            JsonObject project = overlaps.getFirst();
            String id = stringValue(project, "id");
            Minecraft client = Minecraft.getInstance();
            if (isCurrentWorld() && client.level != null && stringValue(project, "dimension").equals(client.level.dimension().identifier().toString())) {
                options.add(option(map, options.size(), () -> TownScreen.isNavigationTarget(project, VersionClient.navigation())
                        ? TownText.text("yat.ui.stop_navigation") : TownText.text("yat.map.navigate_project"), () -> {
                    if (!validMenu(map, context, world, mapKey, dimension)) return;
                    for (JsonObject current : projectsInSelection(chunks)) if (id.equals(stringValue(current, "id"))) {
                        if (TownScreen.isNavigationTarget(current, VersionClient.navigation())) VersionClient.stopNavigation();
                        else if (!VersionClient.navigate(current)) throw new IllegalStateException(gateway.status);
                        VersionClient.show(null);
                        return;
                    }
                }));
            }
            options.add(option(map, options.size(), TownText.text("yat.map.view_project_prefix") + projectName(project), () -> {
                if (!validMenu(map, context, world, mapKey, dimension)) return;
                for (JsonObject current : projectsInSelection(chunks)) if (id.equals(stringValue(current, "id"))) {
                    if (map instanceof BoundMap bound) bound.suspendForForm();
                    TownScreen.openProject(map, current);
                    return;
                }
            }));
        } catch (ReflectiveOperationException exception) {
            menuUnavailable = true;
            LOGGER.log(System.Logger.Level.ERROR, "Unsupported Xaero native selection interface", exception);
            TownGateway.get().status = TownText.text("yat.error.selection_api");
        }
    }

    private static Field mapField(String name) throws ReflectiveOperationException {
        Field field = GuiMap.class.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field;
    }

    /** 1.42 起原生地图支持隐藏界面；更早版本始终绘制地图控件。 */
    static boolean mapUiHidden() {
        try {
            if (!mapHiddenUiChecked) {
                try { mapHiddenUiField = mapField("hiddenUI"); }
                catch (NoSuchFieldException exception) { mapHiddenUiField = null; }
                mapHiddenUiChecked = true;
            }
            return mapHiddenUiField != null && mapHiddenUiField.getBoolean(null);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.selection_api"), exception); }
    }

    private static RightClickOption option(GuiMap map, int index, String label, Runnable action) {
        return option(map, index, () -> label, action);
    }

    private static RightClickOption option(GuiMap map, int index, java.util.function.Supplier<String> label, Runnable action) {
        return new RightClickOption(label.get(), index, map) {
            @Override protected String getName() { return label.get(); }
            @Override public void onAction(Screen screen) {
                try { action.run(); }
                catch (IllegalArgumentException | IllegalStateException exception) { TownForm.showError(map, exception.getMessage()); }
            }
        };
    }

    private static void addAssociationOption(GuiMap map, ArrayList<RightClickOption> options) {
        options.add(option(map, options.size(), TownText.text("yat.title.map_settings"), () -> {
            if (map instanceof BoundMap bound) bound.suspendForForm();
            TownScreen.openMapBinding(map);
        }));
    }
    private static boolean validMenu(GuiMap map, TownGateway.Context context, MapWorld world, String key, ResourceKey<Level> dimension) {
        return TownGateway.get().isCurrent(context) && map.getMapProcessor() == processor()
                && map.getMapProcessor().getMapWorld() == world && key.equals(currentMapKey())
                && dimension.identifier().toString().equals(currentMapDimension()) && matchingMap(dimension);
    }

    private static String creationBlockReason() {
        if (archived(selectedWorld())) return TownText.text("yat.error.historic_season_read_only");
        if (!contains(arrayValue(TownGateway.get().snapshot, "permissions"), "project.create")) return TownText.text("yat.error.member_read_only");
        if (isCurrentWorld()) return "";
        if (Minecraft.getInstance().player == null) return TownText.text("yat.error.enter_season_world");
        return TownText.text("yat.error.current_map_mismatch");
    }

    /** 单地图模式不会更新玩家实例，匹配共享名称后通过原生接口确认玩家实例。 */
    static boolean confirmAssociation(MapProcessor processor, MapDimension dimension, String key) {
        synchronized (processor) {
            MapWorld world = processor.getMapWorld();
            if (!processor.isMapWorldUsable() || processor.mainWorld == null
                    || world == null || dimension == null || dimension.getMapWorld() != world || world.getCurrentDimension() != dimension
                    || !processor.mainWorld.dimension().equals(dimension.getDimId()) || world.getPotentialDimId() != dimension.getDimId()
                    || dimension.getCurrentMultiworld() == null || !dimension.getCurrentMultiworld().equals(dimension.getFutureMultiworldUnsynced())) return false;
            MapConnectionNode playerMap = world.getPlayerMapKey();
            if (playerMap != null && dimension.getDimId().equals(playerMap.getDimId()) && key.equals(world.getMainId() + "|" + playerMap.getMw())) return true;
            if (world.getFutureMultiworldType(dimension) == 0) {
                processor.toggleMultiworldType(dimension);
                if (world.getFutureMultiworldType(dimension) != 1) {
                    LOGGER.log(System.Logger.Level.WARNING, "Native map association failed: mode={0}, target={1}, playerMap={2}, confirmed=false, modeChangeRejected=true",
                            world.getFutureMultiworldType(dimension), dimension.getCurrentMultiworld(), playerMap == null ? "<none>" : playerMap.getMw());
                    return false;
                }
            }
            processor.setMultiworld(dimension, dimension.getCurrentMultiworld());
            boolean confirmed = processor.confirmMultiworld(dimension);
            playerMap = world.getPlayerMapKey();
            boolean matches = playerMap != null && dimension.getDimId().equals(playerMap.getDimId()) && key.equals(world.getMainId() + "|" + playerMap.getMw());
            if (!confirmed || !matches) LOGGER.log(System.Logger.Level.WARNING, "Native map association failed: mode={0}, target={1}, playerMap={2}, confirmed={3}",
                    world.getFutureMultiworldType(dimension), dimension.getCurrentMultiworld(), playerMap == null ? "<none>" : playerMap.getMw(), confirmed);
            return confirmed && matches;
        }
    }

    public static String currentMapSetupBlockReason() {
        if (suspendedMap != null || VersionClient.currentScreen() instanceof BoundMap)
            return TownText.text("yat.error.close_historic_map");
        Minecraft client = Minecraft.getInstance();
        MapProcessor processor = processor();
        MapWorld world = processor == null ? null : processor.getMapWorld();
        MapDimension dimension = world == null ? null : world.getCurrentDimension();
        boolean live = client.player != null && client.level != null && processor != null && processor.isMapWorldUsable()
                && processor.mainWorld == client.level && client.player.level() == client.level;
        return associationBlockReason(TownGateway.object("archived", false), live, client.level == null ? "" : client.level.dimension().identifier().toString(),
                currentMapDimension(), dimension == null ? null : dimension.getCurrentMultiworld(), dimension == null ? null : dimension.getFutureMultiworldUnsynced());
    }

    static String associationBlockReason(JsonObject world, boolean live, String playerDimension, String viewedDimension, String current, String future) {
        if (world.isEmpty()) return TownText.text("yat.error.select_current_season");
        if (archived(world)) return TownText.text("yat.error.bind_historic_season");
        if (!live) return TownText.text("yat.error.wait_player_map");
        if (!playerDimension.equals(viewedDimension)) return TownText.text("yat.error.bind_current_dimension");
        if (current == null || !current.equals(future)) return TownText.text("yat.error.wait_map_switch");
        return "";
    }

    static JsonObject selectionChunks(MapTileSelection selection, String dimension) {
        return TownGateway.object("minX", selection.getLeft(), "maxX", selection.getRight(), "minZ", selection.getTop(), "maxZ", selection.getBottom(), "dimension", dimension);
    }

    private static List<JsonObject> projectsInSelection(JsonObject chunks) {
        List<JsonObject> result = new ArrayList<>();
        TownGateway gateway = TownGateway.get();
        for (JsonElement element : arrayValue(gateway.snapshot, "projects")) {
            JsonObject project = element.getAsJsonObject();
            if (!gateway.worldId.equals(stringValue(project, "worldId")) || !stringValue(chunks, "dimension").equals(stringValue(project, "dimension"))) continue;
            JsonObject area = objectValue(project, "chunks");
            if (!area.isEmpty() && ProjectHighlighter.intersects(integer(chunks, "minX"), integer(chunks, "maxX"), integer(chunks, "minZ"), integer(chunks, "maxZ"), integer(area, "minX"), integer(area, "maxX"), integer(area, "minZ"), integer(area, "maxZ"))) result.add(project);
        }
        return result;
    }

    static String projectName(JsonObject project) {
        String name = TownText.recordName(project, "");
        return name.isBlank() ? TownText.text("yat.project.unnamed_reserved") : name;
    }

    private static String conflictNames(List<JsonObject> projects) {
        return projects.stream().limit(2).map(project -> ProjectHighlighter.summary(projectName(project), 14)).collect(java.util.stream.Collectors.joining("、"))
                + (projects.size() > 2 ? TownText.text("yat.label.and_more") : "");
    }

    /** 定位沿用原生相机与底图，临时切换维度在关闭地图后恢复。 */
    public static boolean openProjectMap(Screen parent, JsonObject project) {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = processor();
        if (processor == null || !processor.isMapWorldUsable() || Minecraft.getInstance().player == null
                || !gateway.worldId.equals(stringValue(project, "worldId"))) {
            gateway.status = TownText.text("yat.error.wait_personal_map"); return false;
        }
        MapWorld world = processor.getMapWorld();
        MapDimension dimension = world == null ? null : world.getDimensionsList().stream()
                .filter(value -> value.getDimId().identifier().toString().equals(stringValue(project, "dimension"))).findFirst().orElse(null);
        if (dimension == null) { gateway.status = TownText.text("yat.error.missing_season_map"); return false; }
        String selectedMap = null;
        if (archived(selectedWorld())) {
            String[] identity = gateway.getBoundMapKey(stringValue(project, "dimension")).split("\\|", 2);
            if (identity.length == 2 && identity[0].equals(world.getMainId()) && dimension.getMultiworldIdsCopy().contains(identity[1])) selectedMap = identity[1];
        } else {
            for (String candidate : dimension.getMultiworldIdsCopy()) {
                if (!gateway.matchesSharedMap(VersionClient.serverAddress(), stringValue(project, "dimension"), mapName(dimension, candidate))) continue;
                selectedMap = candidate;
                if (candidate.equals(dimension.getCurrentMultiworld())) break;
            }
        }
        if (selectedMap == null) { gateway.status = TownText.text("yat.error.missing_season_map"); return false; }
        try { CameraFields.verify(); }
        catch (IllegalStateException exception) { gateway.status = exception.getMessage(); return false; }
        gateway.setProjectOverlayEnabled(true);
        ProjectHighlighter.invalidate();
        if (suspendedMap != null) suspendedMap.finishViewing();
        VersionClient.show(new BoundMap(parent, processor, dimension.getDimId(), selectedMap, project));
        return true;
    }

    public static void startProjectExpansion(Screen parent, JsonObject project) {
        String reason = expansionBlockReason(project);
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        if (!openProjectMap(parent instanceof TownForm form ? form.parentScreen() : parent, project)) throw new IllegalStateException(TownGateway.get().status);
        ((BoundMap) VersionClient.currentScreen()).expandingProject = project.deepCopy();
    }

    private static String expansionBlockReason(JsonObject project) {
        TownGateway gateway = TownGateway.get();
        if (archived(selectedWorld())) return TownText.text("yat.error.historic_season_read_only");
        if (!gateway.worldId.equals(stringValue(project, "worldId")) || !isCurrentWorld()) return TownText.text("yat.error.current_map_mismatch");
        boolean manager = contains(arrayValue(gateway.snapshot, "permissions"), "project.approve");
        boolean owner = contains(arrayValue(gateway.snapshot, "permissions"), "project.edit")
                && stringValue(gateway.player(), "id").equals(stringValue(project, "leaderId"));
        return manager || owner ? "" : TownText.text("yat.error.project_permission_denied");
    }

    private static void addExpansionOptions(BoundMap map, ArrayList<RightClickOption> options, JsonObject chunks,
                                           TownGateway.Context context, MapWorld world, String mapKey, ResourceKey<Level> dimension) {
        JsonObject project = map.expandingProject;
        String id = stringValue(project, "id");
        List<JsonObject> conflicts = projectsInSelection(chunks).stream().filter(value -> !id.equals(stringValue(value, "id"))).toList();
        String reason = expansionBlockReason(project);
        if (reason.isEmpty() && !stringValue(project, "dimension").equals(stringValue(chunks, "dimension"))) reason = TownText.text("yat.error.project_region_scope");
        if (reason.isEmpty() && !conflicts.isEmpty()) reason = TownText.text("yat.error.region_overlap_named", conflictNames(conflicts));
        options.add(option(map, options.size(), reason.isEmpty() ? TownText.text("yat.map.confirm_resize", projectName(project)) : reason, () -> {
            if (!validMenu(map, context, world, mapKey, dimension)) return;
            String blocked = expansionBlockReason(project);
            if (!blocked.isEmpty()) throw new IllegalStateException(blocked);
            List<JsonObject> currentConflicts = projectsInSelection(chunks).stream().filter(value -> !id.equals(stringValue(value, "id"))).toList();
            if (!currentConflicts.isEmpty()) throw new IllegalStateException(TownText.text("yat.error.region_overlap_named", conflictNames(currentConflicts)));
            map.suspendForForm();
            VersionClient.show(new TownForm(map, TownText.text("yat.ui.expand_project"), List.of(),
                    List.of(TownText.text("yat.map.resize_summary", projectName(project), integer(chunks, "minX"), integer(chunks, "minZ"), integer(chunks, "maxX"), integer(chunks, "maxZ"))), List.of(), ignored -> {
                JsonObject payload = TownGateway.object("townId", TownGateway.get().townId, "id", id);
                JsonObject region = chunks.deepCopy(); region.remove("dimension"); payload.add("chunks", region);
                Screen origin = VersionClient.currentScreen();
                TownForm.track(TownGateway.get().request("project.update", payload).thenCompose(result -> TownGateway.get().refresh()).thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (!TownGateway.get().isCurrent(context) || VersionClient.currentScreen() != origin) return;
                    map.expandingProject = null;
                    ProjectHighlighter.invalidate(); VersionClient.show(map); ProjectHighlighter.focusProject(id);
                })));
            }));
        }).setActive(reason.isEmpty()));
        options.add(option(map, options.size(), TownText.text("yat.map.cancel_resize"), () -> map.expandingProject = null));
    }

    private static final class CameraFields {
        private static Field x, z, reset, attached, destination, animationX, animationZ, division;
        static void verify() {
            if (x != null) return;
            try {
                Field nextX = mapField("cameraX"); z = mapField("cameraZ"); reset = mapField("shouldResetCameraPos");
                // 1.42 之前没有跟随相机模式，原生定位只需取消位置复位。
                try { attached = mapField("attachedCamera"); }
                catch (NoSuchFieldException exception) { attached = null; }
                destination = mapField("cameraDestination");
                animationX = mapField("cameraDestinationAnimX"); animationZ = mapField("cameraDestinationAnimZ"); division = mapField("prevPlayerDimDiv"); x = nextX;
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.map_focus_incompatible"), exception); }
        }
    }

    private static void centerMap(GuiMap map, JsonObject chunks) {
        CameraFields.verify();
        try {
            CameraFields.x.setDouble(map, ((double) integer(chunks, "minX") + integer(chunks, "maxX") + 1) * 8);
            CameraFields.z.setDouble(map, ((double) integer(chunks, "minZ") + integer(chunks, "maxZ") + 1) * 8);
            CameraFields.reset.setBoolean(map, false);
            if (CameraFields.attached != null) CameraFields.attached.setBoolean(null, false);
            CameraFields.division.setDouble(map, 0);
            CameraFields.destination.set(map, null); CameraFields.animationX.set(map, null); CameraFields.animationZ.set(map, null);
        } catch (IllegalAccessException exception) { throw new IllegalStateException(TownText.text("yat.error.map_focus_incompatible"), exception); }
    }

    public static boolean openMap(Screen returnTo) {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = processor();
        Minecraft minecraft = Minecraft.getInstance();
        if (processor == null || minecraft.player == null || !processor.isMapWorldUsable()) {
            gateway.status = TownText.text("yat.error.wait_personal_map");
            return false;
        }
        JsonObject world = selectedWorld();
        if (!world.isEmpty() && !archived(world)) {
            if (suspendedMap != null) suspendedMap.finishViewing();
            if (returnTo instanceof GuiMap existing && existing.getMapProcessor() == processor && !(existing instanceof BoundMap)) {
                VersionClient.show(existing);
            } else VersionClient.show(new GuiMap(returnTo, returnTo, processor, minecraft.player));
            return true;
        }
        String[] identity = gateway.getBoundMapKey(currentMapDimension()).split("\\|", 2);
        MapWorld mapWorld = processor.getMapWorld();
        MapDimension dimension = mapWorld.getCurrentDimension();
        if (world.isEmpty() || dimension == null || identity.length != 2 || !identity[0].equals(mapWorld.getMainId()) || !dimension.getMultiworldIdsCopy().contains(identity[1])) {
            gateway.status = TownText.text("yat.error.missing_season_map");
            return false;
        }
        if (returnTo instanceof GuiMap existing && existing.getMapProcessor() == processor
                && (!(existing instanceof BoundMap bound) || bound.sameSelection())) {
            VersionClient.show(existing);
            return true;
        }
        if (suspendedMap != null) suspendedMap.finishViewing();
        VersionClient.show(new BoundMap(returnTo, processor));
        return true;
    }

    public static boolean navigate(JsonObject record) {
        TownGateway gateway = TownGateway.get();
        Minecraft minecraft = Minecraft.getInstance();
        if (!isCurrentWorld() || !stringValue(record, "worldId").equals(gateway.worldId)) {
            gateway.status = TownText.text("yat.error.navigation_other_season");
            return false;
        }
        if (minecraft.level == null || !stringValue(record, "dimension").equals(minecraft.level.dimension().identifier().toString())) {
            gateway.status = TownText.text("yat.error.target_dimension", stringValue(record, "dimension"));
            return false;
        }
        HudSession session = HudSession.getCurrentSession();
        if (session == null) { gateway.status = TownText.text("yat.error.minimap_not_loaded"); return false; }
        MinimapSession minimap = session.getSession(BuiltInHudModules.MINIMAP);
        if (minimap == null || minimap.getWorldManager().getCurrentWorld() == null) { gateway.status = TownText.text("yat.error.minimap_world_missing"); return false; }
        JsonObject chunks = objectValue(record, "chunks");
        int x = record.has("x") ? integer(record, "x") : (integer(chunks, "minX") + integer(chunks, "maxX")) * 8 + 8;
        int z = record.has("z") ? integer(record, "z") : (integer(chunks, "minZ") + integer(chunks, "maxZ")) * 8 + 8;
        int y = record.has("y") ? integer(record, "y") : minecraft.player.getBlockY();
        if (navigationSet != null && navigationWaypoint != null) navigationSet.remove(navigationWaypoint);
        navigationWaypoint = new Waypoint(x, y, z, stringValue(record, "name").isEmpty() ? TownText.text("yat.label.town_waypoint") : stringValue(record, "name"), "T", WaypointColor.GOLD, WaypointPurpose.DESTINATION, true, record.has("y"));
        navigationSet = minimap.getWorldManager().getCurrentWorld().getCurrentWaypointSet();
        navigationSet.add(navigationWaypoint);
        gateway.status = TownText.text("yat.status.waypoint_added");
        return true;
    }

    public static void clearNavigation() {
        if (navigationSet != null && navigationWaypoint != null) navigationSet.remove(navigationWaypoint);
        navigationSet = null;
        navigationWaypoint = null;
    }

    static boolean navigationWaypointRemoved() {
        if (navigationSet == null || navigationWaypoint == null) return false;
        for (Waypoint waypoint : navigationSet.getWaypoints()) if (waypoint == navigationWaypoint) return false;
        return true;
    }

    static MapProcessor processor() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        return session == null ? null : session.getMapProcessor();
    }

    private static JsonObject selectedWorld() {
        TownGateway gateway = TownGateway.get();
        for (JsonElement element : arrayValue(gateway.snapshot, "worlds")) {
            JsonObject world = element.getAsJsonObject();
            if (stringValue(world, "id").equals(gateway.worldId)) return world;
        }
        return new JsonObject();
    }
    private static boolean archived(JsonObject world) { return world.has("archived") && world.get("archived").getAsBoolean(); }

    /** 历史窗口只管理实例与写入暂停，渲染和区块选择完全交给原生地图。 */
    private static final class BoundMap extends GuiMap {
        private record MapState(String selection, boolean writable) { }
        private final Map<MapDimension, MapState> originalMaps = new HashMap<>();
        private final MapWorld originalMapWorld;
        private final TownGateway.Context context;
        private ResourceKey<Level> originalCustomDimension;
        private ResourceKey<Level> viewedDimension;
        private boolean writerPaused;
        private boolean suspendedForForm;
        private JsonObject focusProject;
        private JsonObject expandingProject;
        private ResourceKey<Level> focusDimension;
        private String focusMap;
        private Boolean originalAttachedCamera;

        BoundMap(Screen returnTo, MapProcessor processor) {
            super(returnTo, returnTo, processor, Minecraft.getInstance().player);
            context = TownGateway.get().context();
            originalMapWorld = processor.getMapWorld();
            viewedDimension = originalMapWorld.getCurrentDimensionId();
        }

        BoundMap(Screen returnTo, MapProcessor processor, ResourceKey<Level> dimension, String map, JsonObject project) {
            this(returnTo, processor);
            viewedDimension = dimension; focusDimension = dimension; focusMap = map; focusProject = project.deepCopy();
        }

        @Override public void init() {
            beginViewing();
            super.init();
        }

        @Override public void added() {
            super.added();
            beginViewing();
        }

        private void beginViewing() {
            suspendedForForm = false;
            if (suspendedMap == this) suspendedMap = null;
            if (writerPaused || !sameSelection()) return;
            originalCustomDimension = originalMapWorld.getCustomDimensionId();
            if (focusDimension != null && CameraFields.attached != null) {
                try { originalAttachedCamera = CameraFields.attached.getBoolean(null); }
                catch (IllegalAccessException exception) { throw new IllegalStateException(TownText.text("yat.error.map_focus_incompatible"), exception); }
            }
            getMapProcessor().pushWriterPause();
            writerPaused = true;
            originalMapWorld.setCustomDimensionId(viewedDimension);
            bindViewedDimension(originalMapWorld.getDimension(viewedDimension));
        }

        @Override public void removed() {
            try {
                if (!suspendedForForm) finishViewing();
            } finally {
                super.removed();
            }
        }

        private void suspendForForm() {
            suspendedForForm = true;
            suspendedMap = this;
        }

        private void finishViewing() {
            try {
                if (writerPaused && getMapProcessor().getMapWorld() == originalMapWorld) {
                    viewedDimension = originalMapWorld.getCurrentDimensionId();
                    synchronized (getMapProcessor()) {
                        for (var entry : originalMaps.entrySet()) {
                            getMapProcessor().setMultiworld(entry.getKey(), entry.getValue().selection());
                            entry.getKey().futureMultiworldWritable = entry.getValue().writable();
                        }
                        originalMapWorld.setCustomDimensionId(originalCustomDimension);
                    }
                }
                originalMaps.clear();
            } finally {
                if (writerPaused) { getMapProcessor().popWriterPause(); writerPaused = false; }
                if (originalAttachedCamera != null) {
                    try { CameraFields.attached.setBoolean(null, originalAttachedCamera); }
                    catch (IllegalAccessException exception) { LOGGER.log(System.Logger.Level.ERROR, "Could not restore map camera mode", exception); }
                    originalAttachedCamera = null;
                }
                suspendedForForm = false;
                if (suspendedMap == this) suspendedMap = null;
            }
        }

        private boolean sameSelection() {
            return TownGateway.get().isCurrent(context) && getMapProcessor() == processor() && getMapProcessor().getMapWorld() == originalMapWorld;
        }

        private void bindViewedDimension(MapDimension dimension) {
            if (!sameSelection() || dimension == null) return;
            originalMaps.putIfAbsent(dimension, new MapState(dimension.getFutureCustomSelectedMultiworld(), dimension.futureMultiworldWritable));
            if (dimension.getDimId().equals(focusDimension) && focusMap != null) {
                if (!focusMap.equals(dimension.getFutureMultiworldUnsynced())) getMapProcessor().setMultiworld(dimension, focusMap);
                return;
            }
            String[] binding = TownGateway.get().getBoundMapKey(dimension.getDimId().identifier().toString()).split("\\|", 2);
            if (binding.length == 2 && binding[0].equals(originalMapWorld.getMainId()) && dimension.getMultiworldIdsCopy().contains(binding[1]) && !binding[1].equals(dimension.getFutureMultiworldUnsynced()))
                getMapProcessor().setMultiworld(dimension, binding[1]);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (sameSelection()) bindViewedDimension(originalMapWorld.getCurrentDimension());
            ProjectHighlighter.refresh();
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            if (focusProject != null && sameSelection() && focusDimension.equals(originalMapWorld.getCurrentDimensionId())
                    && focusMap.equals(originalMapWorld.getCurrentDimension().getCurrentMultiworld())) {
                centerMap(this, objectValue(focusProject, "chunks"));
                ProjectHighlighter.refresh(); ProjectHighlighter.focusProject(stringValue(focusProject, "id"));
                focusProject = null;
            }
            if (expandingProject != null) {
                String hint = font.plainSubstrByWidth(TownText.text("yat.map.resize_hint", projectName(expandingProject)), Math.max(40, width - 80));
                int left = (width - font.width(hint)) / 2, top = height - 48;
                graphics.fill(left - 6, top - 4, left + font.width(hint) + 6, top + font.lineHeight + 4, 0xD0202020);
                graphics.text(font, hint, left, top, TownStyle.ACCENT, false);
            }
        }
    }
}
