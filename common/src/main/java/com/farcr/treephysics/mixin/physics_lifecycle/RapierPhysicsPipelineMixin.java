package com.farcr.treephysics.mixin.physics_lifecycle;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RapierPhysicsPipeline.class)
public class RapierPhysicsPipelineMixin {
    // Physics listeners and serializers can retain a handle after a collision
    // removes its body. Never query the native engine through that stale handle.
    @Inject(method = {"getLinearVelocity", "getAngularVelocity"}, at = @At("HEAD"), cancellable = true)
    private void treephysics$removedBodyVelocity(PhysicsPipelineBody body, Vector3d dest,
                                               CallbackInfoReturnable<Vector3d> cir) {
        if (body.isRemoved()) {
            cir.setReturnValue(dest.zero());
        }
    }
}
