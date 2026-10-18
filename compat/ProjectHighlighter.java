package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.highlight.ChunkHighlighter;
import xaero.map.highlight.HighlighterRegistry;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import static dev.yetanothertown.client.TownScreen.*;

/** 将小镇区块交给 Xaero 的底图纹理管线，并为原生悬浮提示提供资料。 */
final class ProjectHighlighter extends ChunkHighlighter {
    private record Area(String dimension, int minX, int maxX, int minZ, int maxZ, int color, String name, String tooltip) {
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean touchesRegion(int x, int z) { return intersects(minX, maxX, minZ, maxZ, x << 5, (x << 5) + 31, z << 5, (z << 5) + 31); }
        boolean samePaint(Area other) {
            return dimension.equals(other.dimension) && minX == other.minX && maxX == other.maxX && minZ == other.minZ && maxZ == other.maxZ && color == other.color;
        }
        int paintHash() {
            int result = dimension.hashCode();
            for (int value : new int[]{minX, maxX, minZ, maxZ, color}) result = 31 * result + value;
            return result;
        }
    }
    private record Overlay(MapWorld world, HighlighterRegistry registry, JsonObject snapshot, TownGateway.Context context, String worldId, boolean enabled,
                           Map<String, String> bindings, Map<String, String> viewedMaps, List<Area> areas) { }
    private static volatile Overlay overlay;
    private final HighlighterRegistry registry;

    ProjectHighlighter(HighlighterRegistry registry) {
        super(true);
        this.registry = registry;
    }

    static void refresh() {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = XaeroBridge.processor();
        MapWorld world = processor == null ? null : processor.getMapWorld();
        HighlighterRegistry nativeRegistry = processor == null ? null : processor.getHighlighterRegistry();
        JsonObject snapshot = gateway.snapshot;
        TownGateway.Context context = gateway.context();
        boolean enabled = gateway.loggedIn() && gateway.config().projectOverlayEnabled && !gateway.worldId.isEmpty()
                && processor != null && processor.isMapWorldUsable();
        Map<String, String> bindings = new HashMap<>();
        Map<String, String> viewedMaps = new HashMap<>();
        if (world != null) for (MapDimension dimension : world.getDimensionsList()) {
            String id = dimension.getDimId().identifier().toString();
            bindings.put(id, gateway.getBoundMapKey(id));
            viewedMaps.put(id, String.valueOf(dimension.getCurrentMultiworld()));
        }
        Overlay previous = overlay;
        if (previous != null && previous.world == world && previous.registry == nativeRegistry && previous.snapshot == snapshot && previous.context.equals(context)
                && previous.worldId.equals(gateway.worldId) && previous.enabled == enabled && previous.bindings.equals(bindings) && previous.viewedMaps.equals(viewedMaps)) return;

        List<Area> areas = new ArrayList<>();
        boolean knownWorld = false;
        for (JsonElement element : arrayValue(snapshot, "worlds")) if (gateway.worldId.equals(stringValue(element.getAsJsonObject(), "id"))) knownWorld = true;
        if (enabled && knownWorld) for (JsonElement element : arrayValue(snapshot, "projects")) {
            JsonObject project = element.getAsJsonObject();
            JsonObject chunks = objectValue(project, "chunks");
            if (!gateway.worldId.equals(stringValue(project, "worldId")) || chunks.isEmpty()) continue;
            String name = XaeroBridge.projectName(project);
            String leader = stringValue(project, "leaderId");
            String leaderName = leader.equals(stringValue(project, "createdBy")) ? stringValue(project, "createdByName", leader) : leader;
            for (JsonElement member : arrayValue(snapshot, "members")) if (leader.equals(stringValue(member.getAsJsonObject(), "playerId"))) {
                leaderName = stringValue(member.getAsJsonObject(), "name", leader);
                break;
            }
            String status = stringValue(project, "status");
            String type = switch (stringValue(project, "type")) { case "building" -> "建筑"; case "machine" -> "机器"; default -> "其他"; };
            String introduction = stringValue(project, "purpose");
            if (!stringValue(project, "notes").isBlank()) introduction += (introduction.isBlank() ? "" : " / ") + stringValue(project, "notes");
            String tooltip = name + "\n建设者：" + (leaderName.isBlank() ? "未指定" : leaderName) + "\n类型：" + type
                    + "\n简介：" + (introduction.isBlank() ? "暂无简介" : summary(introduction, 100))
                    + (status.equals("completed") ? "" : "\n状态：" + state(status)) + "\n右键查看工程详情";
            int color = switch (status) { case "planned" -> 0xDAB75C; case "active" -> 0xE49754; case "maintenance" -> 0xDF9481; case "completed" -> 0x73BC78; default -> 0x999999; };
            areas.add(new Area(stringValue(project, "dimension"), integer(chunks, "minX"), integer(chunks, "maxX"), integer(chunks, "minZ"), integer(chunks, "maxZ"), color, name, tooltip));
        }
        Overlay updated = new Overlay(world, nativeRegistry, snapshot, context, gateway.worldId, enabled, Map.copyOf(bindings), Map.copyOf(viewedMaps), List.copyOf(areas));
        overlay = updated;
        boolean repaint = previous == null || previous.world != world || previous.registry != nativeRegistry || !previous.context.equals(context)
                || !previous.worldId.equals(updated.worldId) || previous.enabled != enabled || !previous.bindings.equals(bindings) || !previous.viewedMaps.equals(viewedMaps)
                || previous.areas.size() != areas.size();
        if (!repaint) for (int index = 0; index < areas.size(); index++) if (!previous.areas.get(index).samePaint(areas.get(index))) { repaint = true; break; }
        if (world != null && repaint) world.clearAllCachedHighlightHashes();
    }

