package com.farcr.treephysics.collision_callback;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.callback.FragileBlockCallback;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public class LeavesBlockCallback extends FragileBlockCallback {
    public static final LeavesBlockCallback INSTANCE = new LeavesBlockCallback();
    private static final Map<SubLevelPhysicsSystem, Map<BlockPos, PendingBreak>> PENDING_BREAKS = new WeakHashMap<>();

    @Override
    public double getTriggerVelocity() {
        return 0.0;
    }

    @Override
    public boolean shouldTriggerFor(BlockState state) {
        return state.getBlock() instanceof LeavesBlock;
    }

    @Override
    public CollisionResult onHit(ServerLevel level, BlockPos pos, BlockState state, Vector3d hitPos) {
        if (!SubLevelPhysicsSystem.IN_PHYSICS_STEP) {
            return super.onHit(level, pos, state, hitPos);
        }

        // Vinery updates neighbouring leaf states immediately. Baking their new
        // colliders here would re-enter Rapier while its native step holds a lock.
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        PENDING_BREAKS.computeIfAbsent(system, key -> new LinkedHashMap<>())
                .putIfAbsent(pos.immutable(), new PendingBreak(state, subLevel == null ? null : subLevel.getUniqueId()));
        return new CollisionResult(JOMLConversion.ZERO, true);
    }

    public static void flushPendingBreaks(SubLevelPhysicsSystem system) {
        if (SubLevelPhysicsSystem.IN_PHYSICS_STEP) return;
        Map<BlockPos, PendingBreak> pending = PENDING_BREAKS.remove(system);
        if (pending == null) return;

        ServerLevel level = system.getLevel();
        pending.forEach((pos, entry) -> {
            SubLevel subLevel = entry.subLevelId == null ? null : SubLevelContainer.getContainer(level).getSubLevel(entry.subLevelId);
            if ((entry.subLevelId == null || (subLevel != null && !subLevel.isRemoved())) && level.getBlockState(pos) == entry.state) {
                level.destroyBlock(pos, true);
            }
        });
    }

    private record PendingBreak(BlockState state, UUID subLevelId) {}
}
