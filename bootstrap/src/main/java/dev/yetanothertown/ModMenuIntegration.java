package dev.yetanothertown;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/** 仅在安装 ModMenu 时提供对应版本的配置界面。 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            String version = FabricLoader.getInstance().getModContainer("minecraft")
                    .orElseThrow().getMetadata().getVersion().getFriendlyString();
            String adapter = switch (version) {
                case "26.3" -> "v263";
                case "26.2" -> "v262";
                case "26.1.2" -> "v2612";
                default -> throw new IllegalStateException("YetAnotherTown 不支持当前 Minecraft 版本：" + version);
            };
            try {
                return (Screen) Class.forName("dev.yetanothertown." + adapter + ".VersionClient")
                        .getMethod("createConfigScreen", Screen.class).invoke(null, parent);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("YetAnotherTown 配置界面初始化失败", exception);
            }
        };
    }
}
