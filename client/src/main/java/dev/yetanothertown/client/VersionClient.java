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
    private static TownGateway.Context navigationContext;
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
                nextMapBindingAttempt = now + gateway.config().pollIntervalSeconds * 1_000_000_000L;
                try { XaeroBridge.synchronizeSharedMap(); }
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
            updateNavigation(client.player != null && gateway.loggedIn() && XaeroBridge.isCurrentWorld(), dimension(),
                    client.player == null ? 0 : client.player.getBlockX(), client.player == null ? 0 : client.player.getBlockZ());
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
        return new TownForm(parent, TownText.text("yat.title.config"), List.of(
                TownForm.Field.text("endpoint", TownText.text("yat.field.service_address"), settings.endpoint),
                TownForm.Field.number("entrySubtitleSeconds", TownText.text("yat.field.entry_subtitle_duration"), settings.entrySubtitleSeconds),
                TownForm.Field.number("entryCooldownSeconds", TownText.text("yat.field.entry_hint_interval"), settings.entryCooldownSeconds),
                TownForm.Field.number("pollIntervalSeconds", TownText.text("yat.field.refresh_interval"), settings.pollIntervalSeconds),
                TownForm.Field.toggle("animationEnabled", TownText.text("yat.field.ui_animation"), settings.animationEnabled),
                TownForm.Field.toggle("projectOverlayEnabled", TownText.text("yat.field.show_projects"), settings.projectOverlayEnabled),
                TownForm.Field.action("keyBindings", TownText.text("yat.ui.open_controls"), () -> show(new KeyBindsScreen(currentScreen(), Minecraft.getInstance().options)))),
                gateway.configurationNotice.isEmpty() ? List.of() : List.of(gateway.configurationNotice), List.of(),
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
            throw new IllegalStateException(TownText.text("yat.error.init_version_ui"), exception);
        }
    }

    public static void show(Screen screen) {
        try { screenSetter.invoke(screenOwner, screen); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.open_town_screen"), exception); }
    }

    static Screen currentScreen() {
        try { return (Screen) (screenField == null ? screenGetter.invoke(screenOwner) : screenField.get(screenOwner)); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.read_current_screen"), exception); }
    }
    static boolean hudHidden() {
        try { return hiddenField == null ? (boolean) hiddenGetter.invoke(hiddenOwner) : hiddenField.getBoolean(hiddenOwner); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException(TownText.text("yat.error.read_hud_state"), exception); }
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

    /** 物品与贴图使用相同绘制边界，间距由页面布局决定。 */
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
        graphics.pose().translate(x, y).scale(size / 16f);
        graphics.item(new ItemStack(item == null || item == Items.AIR ? Items.OAK_SAPLING : item), 0, 0);
        graphics.pose().popMatrix();
    }

    static boolean navigate(JsonObject record) {
        if (!XaeroBridge.isCurrentWorld()) { TownGateway.get().status = TownText.text("yat.error.navigation_world"); return false; }
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
        navigationContext = TownGateway.get().context();
        TownGateway.get().status = TownText.text("yat.status.navigation_target_prefix") + navigationName(target);
        return true;
    }
    static JsonObject navigation() { return navigation; }
    static String navigationName(JsonObject target) { return target.has("chunks") ? XaeroBridge.projectName(target) : TownScreen.stringValue(target, "title", TownScreen.stringValue(target, "name", TownText.text("yat.label.navigation_target"))); }
    static void updateNavigation(boolean usable, String dimension, int blockX, int blockZ) {
        TownGateway gateway = TownGateway.get();
        if (navigation == null) return;
        if (!usable || !navigationTown.equals(gateway.townId) || !navigationWorld.equals(gateway.worldId)
                || !gateway.context().equals(navigationContext) || !TownScreen.stringValue(navigation, "dimension").equals(dimension)) {
            clearNavigation();
            return;
        }
        JsonObject chunks = TownScreen.objectValue(navigation, "chunks");
        boolean arrived = chunks.isEmpty()
                ? TownScreen.integer(navigation, "x") == blockX && TownScreen.integer(navigation, "z") == blockZ
                : (blockX >> 4) >= TownScreen.integer(chunks, "minX") && (blockX >> 4) <= TownScreen.integer(chunks, "maxX")
                    && (blockZ >> 4) >= TownScreen.integer(chunks, "minZ") && (blockZ >> 4) <= TownScreen.integer(chunks, "maxZ");
        if (arrived) {
            String name = navigationName(navigation);
            clearNavigation();
            gateway.status = TownText.text("yat.status.arrived_prefix") + name;
        }
    }
    static void stopNavigation() { clearNavigation(); TownGateway.get().status = TownText.text("yat.status.navigation_stopped"); }
    static void clearNavigation() { navigation = null; navigationTown = ""; navigationWorld = ""; navigationContext = null; XaeroBridge.clearNavigation(); }
}
