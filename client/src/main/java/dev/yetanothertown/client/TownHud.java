package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 附近留言与维护工单，以及显式选择的方向距离导航。 */
public final class TownHud {
    private record Nearby(JsonObject record, String category, double distance) { }
    private TownHud() { }

    static void initialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("yetanothertown", "nearby"), (graphics, tracker) -> draw(graphics));
    }

    private static List<Nearby> nearby() {
        TownGateway gateway = TownGateway.get();
        JsonObject snapshot = gateway.snapshot;
        List<Nearby> result = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !gateway.loggedIn() || gateway.worldId.isEmpty() || !XaeroBridge.isCurrentWorld()) return result;
        for (JsonElement element : TownScreen.arrayValue(snapshot, "worlds")) {
            JsonObject world = element.getAsJsonObject();
            if (!TownScreen.stringValue(world, "id").equals(gateway.worldId)) continue;
            if (TownScreen.bool(world, "archived")) return result;
        }
        for (String collection : List.of("messages", "tickets")) for (JsonElement element : TownScreen.arrayValue(snapshot, collection)) {
            JsonObject record = element.getAsJsonObject();
            if (!TownScreen.stringValue(record, "worldId").equals(gateway.worldId)) continue;
            if (!TownScreen.stringValue(record, "dimension").equals(VersionClient.dimension())) continue;
            if (collection.equals("tickets") && TownScreen.stringValue(record, "status").equals("resolved")) continue;
            double dx = minecraft.player.getX() - TownScreen.integer(record, "x");
            double dz = minecraft.player.getZ() - TownScreen.integer(record, "z");
            double dy = collection.equals("messages") ? 0 : minecraft.player.getY() - TownScreen.integer(record, "y");
            if (collection.equals("messages") && record.has("projectId")) for (JsonElement projectElement : TownScreen.arrayValue(snapshot, "projects")) {
                JsonObject project = projectElement.getAsJsonObject();
                if (!TownScreen.stringValue(project, "id").equals(TownScreen.stringValue(record, "projectId")) || !TownScreen.stringValue(project, "dimension").equals(VersionClient.dimension())) continue;
                JsonObject chunks = TownScreen.objectValue(project, "chunks");
                double minX = TownScreen.integer(chunks, "minX") * 16d, maxX = (TownScreen.integer(chunks, "maxX") + 1) * 16d;
                double minZ = TownScreen.integer(chunks, "minZ") * 16d, maxZ = (TownScreen.integer(chunks, "maxZ") + 1) * 16d;
                dx = Math.max(Math.max(minX - minecraft.player.getX(), 0), minecraft.player.getX() - maxX);
                dz = Math.max(Math.max(minZ - minecraft.player.getZ(), 0), minecraft.player.getZ() - maxZ);
                break;
            }
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int radius = record.has("radius") ? Math.min(TownScreen.integer(record, "radius"), gateway.config().nearbyRadius) : gateway.config().nearbyRadius;
            if (distance <= radius) result.add(new Nearby(record, collection.equals("messages") ? "留言" : "维修", distance));
        }
        result.sort(Comparator.comparingDouble(Nearby::distance));
        return result;
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || VersionClient.hudHidden() || VersionClient.currentScreen() != null) return;
        List<Nearby> nearby = nearby();
        int x = 10;
        JsonObject target = VersionClient.navigation();
        int y = Math.max(target == null ? 48 : 94, graphics.guiHeight() / 3);
        int cardWidth = Math.min(218, graphics.guiWidth() / 2 - 12);
        if (!nearby.isEmpty()) {
            int shown = Math.min(3, nearby.size());
            TownStyle.panel(graphics, x, y, cardWidth, 27 + shown * 30, false);
            graphics.text(minecraft.font, "附近 · " + nearby.size(), x + 9, y + 8, TownStyle.ACCENT, false);
            String shortcut = minecraft.font.plainSubstrByWidth(VersionClient.nearbyKeyLabel() + " 展开", cardWidth / 2 - 10);
            graphics.text(minecraft.font, shortcut, x + cardWidth - 9 - minecraft.font.width(shortcut), y + 8, TownStyle.MUTED, false);
            graphics.fill(x + 8, y + 21, x + cardWidth - 8, y + 22, TownStyle.LINE);
            y += 28;
            for (int index = 0; index < shown; index++) {
                Nearby entry = nearby.get(index);
                String title = TownScreen.stringValue(entry.record(), "name", TownScreen.stringValue(entry.record(), "title", TownScreen.stringValue(entry.record(), "text")));
                graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(entry.category() + " · " + title, cardWidth - 18), x + 9, y, TownStyle.TEXT, true);
                graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(Math.round(entry.distance()) + "m · " + TownScreen.stringValue(entry.record(), "dimension").replace("minecraft:", ""), cardWidth - 18), x + 9, y + 12, TownStyle.MUTED, false);
                y += 30;
            }
        }
        if (target != null) {
            int navY = 38;
            String name = TownScreen.stringValue(target, "name", TownScreen.stringValue(target, "title", "导航目标"));
            String instruction;
            if (!TownScreen.stringValue(target, "dimension").equals(VersionClient.dimension())) instruction = "目标维度 " + TownScreen.stringValue(target, "dimension");
            else {
                double dx = TownScreen.integer(target, "x") - minecraft.player.getX(), dz = TownScreen.integer(target, "z") - minecraft.player.getZ();
                double degrees = Math.toDegrees(Math.atan2(-dx, dz));
                double angle = ((degrees - minecraft.player.getYRot()) % 360 + 540) % 360 - 180;
                String direction = Math.abs(angle) <= 20 ? "↑ 前方" : Math.abs(angle) >= 160 ? "↓ 后方" : angle < 0 ? "← 左侧" : "→ 右侧";
                instruction = direction + " · " + Math.round(Math.hypot(dx, dz)) + "m";
            }
            TownStyle.panel(graphics, x, navY, cardWidth, 44, true);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(name, cardWidth - 18), x + 9, navY + 9, TownStyle.ACCENT, true);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(instruction, cardWidth - 18), x + 9, navY + 25, TownStyle.TEXT, false);
        }
    }

    static void openNearby() {
        List<Nearby> entries = nearby();
        List<TownForm.Action> actions = new ArrayList<>();
        for (Nearby entry : entries) {
            JsonObject record = entry.record();
            String title = TownScreen.stringValue(record, "name", TownScreen.stringValue(record, "title", TownScreen.stringValue(record, "text")));
            actions.add(new TownForm.Action(entry.category() + " · " + title + " · " + Math.round(entry.distance()) + "m", () -> showDetail(record, title)));
        }
        if (VersionClient.navigation() != null) actions.add(new TownForm.Action("清除当前方向导航", () -> { VersionClient.clearNavigation(); VersionClient.show(null); }));
        VersionClient.show(new TownForm(VersionClient.currentScreen(), "附近的小镇信息", List.of(), List.of(entries.isEmpty() ? "附近没有留言或未解决工单。" : "提示只显示你有权查阅的内容。"), actions, null));
    }

    private static void showDetail(JsonObject record, String title) {
        List<String> lines = new ArrayList<>();
        String[][] fields = {{"text", "留言"}, {"description", "故障详情"}, {"resolution", "处理结果"}, {"createdByName", "登记人"}};
        for (String[] field : fields) if (record.has(field[0])) lines.add(field[1] + "：" + TownScreen.stringValue(record, field[0]));
        lines.add("坐标：" + TownScreen.stringValue(record, "dimension") + " " + TownScreen.stringValue(record, "x") + ", " + TownScreen.stringValue(record, "y") + ", " + TownScreen.stringValue(record, "z"));
        VersionClient.show(new TownForm(null, title, List.of(), lines, List.of(new TownForm.Action("设为导航目标", () -> { if (VersionClient.navigate(record)) VersionClient.show(null); })), null));
    }
}
