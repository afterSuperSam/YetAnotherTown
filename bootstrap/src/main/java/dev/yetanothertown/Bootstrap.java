package dev.yetanothertown;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** 按运行中的游戏版本选择独立适配层。 */
public final class Bootstrap implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow().getMetadata().getVersion().getFriendlyString();
        String adapter = switch (version) {
            case "26.3" -> "v263";
            case "26.2" -> "v262";
            case "26.1.2" -> "v2612";
            default -> throw new IllegalStateException("YetAnotherTown 不支持当前 Minecraft 版本：" + version);
        };
        try {
            Class.forName("dev.yetanothertown." + adapter + ".VersionClient")
                    .getMethod("initialize").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("YetAnotherTown 客户端初始化失败", exception);
        }
    }
}
