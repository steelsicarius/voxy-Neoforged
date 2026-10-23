package me.cortex.voxy.common;

import net.neoforged.fml.loading.FMLLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.util.Set;

public class MixinConfig implements IMixinConfigPlugin {
    private String mixinPackage;
    private final Set<String> fallThrough;

    public MixinConfig() {
        this.fallThrough = Set.of();
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage + ".";//we want to get rid of the .
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixin) {
        if (mixin.startsWith(this.mixinPackage)) {
            var pth = mixin.substring(this.mixinPackage.length());
            if (!pth.contains(".")) return true;
            var targetMod = pth.substring(0, pth.indexOf('.'));
            //filter out fallthrough and if the mod is actually loaded
            if (this.fallThrough.contains(targetMod)) return true;
            //NOTE: mixin config plugins are invoked during the mixin PREPARE phase, way
            // before ModList is built; the early-loading API must be used here.
            var fmlLoader = FMLLoader.getCurrentOrNull();
            if (fmlLoader == null) return true;//fail-open, better to try than to crash
            var loadingModList = fmlLoader.getLoadingModList();
            if (loadingModList == null) return true;//fail-open, better to try than to crash
            return loadingModList.getModFileById(targetMod) != null;
        }
        return true;
    }
}
