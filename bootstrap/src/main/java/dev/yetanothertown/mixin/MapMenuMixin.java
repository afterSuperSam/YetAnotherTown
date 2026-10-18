package dev.yetanothertown.mixin;

import dev.yetanothertown.Bootstrap;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 向原生地图右键菜单追加项目操作，保留 Xaero 原有选区和导航。 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class MapMenuMixin {
    @Inject(method = "getRightClickOptions()Ljava/util/ArrayList;", at = @At("RETURN"), remap = false)
    private void appendProjectOptions(CallbackInfoReturnable<ArrayList<?>> callback) {
        Bootstrap.mapHook("addMapOptions", this, callback.getReturnValue());
    }
}
