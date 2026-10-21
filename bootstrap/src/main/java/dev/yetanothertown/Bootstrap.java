package dev.yetanothertown;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** 按运行中的游戏版本选择独立适配层。 */
public final class Bootstrap implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            Class.forName("dev.yetanothertown." + adapter() + ".VersionClient")
                    .getMethod("initialize").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Yet Another Town client initialization failed.", exception);
        }
    }

    static String adapter() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow().getMetadata().getVersion().getFriendlyString();
        return switch (version) {
            case "26.3" -> "v263";
            case "26.2" -> "v262";
            case "26.1.2" -> "v2612";
            default -> throw new IllegalStateException("Yet Another Town does not support this Minecraft version: " + version);
        };
    }

    /** 原生地图注入只转发到当前版本，避免加载其他版本的游戏类型。 */
    public static void mapHook(String method, Object... arguments) {
        try {
            Class<?>[] parameters = new Class<?>[arguments.length];
            java.util.Arrays.fill(parameters, Object.class);
            Class.forName("dev.yetanothertown." + adapter() + ".XaeroBridge").getMethod(method, parameters).invoke(null, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Incompatible Xaero map extension API: " + method, exception);
        }
    }
}
