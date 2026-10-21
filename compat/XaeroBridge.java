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
import net.minecraft.network.chat.Component;
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
    }
    private static final System.Logger LOGGER = System.getLogger("Yet Another Town");
    private static Waypoint navigationWaypoint;
    private static WaypointSet navigationSet;
    private static Field selectionField;
    private static Field rightClickDimensionField;
    private static boolean menuUnavailable;
    private static BoundMap suspendedMap;
    private XaeroBridge() { }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BoundMap suspended = suspendedMap;
            if (suspended != null) {
                Screen screen = VersionClient.currentScreen();
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
            if (VersionClient.navigation() != null) options.add(option(map, options.size(), TownText.text("yat.map.stop_navigation"), VersionClient::stopNavigation));
            options.add(option(map, options.size(), gateway.config().projectOverlayEnabled ? TownText.text("yat.map.hide_projects") : TownText.text("yat.map.show_projects"), () -> {
                gateway.setProjectOverlayEnabled(!gateway.config().projectOverlayEnabled);
                ProjectHighlighter.refresh();
            }));
            MapTileSelection selection = (MapTileSelection) selectionField.get(map);
            ResourceKey<Level> dimension = (ResourceKey<Level>) rightClickDimensionField.get(map);
            if (selection == null || dimension == null) return;
            JsonObject chunks = selectionChunks(selection, dimension.identifier().toString());
            if (map.getMapProcessor() != processor() || !matchingMap(dimension)) {
                addAssociationOption(map, options, chunks);
                return;
            }
            TownGateway.Context context = gateway.context();
            MapWorld world = map.getMapProcessor().getMapWorld();
            String mapKey = currentMapKey();
            List<JsonObject> overlaps = projectsInSelection(chunks);
            String reason = creationBlockReason();
            if (reason.isEmpty() && !overlaps.isEmpty()) reason = TownText.text("yat.map.overlap_prefix") + overlaps.size() + TownText.text("yat.map.overlap_suffix") + conflictNames(overlaps);
            String createLabel = reason.isEmpty() ? TownText.text("yat.map.create_in_selection") : TownText.text("yat.map.cannot_create_prefix") + reason;
            options.add(option(map, options.size(), createLabel, () -> {
                if (!validMenu(map, context, world, mapKey, dimension) || !creationBlockReason().isEmpty()) return;
                gateway.setProjectOverlayEnabled(true);
                ProjectHighlighter.refresh();
                if (!projectsInSelection(chunks).isEmpty()) { gateway.status = TownText.text("yat.error.selection_overlap"); return; }
                if (map instanceof BoundMap bound) bound.suspendForForm();
                TownScreen.createProject(map, chunks.deepCopy());
            }).setActive(reason.isEmpty()));
            if (!isCurrentWorld() && !archived(selectedWorld())) {
                addAssociationOption(map, options, chunks);
            }
            if (!overlaps.isEmpty() && !gateway.config().projectOverlayEnabled) options.add(option(map, options.size(), TownText.text("yat.map.show_conflicts"), () -> {
                gateway.setProjectOverlayEnabled(true);
                ProjectHighlighter.refresh();
            }));
            for (JsonObject project : overlaps) {
                String id = stringValue(project, "id");
                options.add(option(map, options.size(), TownText.text("yat.map.view_project_prefix") + projectName(project), () -> {
                    if (!validMenu(map, context, world, mapKey, dimension)) return;
                    for (JsonObject current : projectsInSelection(chunks)) if (stringValue(current, "id").equals(id)) {
                        if (map instanceof BoundMap bound) bound.suspendForForm();
                        TownScreen.openProject(map, current);
                        return;
                    }
                }));
            }
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

    private static RightClickOption option(GuiMap map, int index, String label, Runnable action) {
        return new RightClickOption(label, index, map) {
            @Override public void onAction(Screen screen) {
                try { action.run(); }
                catch (IllegalArgumentException | IllegalStateException exception) { TownForm.showError(map, exception.getMessage()); }
            }
        };
    }

    private static void addAssociationOption(GuiMap map, ArrayList<RightClickOption> options, JsonObject chunks) {
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

        BoundMap(Screen returnTo, MapProcessor processor) {
            super(returnTo, returnTo, processor, Minecraft.getInstance().player);
            context = TownGateway.get().context();
            originalMapWorld = processor.getMapWorld();
            viewedDimension = originalMapWorld.getCurrentDimensionId();
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
            String[] binding = TownGateway.get().getBoundMapKey(dimension.getDimId().identifier().toString()).split("\\|", 2);
            if (binding.length == 2 && binding[0].equals(originalMapWorld.getMainId()) && dimension.getMultiworldIdsCopy().contains(binding[1]) && !binding[1].equals(dimension.getFutureMultiworldUnsynced()))
                getMapProcessor().setMultiworld(dimension, binding[1]);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (sameSelection()) bindViewedDimension(originalMapWorld.getCurrentDimension());
            ProjectHighlighter.refresh();
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }
}
