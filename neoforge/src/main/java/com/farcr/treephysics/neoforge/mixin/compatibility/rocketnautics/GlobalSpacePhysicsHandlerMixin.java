package com.farcr.treephysics.neoforge.mixin.compatibility.rocketnautics;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.devce.rocketnautics.content.physics.GlobalSpacePhysicsHandler", remap = false)
public class GlobalSpacePhysicsHandlerMixin {
    // Older Cosmonautics iterates bodies awaiting removal, including ragdolls.
    // Skip the whole callback so it cannot read velocity or apply forces to them.
    @Inject(method = "processSubLevelPhysics", at = @At("HEAD"), cancellable = true)
    private static void treephysics$skipRemovedBody(ServerSubLevel subLevel, RigidBodyHandle handle,
                                                   ServerLevel level, double timeStep, CallbackInfo ci) {
        if (subLevel.isRemoved() || handle == null || !handle.isValid()) {
            ci.cancel();
        }
    }
}
