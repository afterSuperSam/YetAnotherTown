package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;

import static dev.yetanothertown.client.TownGateway.object;

/** 统一入口、正版会话与版本间客户端生命周期。 */
public final class VersionClient {
    private static final System.Logger LOGGER = System.getLogger("YetAnotherTown");
    private static KeyMapping openKey;
    private static KeyMapping nearbyKey;
    private static long nextRefresh;
    private static boolean refreshing;
    private static Method screenSetter;
    private static Method screenGetter;
    private static Field screenField;
    private static Object screenOwner;
    private static java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> skin;
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
        Minecraft minecraft = Minecraft.getInstance();
        initializeScreens(minecraft);
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.yetanothertown.open", gateway.config().openKey, KeyMapping.Category.MISC));
        nearbyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.yetanothertown.nearby", gateway.config().nearbyKey, KeyMapping.Category.MISC));
        skin = minecraft.getSkinManager().createLookup(minecraft.getGameProfile(), false);
        TownHud.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) show(new TownScreen(currentScreen()));
            while (nearbyKey.consumeClick()) TownHud.openNearby();
            if (navigation != null && (!gateway.loggedIn() || !navigationTown.equals(gateway.townId) || !navigationWorld.equals(gateway.worldId) || !XaeroBridge.isCurrentWorld())) clearNavigation();
            long now = System.nanoTime();
            if (gateway.loggedIn() && !gateway.townId.isEmpty() && !refreshing && now >= nextRefresh) {
                refreshing = true;
                nextRefresh = now + gateway.config().pollIntervalSeconds * 1_000_000_000L;
                gateway.refresh().whenComplete((value, error) -> client.execute(() -> refreshing = false));
            }
        });
        LOGGER.log(System.Logger.Level.INFO, "YetAnotherTown client initialized");
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

    static void authenticate(Runnable success) {
        Minecraft minecraft = Minecraft.getInstance();
        var user = minecraft.getUser();
        TownGateway.get().login(user.getName(), serverHash -> {
            try { minecraft.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverHash); }
            catch (Exception exception) { throw new CompletionException("正版会话验证失败，请重新登录启动器", exception); }
        }).thenAccept(result -> minecraft.execute(success));
    }

    static String playerName() { return Minecraft.getInstance().getUser().getName(); }
    static String playerId() { return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", ""); }
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
    static String nearbyKeyLabel() { return nearbyKey == null ? "H" : nearbyKey.getTranslatedKeyMessage().getString(); }

    static void drawAvatar(GuiGraphicsExtractor graphics, int x, int y, int size) {
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, TownScreen.ACCENT);
        if (skin == null) return;
        Identifier texture = skin.get().body().texturePath();
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8, 8, size, size, 8, 8, 64, 64);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40, 8, size, size, 8, 8, 64, 64);
    }

    static void drawLogo(GuiGraphicsExtractor graphics, String identifier, int x, int y) {
        if (identifier.startsWith("texture:")) {
            Identifier texture = Identifier.tryParse(identifier.substring("texture:".length()));
            if (texture != null) { graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x - 12, y, 0, 0, 28, 28, 28, 28); return; }
        }
        if (Minecraft.getInstance().level == null) {
            graphics.fill(x - 10, y, x + 16, y + 26, 0xFF637B4C);
            graphics.fill(x - 5, y + 7, x + 11, y + 12, TownScreen.ACCENT);
            graphics.fill(x, y + 12, x + 6, y + 22, TownScreen.ACCENT);
            return;
        }
        Identifier id = Identifier.tryParse(identifier);
        var item = id == null ? Items.OAK_SAPLING : BuiltInRegistries.ITEM.getValue(id);
        graphics.item(new ItemStack(item == null || item == Items.AIR ? Items.OAK_SAPLING : item), x, y);
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
