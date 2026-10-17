package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;
import xaero.map.world.MapConnectionNode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.HudSession;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.set.WaypointSet;

import static dev.yetanothertown.client.TownScreen.*;

/** 复用 Xaero 原生地图、个人底图实例与临时导航路标。 */
public final class XaeroBridge {
    private static final System.Logger LOGGER = System.getLogger("YetAnotherTown");
    private static Waypoint navigationWaypoint;
    private static WaypointSet navigationSet;
    private XaeroBridge() { }

    public static String currentMapKey() {
        MapProcessor processor = processor();
        if (processor == null || processor.getMapWorld() == null) return "";
        MapWorld world = processor.getMapWorld();
        return world.getMainId() + "|" + world.getCurrentMultiworld();
    }

    public static String currentMapDimension() {
        MapProcessor processor = processor();
        return processor == null || processor.getMapWorld() == null || processor.getMapWorld().getCurrentDimensionId() == null ? "" : processor.getMapWorld().getCurrentDimensionId().identifier().toString();
    }

    public static boolean isCurrentWorld() {
        JsonObject world = selectedWorld();
        MapProcessor processor = processor();
        if (world.isEmpty() || archived(world) || processor == null || !processor.isMapWorldUsable()) return false;
        MapWorld mapWorld = processor.getMapWorld();
        MapConnectionNode playerMap = mapWorld.getPlayerMapKey();
        return playerMap != null && TownGateway.get().getBoundMapKey(playerMap.getDimId().identifier().toString()).equals(mapWorld.getMainId() + "|" + playerMap.getMw());
    }