    private Overlay current(ResourceKey<Level> dimension) {
        Overlay value = overlay;
        MapProcessor processor = XaeroBridge.processor();
        TownGateway gateway = TownGateway.get();
        if (value == null || !value.enabled || dimension == null || processor == null || !processor.isMapWorldUsable()
                || value.registry != registry || processor.getHighlighterRegistry() != registry || processor.getMapWorld() != value.world
                || !gateway.worldId.equals(value.worldId) || !gateway.loggedIn()
                || !gateway.config().projectOverlayEnabled || !gateway.isCurrent(value.context)) return null;
        MapDimension nativeDimension = value.world.getDimension(dimension);
        String binding = value.bindings.getOrDefault(dimension.identifier().toString(), "");
        return nativeDimension != null && !binding.isEmpty() && binding.equals(gateway.getBoundMapKey(dimension.identifier().toString()))
                && binding.equals(value.world.getMainId() + "|" + nativeDimension.getCurrentMultiworld()) ? value : null;
    }

    private Area areaAt(Overlay value, String dimension, int x, int z) {
        if (value == null) return null;
        for (Area area : value.areas) if (area.dimension.equals(dimension) && area.contains(x, z)) return area;
        return null;
    }

    @Override public boolean regionHasHighlights(ResourceKey<Level> dimension, int x, int z) {
        Overlay value = current(dimension);
        if (value == null) return false;
        String id = dimension.identifier().toString();
        for (Area area : value.areas) if (area.dimension.equals(id) && area.touchesRegion(x, z)) return true;
        return false;
    }

    @Override public int calculateRegionHash(ResourceKey<Level> dimension, int x, int z) {
        Overlay value = current(dimension);
        if (value == null) return 0;
        int hash = 1;
        String id = dimension.identifier().toString();
        for (Area area : value.areas) if (area.dimension.equals(id) && area.touchesRegion(x, z)) hash = 31 * hash + area.paintHash();
        return hash;
    }

    @Override public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
        return areaAt(current(dimension), dimension.identifier().toString(), x, z) != null;
    }

    @Override protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
        Area area = areaAt(current(dimension), dimension.identifier().toString(), x, z);
        if (area == null) return null;
        int fill = nativeColor(area.color, 52);
        int border = nativeColor(area.color, 210);
        return new int[] { fill, z == area.minZ ? border : fill, x == area.maxX ? border : fill,
                z == area.maxZ ? border : fill, x == area.minX ? border : fill };
    }

    @Override public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
        Area area = areaAt(current(dimension), dimension.identifier().toString(), x, z);
        return area == null ? null : Component.literal(area.name);
    }

    @Override public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
        Overlay value = current(dimension);
        if (value == null) return null;
        String id = dimension.identifier().toString();
        List<String> tips = new ArrayList<>();
        for (Area area : value.areas) if (area.dimension.equals(id) && area.contains(x, z)) tips.add(area.tooltip);
        return tips.isEmpty() ? null : Component.literal(String.join("\n\n", tips));
    }

    @Override public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension, int x, int z, int width) {
        Component tooltip = getBlockHighlightBluntTooltip(dimension, x, z);
        if (tooltip != null) tooltips.add(tooltip);
    }

    static int nativeColor(int rgb, int alpha) {
        return (rgb & 255) << 24 | ((rgb >>> 8) & 255) << 16 | ((rgb >>> 16) & 255) << 8 | (alpha & 255);
    }

    static String summary(String text, int maximumCharacters) {
        String singleLine = text.replaceAll("\\s+", " ").strip();
        return singleLine.codePointCount(0, singleLine.length()) <= maximumCharacters ? singleLine
                : singleLine.substring(0, singleLine.offsetByCodePoints(0, maximumCharacters)) + "…";
    }

    static boolean intersects(int minX, int maxX, int minZ, int maxZ, int otherMinX, int otherMaxX, int otherMinZ, int otherMaxZ) {
        return minX <= otherMaxX && maxX >= otherMinX && minZ <= otherMaxZ && maxZ >= otherMinZ;
    }
}
