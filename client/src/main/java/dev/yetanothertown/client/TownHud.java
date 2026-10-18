package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
            entry.reset(); if (entrySubtitle) subtitleUntil = 0;
            return;
        }
        TownGateway.Context context = gateway.context();
        JsonObject snapshot = gateway.snapshot;
        String dimension = VersionClient.dimension();
        if (!context.equals(previousContext) || !dimension.equals(previousDimension)) { entry.reset(); if (entrySubtitle) subtitleUntil = 0; previousSnapshot = null; }
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
            subtitle = "进入项目：" + XaeroBridge.projectName(currentProject);
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
        if (currentProject != null) TownScreen.openProject(VersionClient.currentScreen(), currentProject);
        else {
            TownGateway.get().status = "当前所在区块没有可查看的项目";
            entrySubtitle = false;
            subtitle = TownGateway.get().status;
            subtitleUntil = System.nanoTime() + TownGateway.get().config().entrySubtitleSeconds * 1_000_000_000L;
        }
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || VersionClient.hudHidden() || VersionClient.currentScreen() != null) return;
        long remaining = subtitleUntil - System.nanoTime();
        if (remaining > 0) {
            int alpha = TownGateway.get().config().animationEnabled ? (int) Math.min(255, remaining / 2_000_000L) : 255;
            if (alpha > 3) {
                int y = Math.min(graphics.guiHeight() - 70, graphics.guiHeight() / 2 + 30);
                String title = client.font.plainSubstrByWidth(subtitle, Math.max(0, graphics.guiWidth() - 32));
                graphics.text(client.font, title, (graphics.guiWidth() - client.font.width(title)) / 2, y, alpha << 24 | 0xFFFFFF, true);
                if (entrySubtitle && currentProject != null) {
                    String key = VersionClient.projectKeyLabel();
                    String hint = key.isEmpty() ? "可在按键设置中绑定项目详情键" : "按 " + key + " 查看项目详情";
                    hint = client.font.plainSubstrByWidth(hint, Math.max(0, graphics.guiWidth() - 32));
                    graphics.text(client.font, hint, (graphics.guiWidth() - client.font.width(hint)) / 2, y + 15, alpha << 24 | 0xD6A45D, true);
                }
            }
        }
        JsonObject target = VersionClient.navigation();
        if (target == null) return;
        String name = VersionClient.navigationName(target);
        String instruction;
        if (!stringValue(target, "dimension").equals(VersionClient.dimension())) instruction = "目标维度 " + stringValue(target, "dimension");
        else {
            double dx = integer(target, "x") - client.player.getX(), dz = integer(target, "z") - client.player.getZ();
            double degrees = Math.toDegrees(Math.atan2(-dx, dz));
            double angle = ((degrees - client.player.getYRot()) % 360 + 540) % 360 - 180;
            String direction = Math.abs(angle) <= 20 ? "↑ 前方" : Math.abs(angle) >= 160 ? "↓ 后方" : angle < 0 ? "← 左侧" : "→ 右侧";
            instruction = direction + " · " + Math.round(Math.hypot(dx, dz)) + "m";
        }
        int width = Math.min(218, graphics.guiWidth() / 2 - 12);
        TownStyle.panel(graphics, 10, 38, width, 44, true);
        graphics.text(client.font, client.font.plainSubstrByWidth(name, width - 18), 19, 47, TownStyle.ACCENT, true);
        graphics.text(client.font, client.font.plainSubstrByWidth(instruction, width - 18), 19, 63, TownStyle.TEXT, false);
    }
}
