package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static dev.yetanothertown.client.TownGateway.object;

/** 统一入口、当前游戏身份与版本间客户端生命周期。 */
public final class VersionClient {
    private static final System.Logger LOGGER = System.getLogger("Yet Another Town");
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("yetanothertown", "general"));
    private static KeyMapping openKey;
    private static KeyMapping projectKey;
    private static long nextRefresh;
    private static boolean refreshing;
    private static boolean connectingIdentity;
    private static long nextIdentityAttempt;
    private static long nextMapBindingAttempt;
    private static String observedIdentity = "";
    private static Method screenSetter;
    private static Method screenGetter;
    private static Field screenField;
    private static Object screenOwner;
    private static java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> skin;
    private static final java.util.Map<String, java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin>> memberSkins = new java.util.HashMap<>();
    private static String avatarTown = "";
    private static JsonObject navigation;
    private static String navigationTown = "";
    private static String navigationWorld = "";
    private static Method hiddenGetter;
    private static Field hiddenField;
    private static Object hiddenOwner;

    private VersionClient() { }

    public static void initialize() {
        TownGateway gateway = TownGateway.get();
        gateway.initialize(FabricLoader.getInstance().getConfigDir());
        KeyMapping[] mappings = createKeyMappings();
        openKey = KeyMappingHelper.registerKeyMapping(mappings[0]);
        projectKey = KeyMappingHelper.registerKeyMapping(mappings[1]);
        TownHud.initialize();
        XaeroBridge.initialize();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            initializeScreens(client);
            skin = client.getSkinManager().createLookup(client.getGameProfile(), false);
            LOGGER.log(System.Logger.Level.INFO, "Yet Another Town client initialized");
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.nanoTime();
            String identity = client.player == null ? "" : playerId() + ":" + playerName();
            if (!observedIdentity.equals(identity)) {
                gateway.logout();
                observedIdentity = identity;
                nextIdentityAttempt = 0;
            }
            if (!identity.isEmpty() && !gateway.loggedIn() && !connectingIdentity && now >= nextIdentityAttempt) connectIdentity(() -> {});
            if (client.player != null && now >= nextMapBindingAttempt) {
                try { gateway.autoBindMap(serverAddress(), dimension(), XaeroBridge.playerMapKey(dimension())); }
                catch (IllegalStateException | IllegalArgumentException exception) {
                    nextMapBindingAttempt = now + gateway.config().pollIntervalSeconds * 1_000_000_000L;
                    gateway.status = exception.getMessage();
                    LOGGER.log(System.Logger.Level.WARNING, "Automatic map binding failed", exception);
                }
            }
            TownHud.tick();
            boolean openRequested = false, projectRequested = false;
            while (openKey.consumeClick()) openRequested = true;
            while (projectKey.consumeClick()) projectRequested = true;
            if (client.player != null && currentScreen() == null) {
                if (openRequested) show(new TownScreen());
                else if (projectRequested) TownHud.openProject();
            }
            if (navigation != null && (!gateway.loggedIn() || !navigationTown.equals(gateway.townId) || !navigationWorld.equals(gateway.worldId) || !XaeroBridge.isCurrentWorld())) clearNavigation();
            if (gateway.loggedIn() && !gateway.townId.isEmpty() && !refreshing && now >= nextRefresh) {
                refreshing = true;
                nextRefresh = now + gateway.config().pollIntervalSeconds * 1_000_000_000L;
                gateway.refresh().whenComplete((value, error) -> client.execute(() -> refreshing = false));
            }
        });
    }

    static KeyMapping[] createKeyMappings() {
        return new KeyMapping[] {
            new KeyMapping("key.yetanothertown.open", InputConstants.KEY_K, KEY_CATEGORY),
            new KeyMapping("key.yetanothertown.project", InputConstants.KEY_X, KEY_CATEGORY)
        };
    }

    public static Screen createConfigScreen(Screen parent) {
        TownGateway gateway = TownGateway.get();
        var settings = gateway.config();
        return new TownForm(parent, "配置", List.of(
                TownForm.Field.text("endpoint", "服务地址", settings.endpoint),
                TownForm.Field.number("entrySubtitleSeconds", "进入项目字幕（秒，0关闭）", settings.entrySubtitleSeconds),
                TownForm.Field.number("entryCooldownSeconds", "重复进入提示间隔（秒）", settings.entryCooldownSeconds),
                TownForm.Field.number("pollIntervalSeconds", "资料刷新间隔（秒）", settings.pollIntervalSeconds),
                TownForm.Field.toggle("animationEnabled", "界面动画", settings.animationEnabled),
                TownForm.Field.toggle("projectOverlayEnabled", "在 Xaero 地图显示项目", settings.projectOverlayEnabled),
                TownForm.Field.action("keyBindings", "打开原版按键绑定", () -> show(new KeyBindsScreen(currentScreen(), Minecraft.getInstance().options)))),
                List.of(), List.of(),
                values -> {
                    gateway.configure(values.get("endpoint").getAsString(), values.get("entrySubtitleSeconds").getAsInt(), values.get("entryCooldownSeconds").getAsInt(),
                            values.get("pollIntervalSeconds").getAsInt(), values.get("animationEnabled").getAsBoolean(), values.get("projectOverlayEnabled").getAsBoolean());
                    show(parent);
                }).withBreadcrumbTitle();
    }

    private static void initializeScreens(Minecraft minecraft) {
        try {
            screenOwner = minecraft;
            try {
                screenSetter = Minecraft.class.getMethod("setScreen", Screen.class);
                screenField = Minecraft.class.getField("screen");
            } catch (NoSuchMethodException exception) {
                screenOwner = minecraft.gui;
                screenSetter = screenOwner.getClass().getMethod("setScreen", Screen.class);
                screenGetter = screenOwner.getClass().getMethod("screen");
            }
            try {
                hiddenOwner = minecraft.options;
                hiddenField = hiddenOwner.getClass().getField("hideGui");
            } catch (NoSuchFieldException exception) {
                hiddenOwner = minecraft.gui.getClass().getField("hud").get(minecraft.gui);
                hiddenGetter = hiddenOwner.getClass().getMethod("isHidden");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法初始化当前版本的界面接口", exception);
        }
    }

    public static void show(Screen screen) {
        try { screenSetter.invoke(screenOwner, screen); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("打开小镇界面失败", exception); }
    }

    static Screen currentScreen() {
        try { return (Screen) (screenField == null ? screenGetter.invoke(screenOwner) : screenField.get(screenOwner)); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("读取当前界面失败", exception); }
    }
    static boolean hudHidden() {
        try { return hiddenField == null ? (boolean) hiddenGetter.invoke(hiddenOwner) : hiddenField.getBoolean(hiddenOwner); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("读取 HUD 显示状态失败", exception); }
    }

    static void connectIdentity(Runnable success) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || connectingIdentity) return;
        connectingIdentity = true;
        nextIdentityAttempt = System.nanoTime() + TownGateway.get().config().pollIntervalSeconds * 1_000_000_000L;
        String identity = playerId() + ":" + playerName();
        TownGateway.get().login(playerId(), playerName()).whenComplete((result, failure) -> minecraft.execute(() -> {
            connectingIdentity = false;
            if (failure == null && minecraft.player != null && identity.equals(playerId() + ":" + playerName()) && TownGateway.get().loggedIn()) {
                if (TownGateway.get().townId.isEmpty() && TownGateway.get().towns.size() > 0) TownGateway.get().selectTown(TownGateway.get().towns.get(0).getAsJsonObject().get("id").getAsString());
                success.run();
            }
        }));
    }

    static String playerName() { var client = Minecraft.getInstance(); return client.player == null ? client.getUser().getName() : client.player.getGameProfile().name(); }
    static String playerId() { var client = Minecraft.getInstance(); return (client.player == null ? client.getUser().getProfileId() : client.player.getUUID()).toString().replace("-", ""); }
    static String dimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? "minecraft:overworld" : level.dimension().identifier().toString();
    }
    static String serverAddress() {
        var server = Minecraft.getInstance().getCurrentServer();
        return server == null ? "singleplayer" : server.ip;
    }
    static JsonObject position() {
        var player = Minecraft.getInstance().player;
        return object("dimension", dimension(), "x", player == null ? 0 : (int) Math.floor(player.getX()), "y", player == null ? 64 : (int) Math.floor(player.getY()), "z", player == null ? 0 : (int) Math.floor(player.getZ()));
    }
    static void copy(String text) { Minecraft.getInstance().keyboardHandler.setClipboard(text); }
    static String projectKeyLabel() { return projectKey == null ? "X" : projectKey.isUnbound() ? "" : projectKey.getTranslatedKeyMessage().getString(); }

    static void drawAvatar(GuiGraphicsExtractor graphics, int x, int y, int size) {
        if (skin == null) return;
        drawSkin(graphics, skin.get().body().texturePath(), x, y, size);
    }

    /** 在线成员使用服务器皮肤，离线成员通过原版缓存提供头像与默认皮肤。 */
    static void drawAvatar(GuiGraphicsExtractor graphics, String playerId, String name, int x, int y, int size) {
        String compactId = playerId.replace("-", "");
        if (!compactId.matches("[a-fA-F0-9]{32}")) { TownStyle.icon(graphics, 3, x + size / 2 - 4, y + size / 2 - 4, TownStyle.MUTED); return; }
        java.util.UUID id = new java.util.UUID(Long.parseUnsignedLong(compactId.substring(0, 16), 16), Long.parseUnsignedLong(compactId.substring(16), 16));
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        var online = connection == null ? null : connection.getPlayerInfo(id);
        if (online != null) { drawSkin(graphics, online.getSkin().body().texturePath(), x, y, size); return; }
        String scope = TownGateway.get().config().endpoint + "#" + TownGateway.get().townId;
        if (!avatarTown.equals(scope)) { memberSkins.clear(); avatarTown = scope; }
        var lookup = memberSkins.computeIfAbsent(compactId, ignored -> client.getSkinManager().createLookup(new com.mojang.authlib.GameProfile(id, name), false));
        drawSkin(graphics, lookup.get().body().texturePath(), x, y, size);
    }

    private static void drawSkin(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size) {
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, TownScreen.ACCENT);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8, 8, size, size, 8, 8, 64, 64);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40, 8, size, size, 8, 8, 64, 64);
    }

    static void drawLogo(GuiGraphicsExtractor graphics, String identifier, int x, int y) {
        drawLogo(graphics, identifier, x, y, 28);
    }

    static void drawLogo(GuiGraphicsExtractor graphics, String identifier, int x, int y, int size) {
        if (identifier.startsWith("texture:")) {
            Identifier texture = Identifier.tryParse(identifier.substring("texture:".length()));
            if (texture != null) { graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, size, size); return; }
        }
        if (Minecraft.getInstance().level == null) {
            TownStyle.icon(graphics, 0, x + size / 2 - 4, y + size / 2 - 4, TownStyle.ACCENT);
            return;
        }
        Identifier id = Identifier.tryParse(identifier);
        var item = id == null ? Items.OAK_SAPLING : BuiltInRegistries.ITEM.getValue(id);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 4, y + 4).scale(Math.max(8, size - 8) / 16f);
        graphics.item(new ItemStack(item == null || item == Items.AIR ? Items.OAK_SAPLING : item), 0, 0);
        graphics.pose().popMatrix();
    }

    static boolean navigate(JsonObject record) {
        if (!XaeroBridge.isCurrentWorld()) { TownGateway.get().status = "导航仅在当前匹配的世界与周目中可用"; return false; }
        JsonObject target = record.deepCopy();
        if (record.has("chunks")) {
            JsonObject chunks = record.getAsJsonObject("chunks");
            target.addProperty("x", (TownScreen.integer(chunks, "minX") + TownScreen.integer(chunks, "maxX") + 1) * 8);
            target.addProperty("z", (TownScreen.integer(chunks, "minZ") + TownScreen.integer(chunks, "maxZ") + 1) * 8);
            target.addProperty("y", TownScreen.integer(position(), "y"));
        }
        if (!XaeroBridge.navigate(target)) return false;
        navigation = target;
        navigationTown = TownGateway.get().townId;
        navigationWorld = TownGateway.get().worldId;
        TownGateway.get().status = "已设为导航目标：" + TownScreen.stringValue(target, "name", TownScreen.stringValue(target, "title", "目标"));
        return true;
    }
    static JsonObject navigation() { return navigation; }
    static void clearNavigation() { navigation = null; navigationTown = ""; navigationWorld = ""; XaeroBridge.clearNavigation(); }
}
