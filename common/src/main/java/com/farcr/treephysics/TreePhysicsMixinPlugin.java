package com.farcr.treephysics;

import foundry.veil.Veil;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class TreePhysicsMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.farcr.treephysics.mixin.compatibility.") ||
                mixinClassName.startsWith("com.farcr.treephysics.neoforge.mixin.compatibility.")) {
            final String[] parts = mixinClassName.split("\\.");
            if (parts.length < 5) {
                return true;
            }

            final String modId = parts[3].equals("mixin") ? parts[5] : parts[6];
            return Veil.platform().isModLoaded(modId);
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
