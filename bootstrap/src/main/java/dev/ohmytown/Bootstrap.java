package dev.ohmytown;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** 按运行中的游戏版本选择独立适配层。 */
public final class Bootstrap implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            Class.forName("dev.ohmytown." + adapter() + ".VersionClient")
                    .getMethod("initialize").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Oh My Town 客户端初始化失败", exception);
        }
    }

    static String adapter() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow().getMetadata().getVersion().getFriendlyString();
        return switch (version) {
            case "26.3" -> "v263";
            case "26.2" -> "v262";
            case "26.1.2" -> "v2612";
            default -> throw new IllegalStateException("Oh My Town 不支持当前 Minecraft 版本：" + version);
        };
    }

    /** 原生地图注入只转发到当前版本，避免加载其他版本的游戏类型。 */
    public static void mapHook(String method, Object... arguments) {
        try {
            Class<?>[] parameters = new Class<?>[arguments.length];
            java.util.Arrays.fill(parameters, Object.class);
            Class.forName("dev.ohmytown." + adapter() + ".XaeroBridge").getMethod(method, parameters).invoke(null, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Xaero 地图扩展接口不兼容：" + method, exception);
        }
    }
}
