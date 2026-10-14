package com.farcr.treephysics.mixin.physics_lifecycle;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubLevelPhysicsSystem.class)
public class SubLevelPhysicsSystemMixin {
    @Inject(method = "updateMassDataFromBlockChange", at = @At("HEAD"), cancellable = true)
    private void treephysics$skipRemovedMassUpdate(SubLevel subLevel, BlockPos pos, BlockState oldState,
                                                  BlockState newState, boolean notifyPipeline, CallbackInfo ci) {
        // Removing the last block can mark the plot removed before Sable sees
        // the block change. It must not upload mass or read that body's pose.
        if (subLevel != null && subLevel.isRemoved()) {
            ci.cancel();
        }
    }
}
