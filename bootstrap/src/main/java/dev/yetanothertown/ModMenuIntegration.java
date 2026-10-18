package dev.yetanothertown;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** 仅在安装 ModMenu 时提供对应版本的配置界面。 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return (Screen) Class.forName("dev.yetanothertown." + Bootstrap.adapter() + ".VersionClient")
                        .getMethod("createConfigScreen", Screen.class).invoke(null, parent);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("YetAnotherTown 配置界面初始化失败", exception);
            }
        };
    }
}
