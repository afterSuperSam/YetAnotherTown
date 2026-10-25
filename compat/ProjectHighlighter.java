package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import xaero.map.gui.GuiMap;
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
    private record Area(String id, String dimension, int minX, int maxX, int minZ, int maxZ, int color, String name, String leaderId, String leaderName, String type, String introduction, String status) {
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean touchesRegion(int x, int z) { return intersects(minX, maxX, minZ, maxZ, x << 5, (x << 5) + 31, z << 5, (z << 5) + 31); }
        boolean samePaint(Area other) {
            return id.equals(other.id) && dimension.equals(other.dimension) && minX == other.minX && maxX == other.maxX && minZ == other.minZ && maxZ == other.maxZ && color == other.color;
        }
        int paintHash() {
            int result = 31 * id.hashCode() + dimension.hashCode();
            for (int value : new int[]{minX, maxX, minZ, maxZ, color}) result = 31 * result + value;
            return result;
        }
    }
    private record Overlay(MapWorld world, HighlighterRegistry registry, JsonObject snapshot, TownGateway.Context context, String worldId, boolean enabled,
                           Map<String, String> bindings, Map<String, String> viewedMaps, List<Area> areas) { }
    private static final int TYPE_ICON_OFFSET = 14;
    private static final int[] FOCUS_STRENGTH = {96, 176, 255, 176, 96, 48};
    record TooltipLayout(int width, int height, int avatarSize, int textLeft, int titleHeight, int ownerTop, int introductionTop, int statusLeft,
                         List<String> title, List<String> owner, List<String> introduction, boolean folded) { }
    private static volatile Overlay overlay;
    private record Emphasis(String hovered, String focused, int strength) { }
    private record Focus(String projectId, TownGateway.Context context, String worldId, long started) { }
    private static volatile Emphasis emphasis = new Emphasis("", "", 0);
    private static Focus focus;
    private final HighlighterRegistry registry;

    ProjectHighlighter(HighlighterRegistry registry) {
        super(true);
        this.registry = registry;
    }

    static void invalidate() {
        Overlay previous = overlay;
        overlay = null;
        if (previous != null && previous.world != null) previous.world.clearAllCachedHighlightHashes();
    }

    static void focusProject(String projectId) {
        TownGateway gateway = TownGateway.get();
        focus = new Focus(projectId, gateway.context(), gateway.worldId, System.nanoTime());
        updateEmphasis("");
    }

    /** 高亮仅在悬停项目切换或定位脉冲阶段变化时使缓存失效。 */
    private static void updateEmphasis(String hovered) {
        TownGateway gateway = TownGateway.get();
        int strength = 0;
        if (focus != null) {
            long elapsed = (System.nanoTime() - focus.started) / 1_000_000L;
            if (!gateway.isCurrent(focus.context) || !gateway.worldId.equals(focus.worldId) || elapsed >= 1800) focus = null;
            else strength = gateway.config().animationEnabled ? FOCUS_STRENGTH[(int) (elapsed / 300)] : 255;
        }
        Emphasis updated = new Emphasis(hovered, focus == null ? "" : focus.projectId, strength);
        if (!updated.equals(emphasis)) {
            emphasis = updated;
            Overlay value = overlay;
            if (value != null && value.world != null) value.world.clearAllCachedHighlightHashes();
        }
    }

    static void refresh() {
        TownGateway gateway = TownGateway.get();
        MapProcessor processor = XaeroBridge.processor();
        MapWorld world = processor == null ? null : processor.getMapWorld();
        HighlighterRegistry nativeRegistry = processor == null ? null : processor.getHighlighterRegistry();
        JsonObject snapshot = gateway.snapshot;
        TownGateway.Context context = gateway.context();
        String hovered = VersionClient.currentScreen() instanceof GuiMap && !XaeroBridge.mapUiHidden() ? emphasis.hovered : "";
        if (overlay != null && (!overlay.context.equals(context) || !overlay.worldId.equals(gateway.worldId))) hovered = "";
        updateEmphasis(hovered);
        boolean enabled = gateway.loggedIn() && gateway.config().projectOverlayEnabled && !gateway.worldId.isEmpty()
                && processor != null && processor.isMapWorldUsable();
        Map<String, String> bindings = new HashMap<>();
        Map<String, String> viewedMaps = new HashMap<>();
        if (world != null) for (MapDimension dimension : world.getDimensionsList()) {
            String id = dimension.getDimId().identifier().toString();
            bindings.put(id, XaeroBridge.resolvedMapKey(dimension));
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
            String type = stringValue(project, "type");
            String introduction = stringValue(project, "purpose");
            if (!stringValue(project, "notes").isBlank()) introduction += (introduction.isBlank() ? "" : " / ") + stringValue(project, "notes");
            int color = switch (status) { case "planned" -> 0xDAB75C; case "active" -> 0xE49754; case "maintenance" -> 0xDF9481; case "completed" -> 0x73BC78; default -> 0x999999; };
            areas.add(new Area(stringValue(project, "id"), stringValue(project, "dimension"), integer(chunks, "minX"), integer(chunks, "maxX"), integer(chunks, "minZ"), integer(chunks, "maxZ"), color, name, leader, leaderName.isBlank() ? TownText.text("yat.label.unspecified") : leaderName, type, introduction.isBlank() ? TownText.text("yat.empty.summary") : introduction, status));
        }
        Overlay updated = new Overlay(world, nativeRegistry, snapshot, context, gateway.worldId, enabled, Map.copyOf(bindings), Map.copyOf(viewedMaps), List.copyOf(areas));
        overlay = updated;
        boolean repaint = previous == null || previous.world != world || previous.registry != nativeRegistry || !previous.context.equals(context)
                || !previous.worldId.equals(updated.worldId) || previous.enabled != enabled || !previous.bindings.equals(bindings) || !previous.viewedMaps.equals(viewedMaps)
                || previous.areas.size() != areas.size();
        if (!repaint) for (int index = 0; index < areas.size(); index++) if (!previous.areas.get(index).samePaint(areas.get(index))) { repaint = true; break; }
        if (world != null && repaint) world.clearAllCachedHighlightHashes();
    }

    private Overlay current(ResourceKey<Level> dimension) { return current(dimension, registry); }

    private static Overlay current(ResourceKey<Level> dimension, HighlighterRegistry registry) {
        Overlay value = overlay;
        MapProcessor processor = XaeroBridge.processor();
        TownGateway gateway = TownGateway.get();
        if (value == null || !value.enabled || dimension == null || processor == null || !processor.isMapWorldUsable()
                || value.registry != registry || processor.getHighlighterRegistry() != registry || processor.getMapWorld() != value.world
                || !gateway.worldId.equals(value.worldId) || !gateway.loggedIn()
                || !gateway.config().projectOverlayEnabled || !gateway.isCurrent(value.context)) return null;
        MapDimension nativeDimension = value.world.getDimension(dimension);
        String binding = value.bindings.getOrDefault(dimension.identifier().toString(), "");
        return nativeDimension != null && !binding.isEmpty() && binding.equals(XaeroBridge.resolvedMapKey(nativeDimension))
                && binding.equals(value.world.getMainId() + "|" + nativeDimension.getCurrentMultiworld()) ? value : null;
    }

    private static Area areaAt(Overlay value, String dimension, int x, int z) {
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
        Emphasis selected = emphasis;
        for (Area area : value.areas) if (area.dimension.equals(id) && area.touchesRegion(x, z)) {
            hash = 31 * hash + area.paintHash();
            hash = 31 * hash + (selected.hovered.isEmpty() ? 0 : selected.hovered.equals(area.id) ? 1 : 2);
            hash = 31 * hash + (selected.focused.equals(area.id) ? selected.strength : 0);
        }
        return hash;
    }

    @Override public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
        return areaAt(current(dimension), dimension.identifier().toString(), x, z) != null;
    }

    @Override protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
        Area area = areaAt(current(dimension), dimension.identifier().toString(), x, z);
        if (area == null) return null;
        Emphasis selected = emphasis;
        boolean hovered = selected.hovered.equals(area.id);
        boolean dimmed = !selected.hovered.isEmpty() && !hovered;
        int strength = selected.focused.equals(area.id) ? selected.strength : hovered ? 96 : 0;
        int borderColor = strength == 0 ? area.color : brighten(area.color, strength);
        int fill = nativeColor(area.color, dimmed ? 24 : 52);
        int border = nativeColor(borderColor, strength > 0 ? 255 : dimmed ? 115 : 210);
        return new int[] { fill, z == area.minZ ? border : fill, x == area.maxX ? border : fill,
                z == area.maxZ ? border : fill, x == area.minX ? border : fill };
    }

    @Override public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
        Area area = areaAt(current(dimension), dimension.identifier().toString(), x, z);
        return area == null ? null : Component.literal(summary(area.name, 40));
    }

    /** 本项目的完整提示在地图尾层绘制，不参与 Xaero 的单行 Blunt 拼接。 */
    @Override public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) { return null; }

    @Override public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension, int x, int z, int width) {
        Overlay value = current(dimension);
        if (value == null) return;
        String id = dimension.identifier().toString();
        for (Area area : value.areas) if (area.dimension.equals(id) && area.contains(x >> 4, z >> 4)) {
            tooltips.add(Component.literal(summary(area.name, 40)));
            tooltips.add(Component.literal(TownText.text("yat.label.owner") + summary(area.leaderName, 40)));
            String type = switch (area.type) { case "building" -> TownText.text("yat.project.type.building"); case "machine" -> TownText.text("yat.project.type.machine"); default -> TownText.text("yat.project.type.other"); };
            tooltips.add(Component.literal(TownText.text("yat.label.type") + type));
            for (String line : textLines(TownText.text("yat.label.summary") + summary(area.introduction, 100), Math.max(40, width), Minecraft.getInstance().font)) tooltips.add(Component.literal(line));
            if (!area.status.equals("completed")) tooltips.add(Component.literal(TownText.text("yat.label.status") + state(area.status)));
        }
    }

    /** 使用 Xaero 当帧鼠标区块与维度，卡片只读取当前绑定底图上的覆盖层。 */
    static void renderTooltip(GuiMap screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (VersionClient.currentScreen() != screen || XaeroBridge.mapUiHidden() || screen.width < 160 || screen.height < 140) { updateEmphasis(""); return; }
        String hovered = "";
        try {
            if (HoverFields.MENU.get(screen) != null || HoverFields.WAYPOINTS.getBoolean(screen) || HoverFields.PLAYERS.getBoolean(screen)) return;
            for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(mouseX, mouseY)) return;
            @SuppressWarnings("unchecked") ResourceKey<Level> dimension = (ResourceKey<Level>) HoverFields.DIMENSION.get(screen);
            Overlay previous = overlay;
            Overlay value = previous == null ? null : current(dimension, previous.registry);
            if (value == null) return;
            int chunkX = HoverFields.X.getInt(screen) >> 4, chunkZ = HoverFields.Z.getInt(screen) >> 4;
            Area area = areaAt(value, dimension.identifier().toString(), chunkX, chunkZ);
            if (area == null) return;
            hovered = area.id;
            Font font = Minecraft.getInstance().font;
            int step = font.lineHeight + 2;
            TooltipLayout layout = tooltipLayout(area.name, area.leaderName, area.introduction, area.status,
                    screen.width, screen.height, font.lineHeight, font::width);
            if (layout.height() > screen.height - 32) return;
            int[] position = tooltipPosition(screen.width, screen.height, layout.width(), layout.height(), mouseX, mouseY);
            int x = position[0], y = position[1];
            int textX = x + layout.textLeft();
            graphics.nextStratum();
            TooltipRenderUtil.extractTooltipBackground(graphics, x, y, layout.width(), layout.height(), null);
            VersionClient.drawAvatar(graphics, area.leaderId, area.leaderName, x, y, layout.avatarSize());
            VersionClient.drawLogo(graphics, projectIcon(area.type), textX, TownStyle.centeredY(y + 1, font.lineHeight, 12), 12);
            int titleColor = area.status.equals("maintenance") ? TownStyle.DANGER : area.status.equals("planned") ? TownStyle.ACCENT : TownStyle.TEXT;
            for (int index = 0; index < layout.title().size(); index++) graphics.text(font, layout.title().get(index), textX + TYPE_ICON_OFFSET, y + 1 + index * step, titleColor, true);
            for (int index = 0; index < layout.owner().size(); index++) graphics.text(font, layout.owner().get(index), textX, y + layout.ownerTop() + index * step, TownStyle.MUTED, false);
            for (int index = 0; index < layout.introduction().size(); index++) graphics.text(font, layout.introduction().get(index), x, y + layout.introductionTop() + index * step, TownStyle.TEXT, false);
            if (layout.folded()) graphics.text(font, font.plainSubstrByWidth(TownText.text("yat.hint.project_full_description"), layout.width()), x,
                    y + layout.introductionTop() + layout.introduction().size() * step + 3, TownStyle.MUTED, false);
            if (!area.status.equals("completed")) graphics.text(font, state(area.status), x + layout.statusLeft(), y + 1,
                    area.status.equals("maintenance") ? TownStyle.DANGER : area.status.equals("active") ? TownStyle.SUCCESS : TownStyle.ACCENT, false);
        } catch (IllegalAccessException exception) { throw new IllegalStateException(TownText.text("yat.error.read_map_hover"), exception); }
        finally { updateEmphasis(hovered); }
    }

    static String projectIcon(String type) {
        return switch (type) { case "machine" -> "minecraft:redstone"; case "building" -> "minecraft:quartz"; default -> "minecraft:lapis_lazuli"; };
    }

    /** 头像与两行身份信息等高，状态跟随名称，简介独占通栏。 */
    static TooltipLayout tooltipLayout(String name, String ownerName, String description, String status,
                                       int screenWidth, int screenHeight, int lineHeight, ToIntFunction<String> measure) {
        String ownerText = summary(ownerName, 80);
        int step = lineHeight + 2;
        int avatarSize = lineHeight * 2 + 4;
        int textLeft = avatarSize + 6;
        int statusWidth = status.equals("completed") ? 0 : measure.applyAsInt(state(status)) + 6;
        int naturalWidth = Math.max(textLeft + Math.max(longestLine(name, measure) + TYPE_ICON_OFFSET + statusWidth, longestLine(ownerText, measure)), longestLine(description, measure));
        int width = Math.max(1, Math.min(Math.min(248, screenWidth - 32), Math.max(96, naturalWidth)));
        int textWidth = width - textLeft;
        List<String> title = limitedLines(summary(name, 200), Math.max(1, textWidth - TYPE_ICON_OFFSET - statusWidth), 1, measure);
        List<String> owner = limitedLines(ownerText, textWidth, 1, measure);
        int ownerTop = step + 1;
        int introductionTop = avatarSize + 6;
        int maximumLines = Math.max(1, Math.min(3, (screenHeight - 35 - introductionTop - step) / step));
        boolean folded = !description.isBlank() && textLines(description, width, measure).size() > maximumLines;
        List<String> introduction = description.isBlank() ? List.of() : limitedLines(description, width, maximumLines, measure);
        int statusLeft = textLeft + TYPE_ICON_OFFSET + measure.applyAsInt(title.getFirst()) + 6;
        int height = introduction.isEmpty() ? avatarSize : introductionTop + introduction.size() * step + (folded ? step + 3 : 0);
        return new TooltipLayout(width, height, avatarSize, textLeft, step, ownerTop, introductionTop, statusLeft, title, owner, introduction, folded);
    }

    private static int longestLine(String text, ToIntFunction<String> measure) {
        int width = 0;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) width = Math.max(width, measure.applyAsInt(line));
        return width;
    }

    static List<String> textLines(String text, int width, Font font) { return textLines(text, width, font::width); }

    static List<String> textLines(String text, int width, ToIntFunction<String> measure) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int point = paragraph.codePointAt(offset);
                String next = new String(Character.toChars(point));
                if (!line.isEmpty() && measure.applyAsInt(line + next) > width) {
                    int boundary = line.lastIndexOf(" ");
                    if (Character.isWhitespace(point)) {
                        result.add(line.toString().stripTrailing()); line.setLength(0);
                        offset += Character.charCount(point); continue;
                    } else if (boundary > 0) {
                        result.add(line.substring(0, boundary).stripTrailing()); line.delete(0, boundary + 1);
                    } else { result.add(line.toString()); line.setLength(0); }
                }
                line.append(next); offset += Character.charCount(point);
            }
            result.add(line.toString());
        }
        return result;
    }

    private static List<String> limitedLines(String text, int width, int maximum, ToIntFunction<String> measure) {
        List<String> lines = textLines(text, width, measure);
        if (lines.size() <= maximum) return lines;
        List<String> result = new ArrayList<>(lines.subList(0, maximum));
        String last = result.get(maximum - 1);
        while (!last.isEmpty() && measure.applyAsInt(last + "…") > width) last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
        result.set(maximum - 1, last + "…");
        return result;
    }

    static int[] tooltipPosition(int screenWidth, int screenHeight, int width, int height, int mouseX, int mouseY) {
        int x = mouseX + 12;
        if (x + width + 16 > screenWidth) x = mouseX - width - 12;
        return new int[]{Math.max(16, Math.min(screenWidth - width - 16, x)), Math.max(16, Math.min(screenHeight - height - 16, mouseY - 12))};
    }

    private static final class HoverFields {
        static final Field X = field("mouseBlockPosX"), Z = field("mouseBlockPosZ"), DIMENSION = field("mouseBlockDim");
        static final Field MENU = field("rightClickMenu"), WAYPOINTS = field("overWaypointsMenu"), PLAYERS = field("overPlayersMenu");
        private static Field field(String name) {
            try { Field field = GuiMap.class.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.map_hover_incompatible_prefix") + name, exception); }
        }
    }

    static int nativeColor(int rgb, int alpha) {
        return (rgb & 255) << 24 | ((rgb >>> 8) & 255) << 16 | ((rgb >>> 16) & 255) << 8 | (alpha & 255);
    }

    private static int brighten(int rgb, int amount) {
        int red = rgb >>> 16 & 255, green = rgb >>> 8 & 255, blue = rgb & 255;
        return (red + (255 - red) * amount / 255) << 16 | (green + (255 - green) * amount / 255) << 8 | blue + (255 - blue) * amount / 255;
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
