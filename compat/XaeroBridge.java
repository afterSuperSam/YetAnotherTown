package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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

    public static boolean isCurrentWorld() {
        JsonObject world = selectedWorld();
        MapProcessor processor = processor();
        if (world.isEmpty() || archived(world) || processor == null || !processor.isMapWorldUsable()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;
        String dimension = client.level.dimension().identifier().toString();
        String key = playerMapKey(dimension);
        return !key.isEmpty() && TownGateway.get().getBoundMapKey(dimension).equals(key);
    }

    static boolean matchingMap(ResourceKey<Level> dimension) {
        MapProcessor processor = processor();
        if (dimension == null || processor == null || !processor.isMapWorldUsable() || selectedWorld().isEmpty()) return false;
        MapWorld world = processor.getMapWorld();
        MapDimension mapDimension = world == null ? null : world.getDimension(dimension);
        return mapDimension != null && world.getMainId() != null && mapDimension.getCurrentMultiworld() != null
                && TownGateway.get().getBoundMapKey(dimension.identifier().toString()).equals(world.getMainId() + "|" + mapDimension.getCurrentMultiworld());
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
            options.add(option(map, options.size(), gateway.config().projectOverlayEnabled ? "隐藏小镇工程区域" : "显示小镇工程区域", () -> {
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
            if (reason.isEmpty() && !overlaps.isEmpty()) reason = "重叠 " + overlaps.size() + " 个工程：" + conflictNames(overlaps);
            String createLabel = reason.isEmpty() ? "在选区创建工程" : "不能创建：" + reason;
            options.add(option(map, options.size(), createLabel, () -> {
                if (!validMenu(map, context, world, mapKey, dimension) || !creationBlockReason().isEmpty()) return;
                gateway.setProjectOverlayEnabled(true);
                ProjectHighlighter.refresh();
                if (!projectsInSelection(chunks).isEmpty()) { gateway.status = "选区与已有工程重叠，请重新选择空闲区块"; return; }
                if (map instanceof BoundMap bound) bound.suspendForForm();
                TownScreen.createProject(map, chunks.deepCopy());
            }).setActive(reason.isEmpty()));
            if (!isCurrentWorld() && !archived(selectedWorld())) {
                addAssociationOption(map, options, chunks);
            }
            if (!overlaps.isEmpty() && !gateway.config().projectOverlayEnabled) options.add(option(map, options.size(), "显示选区冲突区域", () -> {
                gateway.setProjectOverlayEnabled(true);
                ProjectHighlighter.refresh();
            }));
            for (JsonObject project : overlaps) {
                String id = stringValue(project, "id");
                options.add(option(map, options.size(), "查看工程：" + projectName(project), () -> {
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
            TownGateway.get().status = "当前 Xaero 版本的原生选区接口不兼容，请使用 README 指定版本";
        }
    }

    private static Field mapField(String name) throws ReflectiveOperationException {
        Field field = GuiMap.class.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field;
    }

    private static RightClickOption option(GuiMap map, int index, String label, Runnable action) {
        return new RightClickOption(label, index, map) {
            @Override public void onAction(Screen screen) { action.run(); }
        };
    }

    private static void addAssociationOption(GuiMap map, ArrayList<RightClickOption> options, JsonObject chunks) {
        TownGateway gateway = TownGateway.get();
        TownGateway.Context context = gateway.context();
        MapProcessor processor = map.getMapProcessor();
        MapWorld world = processor.getMapWorld();
        String key = currentMapKey(), dimension = currentMapDimension();
        String reason = currentAssociationBlockReason();
        options.add(option(map, options.size(), reason.isEmpty() ? "关联此地图并继续" : "不能关联：" + reason, () -> {
            if (!gateway.isCurrent(context) || processor != processor() || processor.getMapWorld() != world
                    || !key.equals(currentMapKey()) || !dimension.equals(currentMapDimension()) || !dimension.equals(stringValue(chunks, "dimension"))) return;
            if (associateCurrentMap(map)) {
                if (!gateway.isCurrent(context) || !key.equals(currentMapKey()) || !dimension.equals(currentMapDimension())) return;
                if (creationBlockReason().isEmpty()) {
                    gateway.setProjectOverlayEnabled(true);
                    ProjectHighlighter.refresh();
                    if (projectsInSelection(chunks).isEmpty()) {
                        if (map instanceof BoundMap bound) bound.suspendForForm();
                        TownScreen.createProject(map, chunks.deepCopy());
                        return;
                    }
                    gateway.status = "地图已关联；选区与已有工程重叠，请重新选择空闲区块";
                } else gateway.status = "地图已关联；" + creationBlockReason();
                return;
            }
            if (map instanceof BoundMap bound) bound.suspendForForm();
            VersionClient.show(new TownForm(map, "关联地图", List.of(), List.of(gateway.status), List.of(), null));
        }).setActive(reason.isEmpty()));
    }

    private static boolean validMenu(GuiMap map, TownGateway.Context context, MapWorld world, String key, ResourceKey<Level> dimension) {
        return TownGateway.get().isCurrent(context) && map.getMapProcessor() == processor()
                && map.getMapProcessor().getMapWorld() == world && key.equals(currentMapKey())
                && dimension.identifier().toString().equals(currentMapDimension()) && matchingMap(dimension);
    }

    private static String creationBlockReason() {
        if (archived(selectedWorld())) return "历史周目只读";
        if (!contains(arrayValue(TownGateway.get().snapshot, "permissions"), "project.create")) return "当前成员只读";
        if (isCurrentWorld()) return "";
        if (Minecraft.getInstance().player == null) return "请先进入此周目的游戏世界";
        return "请关联此地图后继续";
    }

    /** 一次显式关联同时保存个人绑定并确认原生玩家实例，不重新选择或清空周目资料。 */
    public static boolean associateCurrentMap(Screen returnTo) {
        TownGateway gateway = TownGateway.get();
        String reason = currentAssociationBlockReason();
        if (!reason.isEmpty()) { gateway.status = reason; return false; }
        MapProcessor processor = processor();
        MapWorld world = processor.getMapWorld();
        MapDimension dimension = world.getCurrentDimension();
        String dimensionId = dimension.getDimId().identifier().toString(), key = currentMapKey();
        TownGateway.Context context = gateway.context();
        synchronized (processor) {
            if (!gateway.isCurrent(context) || processor != processor() || world != processor.getMapWorld()
                    || !key.equals(currentMapKey()) || !currentAssociationBlockReason().isEmpty()) {
                gateway.status = "地图或周目已变化，请在当前地图重新关联";
                return false;
            }
            if (!saveAssociation(gateway, dimensionId, key, () -> {
                if (key.equals(playerMapKey(dimensionId))) return true;
                processor.setMultiworld(dimension, dimension.getCurrentMultiworld());
                return processor.confirmMultiworld(dimension) && key.equals(playerMapKey(dimensionId));
            })) return false;
            if (returnTo instanceof BoundMap bound) bound.keepConfirmedMap(dimension);
        }
        ProjectHighlighter.refresh();
        if (!(returnTo instanceof GuiMap)) return openMap(returnTo);
        if (VersionClient.currentScreen() != returnTo) VersionClient.show(returnTo);
        return true;
    }

    static boolean saveAssociation(TownGateway gateway, String dimension, String key, BooleanSupplier confirmPlayerMap) {
        String previous = gateway.getBoundMapKey(dimension);
        TownGateway.Context context = gateway.context();
        boolean saved = false;
        try {
            gateway.bindCurrentMap(dimension, key);
            saved = true;
            if (!confirmPlayerMap.getAsBoolean()) throw new IllegalStateException("Xaero 尚未确认此玩家地图，请等待底图加载完成后重试");
            if (!gateway.isCurrent(context)) throw new IllegalStateException("周目或登录状态已变化，请重新关联当前地图");
            gateway.status = "地图已关联";
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (saved && gateway.isCurrent(context)) {
                try {
                    if (previous.isEmpty()) gateway.unbindMap(dimension); else gateway.bindCurrentMap(dimension, previous);
                } catch (IllegalArgumentException | IllegalStateException rollback) {
                    exception.addSuppressed(rollback);
                    LOGGER.log(System.Logger.Level.ERROR, "Could not restore the previous personal map binding", exception);
                    gateway.status = "关联未完成，恢复原绑定失败；请检查客户端配置文件是否可写";
                    return false;
                }
            }
            gateway.status = exception.getMessage();
            return false;
        }
    }

    private static String currentAssociationBlockReason() {
        Minecraft client = Minecraft.getInstance();
        MapProcessor processor = processor();
        MapWorld world = processor == null ? null : processor.getMapWorld();
        MapDimension dimension = world == null ? null : world.getCurrentDimension();
        boolean live = client.player != null && client.level != null && processor != null && processor.isMapWorldUsable()
                && processor.mainWorld == client.level && client.player.level() == client.level;
        return associationBlockReason(selectedWorld(), live, client.level == null ? "" : client.level.dimension().identifier().toString(),
                currentMapDimension(), dimension == null ? null : dimension.getCurrentMultiworld(), dimension == null ? null : dimension.getFutureMultiworldUnsynced());
    }

    static String associationBlockReason(JsonObject world, boolean live, String playerDimension, String viewedDimension, String current, String future) {
        if (world.isEmpty()) return "请先选择当前周目，等待资料加载完成";
        if (archived(world)) return "历史周目只读，请使用历史地图关联";
        if (!live) return "请先进入游戏，等待 Xaero 玩家地图加载完成";
        if (!playerDimension.equals(viewedDimension)) return "请切回玩家当前维度后关联";
        if (current == null || !current.equals(future)) return "底图正在切换，请加载完成后关联";
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
        String name = stringValue(project, "name");
        return name.isBlank() ? "未命名预留工程" : name;
    }

    private static String conflictNames(List<JsonObject> projects) {
        return projects.stream().limit(2).map(project -> ProjectHighlighter.summary(projectName(project), 14)).collect(java.util.stream.Collectors.joining("、"))
                + (projects.size() > 2 ? "等" : "");
    }

    public static boolean openMap(Screen returnTo) {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = processor();
        Minecraft minecraft = Minecraft.getInstance();
        if (processor == null || minecraft.player == null || !processor.isMapWorldUsable()) {
            gateway.status = "请先进入游戏世界，等待 Xaero 个人地图加载完成";
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
            gateway.status = "本地未绑定或缺少此周目的当前维度底图；请在 Xaero 中选择对应已保存实例，再到世界详情绑定个人地图";
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
            gateway.status = "历史或其他周目的目标只能查看，不能导航";
            return false;
        }
        if (minecraft.level == null || !stringValue(record, "dimension").equals(minecraft.level.dimension().identifier().toString())) {
            gateway.status = "目标位于 " + stringValue(record, "dimension") + "，请先进入该维度";
            return false;
        }
        HudSession session = HudSession.getCurrentSession();
        if (session == null) { gateway.status = "Xaero Minimap 尚未加载"; return false; }
        MinimapSession minimap = session.getSession(BuiltInHudModules.MINIMAP);
        if (minimap == null || minimap.getWorldManager().getCurrentWorld() == null) { gateway.status = "Xaero Minimap 还没有当前世界资料"; return false; }
        JsonObject chunks = objectValue(record, "chunks");
        int x = record.has("x") ? integer(record, "x") : (integer(chunks, "minX") + integer(chunks, "maxX")) * 8 + 8;
        int z = record.has("z") ? integer(record, "z") : (integer(chunks, "minZ") + integer(chunks, "maxZ")) * 8 + 8;
        int y = record.has("y") ? integer(record, "y") : minecraft.player.getBlockY();
        if (navigationSet != null && navigationWaypoint != null) navigationSet.remove(navigationWaypoint);
        navigationWaypoint = new Waypoint(x, y, z, stringValue(record, "name").isEmpty() ? "小镇目标" : stringValue(record, "name"), "T", WaypointColor.GOLD, WaypointPurpose.DESTINATION, true, record.has("y"));
        navigationSet = minimap.getWorldManager().getCurrentWorld().getCurrentWaypointSet();
        navigationSet.add(navigationWaypoint);
        gateway.status = "已在 Xaero Minimap 添加临时导航目标";
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

        private void keepConfirmedMap(MapDimension dimension) {
            originalMaps.put(dimension, new MapState(dimension.getFutureCustomSelectedMultiworld(), dimension.futureMultiworldWritable));
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
