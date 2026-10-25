package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static dev.yetanothertown.client.TownScreen.*;

/** 区域进入字幕、当前项目详情与显式选择的方向导航。 */
public final class TownHud {
    private static final EntryTracker entry = new EntryTracker();
    private static JsonObject previousSnapshot;
    private static TownGateway.Context previousContext;
    private static JsonObject currentProject;
    private static String previousDimension = "";
    private static int previousChunkX, previousChunkZ;
    private static long subtitleUntil;
    private static String subtitle = "";
    private static boolean entrySubtitle;

    private TownHud() { }

    static void initialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("yetanothertown", "project"), (graphics, tracker) -> draw(graphics));
    }

    /** 区块或资料变化时重新命中项目；字幕不依赖网络轮询或每帧遍历。 */
    static void tick() {
        TownGateway gateway = TownGateway.get();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !gateway.loggedIn() || !XaeroBridge.isCurrentWorld()) {
            currentProject = null; previousSnapshot = null; previousContext = null;
            entry.reset(); subtitleUntil = 0;
            return;
        }
        TownGateway.Context context = gateway.context();
        JsonObject snapshot = gateway.snapshot;
        String dimension = VersionClient.dimension();
        if (!context.equals(previousContext) || !dimension.equals(previousDimension)) { entry.reset(); subtitleUntil = 0; previousSnapshot = null; }
        int chunkX = client.player.getBlockX() >> 4, chunkZ = client.player.getBlockZ() >> 4;
        if (previousSnapshot != snapshot || chunkX != previousChunkX || chunkZ != previousChunkZ) {
            currentProject = projectAt(snapshot, gateway.worldId, dimension, chunkX, chunkZ);
            previousSnapshot = snapshot; previousChunkX = chunkX; previousChunkZ = chunkZ;
        }
        previousContext = context; previousDimension = dimension;
        String id = currentProject == null ? "" : stringValue(currentProject, "id");
        long now = System.nanoTime();
        if (entry.enter(id, now, gateway.config().entryCooldownSeconds * 1_000_000_000L)) {
            entrySubtitle = true;
            subtitle = TownText.text("yat.hud.project_entry") + XaeroBridge.projectName(currentProject);
            subtitleUntil = now + gateway.config().entrySubtitleSeconds * 1_000_000_000L;
        } else if (entrySubtitle && !id.equals(entry.announced)) subtitleUntil = 0;
    }

    static JsonObject projectAt(JsonObject snapshot, String world, String dimension, int chunkX, int chunkZ) {
        JsonObject selected = null;
        long selectedArea = Long.MAX_VALUE;
        for (JsonElement element : arrayValue(snapshot, "projects")) {
            JsonObject project = element.getAsJsonObject();
            if (!stringValue(project, "worldId").equals(world) || !stringValue(project, "dimension").equals(dimension)) continue;
            JsonObject chunks = objectValue(project, "chunks");
            int minX = integer(chunks, "minX"), maxX = integer(chunks, "maxX"), minZ = integer(chunks, "minZ"), maxZ = integer(chunks, "maxZ");
            if (chunkX < minX || chunkX > maxX || chunkZ < minZ || chunkZ > maxZ) continue;
            long area = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
            if (selected == null || area < selectedArea || area == selectedArea && stringValue(project, "id").compareTo(stringValue(selected, "id")) < 0) { selected = project; selectedArea = area; }
        }
        return selected;
    }

    static final class EntryTracker {
        private String current = "";
        private String announced = "";
        private final java.util.Map<String, Long> announcements = new java.util.HashMap<>();
        boolean enter(String id, long now, long cooldown) {
            if (current.equals(id)) return false;
            current = id;
            if (id.isEmpty()) return false;
            Long previous = announcements.get(id);
            if (previous != null && now - previous < cooldown) return false;
            announcements.values().removeIf(time -> now - time >= cooldown);
            announced = id; announcements.put(id, now);
            return true;
        }
        void reset() { current = ""; announced = ""; announcements.clear(); }
    }

    static void openProject() {
        tick();
        JsonObject project = projectForDetails(TownGateway.get().snapshot, VersionClient.navigation(), currentProject);
        if (project != null) TownScreen.openProject(VersionClient.currentScreen(), project);
        else {
            TownGateway.get().status = TownText.text("yat.hint.no_project_here");
            entrySubtitle = false;
            subtitle = TownGateway.get().status;
            subtitleUntil = System.nanoTime() + TownGateway.get().config().entrySubtitleSeconds * 1_000_000_000L;
        }
    }

    static JsonObject projectForDetails(JsonObject snapshot, JsonObject target, JsonObject nearby) {
        if (target == null || !target.has("chunks")) return nearby;
        for (JsonElement element : arrayValue(snapshot, "projects")) {
            JsonObject project = element.getAsJsonObject();
            if (TownScreen.isNavigationTarget(project, target)) return project;
        }
        return null;
    }

    static String entryStatus(String status) {
        return switch (status) {
            case "planned" -> TownText.text("yat.project.status.planned");
            case "active" -> TownText.text("yat.hud.project_unfinished");
            case "maintenance" -> TownText.text("yat.project.status.needs_repair");
            case "completed" -> TownText.text("yat.project.status.completed");
            default -> "";
        };
    }

    /** 状态优先保留，长项目名称用省略号收紧。 */
    static Component entryLine(String title, String status, int width, java.util.function.ToIntFunction<String> measure) {
        String label = entryStatus(status);
        if (label.isEmpty()) return Component.literal(TownStyle.fitText(title, width, measure));
        String visibleStatus = TownStyle.fitText(label, width, measure);
        String name = TownStyle.fitText(title, Math.max(0, width - measure.applyAsInt(" · " + visibleStatus)), measure);
        var text = Component.literal(name);
        if (!name.isEmpty()) text.append(Component.literal(" · "));
        return text.append(Component.literal(visibleStatus).withStyle(switch (status) {
            case "maintenance" -> ChatFormatting.RED;
            case "planned" -> ChatFormatting.GOLD;
            case "active" -> ChatFormatting.YELLOW;
            case "completed" -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        }));
    }

    /** 进入提示与导航共用原来的居中小字位置；进入提示优先，不绘制面板。 */
    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || VersionClient.hudHidden() || VersionClient.currentScreen() != null) return;
        int y = Math.min(graphics.guiHeight() - 70, graphics.guiHeight() / 2 + 30);
        long remaining = subtitleUntil - System.nanoTime();
        if (remaining > 0) {
            int alpha = TownGateway.get().config().animationEnabled ? (int) Math.min(255, remaining / 2_000_000L) : 255;
            if (alpha > 3) {
                if (entrySubtitle && currentProject != null) {
                    String status = stringValue(currentProject, "status");
                    var text = entryLine(subtitle, status, Math.max(0, graphics.guiWidth() - 32), client.font::width);
                    drawCentered(graphics, text, y, alpha << 24 | 0xFFFFFF);
                } else drawCentered(graphics, subtitle, y, alpha << 24 | 0xFFFFFF);
                if (entrySubtitle && currentProject != null) {
                    String key = VersionClient.projectKeyLabel();
                    String hint = key.isEmpty() ? TownText.text("yat.hint.bind_project_details") : TownText.text("yat.hint.project_key", key);
                    drawCentered(graphics, hint, y + 15, alpha << 24 | 0xD6A45D);
                }
            }
            return;
        }
        JsonObject target = VersionClient.navigation();
        if (target == null) return;
        double dx = integer(target, "x") - client.player.getX(), dz = integer(target, "z") - client.player.getZ();
        double degrees = Math.toDegrees(Math.atan2(-dx, dz));
        double angle = ((degrees - client.player.getYRot()) % 360 + 540) % 360 - 180;
        String direction = Math.abs(angle) <= 20 ? TownText.text("yat.hud.ahead") : Math.abs(angle) >= 160 ? TownText.text("yat.hud.behind") : angle < 0 ? TownText.text("yat.hud.left") : TownText.text("yat.hud.right");
        drawCentered(graphics, VersionClient.navigationName(target), y, 0xFFFFFFFF);
        drawCentered(graphics, direction + " · " + Math.round(Math.hypot(dx, dz)) + "m", y + 15, 0xFFD6A45D);
        if (!TownGateway.get().hasCompletedHint("navigation")) {
            String key = VersionClient.projectKeyLabel();
            drawCentered(graphics, key.isEmpty() ? TownText.text("yat.hint.navigation_unbound") : TownText.text("yat.hint.navigation_once", key), y + 30, 0xFFD6A45D);
        }
    }

    private static void drawCentered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        var font = Minecraft.getInstance().font;
        String line = font.plainSubstrByWidth(text, Math.max(0, graphics.guiWidth() - 32));
        graphics.text(font, line, (graphics.guiWidth() - font.width(line)) / 2, y, color, true);
    }

    private static void drawCentered(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        var font = Minecraft.getInstance().font;
        var line = Language.getInstance().getVisualOrder(font.substrByWidth(text, Math.max(0, graphics.guiWidth() - 32)));
        graphics.text(font, line, (graphics.guiWidth() - font.width(line)) / 2, y, color, true);
    }
}
