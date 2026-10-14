package com.farcr.treephysics.neoforge.gametest;

import com.farcr.treephysics.TreePhysics;
import com.farcr.treephysics.api.manager.ServerTreeManager;
import com.farcr.treephysics.api.manager.TreeData;
import com.farcr.treephysics.api.util.FloodFillUtil;
import com.farcr.treephysics.collision_callback.LeavesBlockCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder(TreePhysics.MOD_ID)
@PrefixGameTestTemplate(false)
public class PhysicsLifecycleTests {
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void fellVineryAppleTree(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockState leaves = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("vinery", "apple_leaves"))
                .defaultBlockState().setValue(BlockStateProperties.PERSISTENT, false)
                .setValue(BlockStateProperties.DISTANCE, 1);
        BlockPos base = helper.absolutePos(new BlockPos(2, 1, 2));
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        level.setBlockAndUpdate(base.below(), Blocks.ROOTED_DIRT.defaultBlockState());
        for (int y = 0; y < 3; y++) {
            level.setBlockAndUpdate(base.above(y), Blocks.OAK_LOG.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    level.setBlockAndUpdate(base.above(2).offset(x, 0, z), leaves);
                }
            }
        }
        FloodFillUtil.blockBroken(level, base, level.getBlockState(base), base.getCenter().add(2, 1, 0), false);
        level.destroyBlock(base, false);
        helper.assertTrue(level.getBlockState(base.above()).isAir(), "Apple tree must be assembled from world blocks");
        helper.runAfterDelay(100, helper::succeed);
    }

    @GameTest(template = "empty")
    public static void deferVineryNeighbourUpdates(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        var system = SubLevelPhysicsSystem.get(level);
        BlockState leaves = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("vinery", "apple_leaves"))
                .defaultBlockState().setValue(BlockStateProperties.PERSISTENT, false);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos, leaves);
        level.setBlockAndUpdate(pos.east(), leaves);
        BlockState before = level.getBlockState(pos);

        // Simulate the JNI callback's context without holding a native lock;
        // a broken implementation then fails an assertion instead of hanging CI.
        var field = SubLevelPhysicsSystem.class.getDeclaredField("currentlySteppingSystem");
        field.setAccessible(true);
        Object previousSystem = field.get(null);
        boolean previousStep = SubLevelPhysicsSystem.IN_PHYSICS_STEP;
        try {
            field.set(null, system);
            SubLevelPhysicsSystem.IN_PHYSICS_STEP = true;
            LeavesBlockCallback.INSTANCE.onHit(level, pos, before, new Vector3d());
            LeavesBlockCallback.INSTANCE.onHit(level, pos, before, new Vector3d());
            helper.assertTrue(level.getBlockState(pos) == before, "Leaf changes must wait until the native step ends");
            LeavesBlockCallback.flushPendingBreaks(system);
            helper.assertTrue(level.getBlockState(pos) == before, "Queue must not flush inside a physics step");
        } finally {
            SubLevelPhysicsSystem.IN_PHYSICS_STEP = previousStep;
            field.set(null, previousSystem);
        }
        LeavesBlockCallback.flushPendingBreaks(system);
        helper.assertTrue(level.getBlockState(pos).isAir(), "Queued leaf must break after the native step");
        helper.assertTrue(!level.getBlockState(pos.east()).isAir(), "Neighbour leaf must survive its state update");

        // Also exercise the final-block mass update introduced by deferred breaks.
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        ServerSubLevel subLevel = createLog(helper, container);
        BlockPos lastBlock = subLevel.getPlot().getCenterBlock();
        level.destroyBlock(lastBlock, false);
        helper.assertTrue(subLevel.isRemoved(), "Empty plots must be removed safely");
        container.processSubLevelRemovals();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removedBodyVelocity(GameTestHelper helper) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        ServerSubLevel subLevel = createLog(helper, container);
        RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(subLevel);

        Vector3d linear = new Vector3d(1, 2, 3);
        Vector3d angular = new Vector3d(4, 5, 6);
        handle.addLinearAndAngularVelocity(linear, angular);
        helper.assertTrue(handle.getLinearVelocity(new Vector3d()).equals(linear, 1.0e-6),
                "Live body linear velocity must still come from Rapier");
        helper.assertTrue(handle.getAngularVelocity(new Vector3d()).equals(angular, 1.0e-6),
                "Live body angular velocity must still come from Rapier");

        subLevel.markRemoved();
        assertZeroVelocity(helper, handle);
        container.processSubLevelRemovals();
        assertZeroVelocity(helper, handle);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removedTreeTick(GameTestHelper helper) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        ServerSubLevel subLevel = createLog(helper, container);
        ServerTreeManager manager = ServerTreeManager.get(helper.getLevel());
        manager.setTree(subLevel);
        TreeData data = manager.getTreeData(subLevel);
        int lifeTicks = data.lifeTicks;

        subLevel.markRemoved();
        manager.tick();
        manager.physicsTick(helper.getLevel(), container.physicsSystem(),
                container.physicsSystem().getPipeline(), 1.0 / 20.0);
        helper.assertTrue(data.lifeTicks == lifeTicks, "Removed trees must not keep ticking");
        container.processSubLevelRemovals();
        helper.assertTrue(!manager.isTree(subLevel), "Removed trees must leave the manager");
        helper.succeed();
    }

    private static ServerSubLevel createLog(GameTestHelper helper, ServerSubLevelContainer container) {
        Pose3d pose = new Pose3d();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        pose.position().set(pos.getX(), pos.getY(), pos.getZ());
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        subLevel.getPlot().newEmptyChunk(subLevel.getPlot().getCenterChunk());
        subLevel.getPlot().getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.OAK_LOG.defaultBlockState(), 3);
        subLevel.updateLastPose();
        return subLevel;
    }

    @SuppressWarnings("deprecation")
    private static void assertZeroVelocity(GameTestHelper helper, RigidBodyHandle handle) {
        Vector3d dest = new Vector3d(7, 8, 9);
        helper.assertTrue(handle.getLinearVelocity(dest) == dest && dest.lengthSquared() == 0,
                "Removed linear velocity must clear and return the supplied vector");
        dest.set(7, 8, 9);
        helper.assertTrue(handle.getAngularVelocity(dest) == dest && dest.lengthSquared() == 0,
                "Removed angular velocity must clear and return the supplied vector");
        helper.assertTrue(handle.getLinearVelocity().lengthSquared() == 0,
                "Legacy linear getter used by Cosmonautics must be safe");
        helper.assertTrue(handle.getAngularVelocity().lengthSquared() == 0,
                "Legacy angular getter must be safe");
    }
}