    public static boolean openMap(Screen returnTo, Consumer<JsonObject> chunkSelection) {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = processor();
        Minecraft minecraft = Minecraft.getInstance();
        if (processor == null || minecraft.player == null || !processor.isMapWorldUsable()) {
            gateway.status = "请先进入游戏世界，等待 Xaero 个人地图加载完成";
            return false;
        }
        JsonObject world = selectedWorld();
        String[] identity = gateway.getBoundMapKey(currentMapDimension()).split("\\|", 2);
        MapWorld mapWorld = processor.getMapWorld();
        MapDimension dimension = mapWorld.getCurrentDimension();
        if (identity.length != 2 || !identity[0].equals(mapWorld.getMainId()) || !dimension.getMultiworldIdsCopy().contains(identity[1])) {
            gateway.status = "本地未绑定或缺少此周目的当前维度底图；请在 Xaero 中选择对应已保存实例，再到世界详情绑定个人地图";
            return false;
        }
        if (chunkSelection != null && archived(world)) {
            gateway.status = "历史周目只读，不能框选新工程";
            return false;
        }
        try {
            VersionClient.show(new ProjectMap(returnTo, processor, chunkSelection, identity[1]));
            return true;
        } catch (ReflectiveOperationException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Unsupported Xaero map projection", exception);
            gateway.status = "当前 Xaero 版本地图接口不兼容，请安装 README 中指定的版本";
            return false;
        }
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

    private static MapProcessor processor() {
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

    private static final class ProjectMap extends GuiMap {
        private final Screen returnTo;
        private final Consumer<JsonObject> selection;
        private final Field cameraX;
        private final Field cameraZ;
        private final Field scale;
        private record MapState(String selection, boolean writable) { }
        private final Map<MapDimension, MapState> originalMaps = new HashMap<>();
        private final ResourceKey<Level> originalCustomDimension;
        private final boolean historical;
        private Button confirm;
        private boolean dragging;
        private boolean selected;
        private boolean writerPaused;
        private String selectedDimension = "";
        private String viewedDimension = "";
        private int startX, startZ, endX, endZ;

        ProjectMap(Screen returnTo, MapProcessor processor, Consumer<JsonObject> selection, String multiworld) throws ReflectiveOperationException {
            super(returnTo, returnTo, processor, Minecraft.getInstance().player);
            this.returnTo = returnTo;
            this.selection = selection;
            cameraX = field("cameraX"); cameraZ = field("cameraZ"); scale = field("scale");
            originalCustomDimension = processor.getMapWorld().getCustomDimensionId();
            historical = !processor.getMapWorld().getCurrentMultiworld().equals(multiworld) || archived(selectedWorld());
        }
        private static Field field(String name) throws ReflectiveOperationException {
            Field field = GuiMap.class.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new IllegalAccessException(name);
            return field;
        }
        private double value(Field field) {
            try { return field.getDouble(this); }
            catch (IllegalAccessException exception) { throw new IllegalStateException("Xaero 地图投影读取失败", exception); }
        }
        @Override public void init() {
            super.init();
            addRenderableWidget(Button.builder(Component.literal("返回小镇"), button -> onClose()).bounds(8, 8, 86, 20).build());
            if (selection != null) {
                confirm = addRenderableWidget(Button.builder(Component.literal("确认区块选区"), button -> {
                    if (!matchingMap() || !selectedDimension.equals(currentMapDimension())) return;
                    JsonObject chunks = TownGateway.object("minX", Math.min(startX, endX), "maxX", Math.max(startX, endX), "minZ", Math.min(startZ, endZ), "maxZ", Math.max(startZ, endZ), "dimension", selectedDimension);
                    onClose(); selection.accept(chunks);
                }).bounds(100, 8, 108, 20).build());
                confirm.active = selected;
            }
        }
        @Override public void added() {
            super.added();
            if (!writerPaused) {
                getMapProcessor().pushWriterPause(); writerPaused = true;
                bindViewedDimension();
            }
        }
        @Override public void onClose() { VersionClient.show(returnTo); }
        @Override public void removed() {
            try {
                if (writerPaused) {
                    synchronized (getMapProcessor()) {
                        for (var entry : originalMaps.entrySet()) {
                            getMapProcessor().setMultiworld(entry.getKey(), entry.getValue().selection());
                            entry.getKey().futureMultiworldWritable = entry.getValue().writable();
                        }
                        getMapProcessor().getMapWorld().setCustomDimensionId(originalCustomDimension);
                    }
                    originalMaps.clear();
                }
            } finally {
                if (writerPaused) { getMapProcessor().popWriterPause(); writerPaused = false; }
                super.removed();
            }
        }
        private boolean matchingMap() {
            String bound = TownGateway.get().getBoundMapKey(currentMapDimension());
            return !bound.isEmpty() && bound.equals(currentMapKey());
        }
        private void bindViewedDimension() {
            MapWorld world = getMapProcessor().getMapWorld();
            MapDimension dimension = world.getCurrentDimension();
            if (dimension == null) return;
            originalMaps.putIfAbsent(dimension, new MapState(dimension.getFutureCustomSelectedMultiworld(), dimension.futureMultiworldWritable));
            String[] binding = TownGateway.get().getBoundMapKey(dimension.getDimId().identifier().toString()).split("\\|", 2);
            if (binding.length == 2 && binding[0].equals(world.getMainId()) && dimension.getMultiworldIdsCopy().contains(binding[1]) && !binding[1].equals(dimension.getFutureMultiworldUnsynced())) {
                getMapProcessor().setMultiworld(dimension, binding[1]);
            }
        }
        private int chunk(double screenCoordinate, boolean horizontal) {
            double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
            double center = horizontal ? Minecraft.getInstance().getWindow().getWidth() / 2 : Minecraft.getInstance().getWindow().getHeight() / 2;
            double coordinate = (screenCoordinate * guiScale - center) / Math.max(0.00001, value(scale)) + value(horizontal ? cameraX : cameraZ);
            return Math.floorDiv((int) Math.floor(coordinate), 16);
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (selection != null && matchingMap() && event.button() == 0 && event.hasShiftDown() && event.y() > 34) {
                dragging = true; selected = true;
                selectedDimension = currentMapDimension();
                startX = endX = chunk(event.x(), true); startZ = endZ = chunk(event.y(), false);
                if (confirm != null) confirm.active = true;
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        @Override public boolean mouseDragged(MouseButtonEvent event, double horizontal, double vertical) {
            if (dragging) { endX = chunk(event.x(), true); endZ = chunk(event.y(), false); return true; }
            return super.mouseDragged(event, horizontal, vertical);
        }
        @Override public boolean mouseReleased(MouseButtonEvent event) {
            if (dragging && event.button() == 0) { endX = chunk(event.x(), true); endZ = chunk(event.y(), false); dragging = false; return true; }
            return super.mouseReleased(event);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            bindViewedDimension();
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            String dimension = getMapProcessor().getMapWorld().getCurrentDimensionId().identifier().toString();
            if (!dimension.equals(viewedDimension)) { selected = false; dragging = false; viewedDimension = dimension; }
            boolean matching = matchingMap();
            if (confirm != null) confirm.active = selected && matching;
            graphics.enableScissor(0, 34, width, height - 35);
            for (JsonElement element : arrayValue(TownGateway.get().snapshot, "projects")) {
                JsonObject project = element.getAsJsonObject();
                if (!matching || !stringValue(project, "dimension").equals(dimension)) continue;
                int color = switch (stringValue(project, "status")) { case "pending" -> 0xFFDAB75C; case "active" -> 0xFFE49754; case "review" -> 0xFF69B4D9; case "completed" -> 0xFF73BC78; default -> 0xFF999999; };
                if (drawArea(graphics, objectValue(project, "chunks"), color, stringValue(project, "name") + " · " + state(stringValue(project, "status")), mouseX, mouseY)) {
                    String type = switch (stringValue(project, "type")) { case "building" -> "建筑"; case "machine" -> "机器"; default -> "其他"; };
                    graphics.setTooltipForNextFrame(font, List.of(Component.literal(stringValue(project, "name") + " · " + type), Component.literal("登记：" + stringValue(project, "createdByName", stringValue(project, "createdBy"))), Component.literal("备注：" + stringValue(project, "notes"))), java.util.Optional.empty(), mouseX, mouseY);
                }
            }
            if (selected && matching) drawArea(graphics, TownGateway.object("minX", Math.min(startX, endX), "maxX", Math.max(startX, endX), "minZ", Math.min(startZ, endZ), "maxZ", Math.max(startZ, endZ)), 0xFF81D6F1, "选中区块", mouseX, mouseY);
            graphics.disableScissor();
            graphics.fill(6, height - 32, width - 6, height - 6, 0xE021261F);
            String help = historical ? "历史个人底图 · 已暂停地图写入 · 退出后恢复当前实例" : selection == null ? "小镇工程地图 · 使用 Xaero 控件切换维度、拖动和缩放" : "按住 Shift + 左键拖动框选完整区块，确认后填写工程登记";
            if (!matching) help = "当前 Xaero 实例与所选周目不一致，工程覆盖层已隐藏；请选择对应个人底图";
            graphics.text(font, font.plainSubstrByWidth(help, width - 24), 12, height - 23, 0xFFF0E4D1, false);
        }
        private boolean drawArea(GuiGraphicsExtractor graphics, JsonObject chunks, int color, String label, int mouseX, int mouseY) {
            if (chunks.isEmpty()) return false;
            double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
            double zoom = value(scale) / guiScale;
            double centerX = Minecraft.getInstance().getWindow().getWidth() / (2.0 * guiScale);
            double centerY = Minecraft.getInstance().getWindow().getHeight() / (2.0 * guiScale);
            int left = bounded((integer(chunks, "minX") * 16.0 - value(cameraX)) * zoom + centerX);
            int right = bounded(((integer(chunks, "maxX") + 1) * 16.0 - value(cameraX)) * zoom + centerX);
            int top = bounded((integer(chunks, "minZ") * 16.0 - value(cameraZ)) * zoom + centerY);
            int bottom = bounded(((integer(chunks, "maxZ") + 1) * 16.0 - value(cameraZ)) * zoom + centerY);
            if (right < 0 || bottom < 34 || left > width || top > height - 35) return false;
            graphics.fill(left, top, right, bottom, (color & 0xFFFFFF) | 0x30000000);
            graphics.outline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), color);
            graphics.text(font, label, Math.max(2, left + 3), Math.max(36, top + 3), color, true);
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
        private int bounded(double value) { return (int) Math.max(-100000, Math.min(100000, value)); }
    }
}
