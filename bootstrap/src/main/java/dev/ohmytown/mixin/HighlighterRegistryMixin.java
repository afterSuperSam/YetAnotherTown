package dev.ohmytown.mixin;

import dev.ohmytown.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 Xaero 封存原生高亮注册表前登记项目覆盖层。 */
@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
public abstract class HighlighterRegistryMixin {
    @Inject(method = "end()V", at = @At("HEAD"), remap = false)
    private void registerProjects(CallbackInfo callback) {
        Bootstrap.mapHook("registerHighlighter", this);
    }
}
