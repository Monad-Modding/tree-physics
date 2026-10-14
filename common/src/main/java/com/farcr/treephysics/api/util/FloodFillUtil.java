package com.farcr.treephysics.api.util;

import com.farcr.treephysics.api.flood_fill.TreeFloodFill;
import com.farcr.treephysics.api.flood_fill.TreeResult;
import com.farcr.treephysics.api.manager.ServerTreeManager;
import com.farcr.treephysics.api.manager.TreeManager;
import com.farcr.treephysics.index.TreePhysicsConfig;
import com.farcr.treephysics.index.TreePhysicsTags;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FloodFillUtil {
    public static final BlockPos[] DIRECTION_OFFSETS_CORNERS = new BlockPos[] {
            new BlockPos(1, 0, 0),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0),
            new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0),
            new BlockPos(-1, -1, 0),
            new BlockPos(1, -1, 0),
            new BlockPos(-1, 1, 0),
            new BlockPos(1, 0, 1),
            new BlockPos(-1, 0, -1),
            new BlockPos(1, 0, -1),
            new BlockPos(-1, 0, 1),
            new BlockPos(0, 1, 1),
            new BlockPos(0, -1, -1),
            new BlockPos(0, -1, 1),
            new BlockPos(0, 1, -1),
            new BlockPos(1, 1, 1),
            new BlockPos(1, 1, -1),
            new BlockPos(-1, 1, -1),
            new BlockPos(-1, 1, 1),
            new BlockPos(1, -1, 1),
            new BlockPos(1, -1, -1),
            new BlockPos(-1, -1, -1),
            new BlockPos(-1, -1, 1)
    };

    private static final TreeFloodFill TREE_VALIDATOR = new TreeFloodFill()
            .addRule(FloodFillUtil::logRule)
            .setEarlyReturn(TreeResult::hasRoot);

    private static final TreeFloodFill ROOTLESS_TREE_VALIDATOR = new TreeFloodFill()
            .addRule(FloodFillUtil::logRule)
            .addRule(FloodFillUtil::leafRule)
            .setEarlyReturn(result -> result.hasRoot() || (result.hasDirt() && result.hasLeaves()));

    private static final TreeFloodFill TREE_FINDER = new TreeFloodFill()
            .addRule(FloodFillUtil::logRule)
            .addRule(FloodFillUtil::leafRule)
            .addRule(FloodFillUtil::attachmentRule)
            .addRule(FloodFillUtil::fallingBlockRule)
            .addTag(TreePhysicsTags.TREE)
            .addTag(TreePhysicsTags.FALLS_FROM_TREES);

    public static void blockBroken(ServerLevel level, BlockPos brokenPos, BlockState brokenState, @Nullable Vec3 brokenFrom, boolean skipWorldSplit) {
        if(!TreeUtil.isLog(brokenState)) return;

        ServerTreeManager manager = (ServerTreeManager) TreeManager.get(level);
        if(!skipWorldSplit && !manager.isTree(brokenPos)) {

            BlockPos belowPos = brokenPos.below();
            BlockState belowState = level.getBlockState(belowPos);

            List<ServerSubLevel> subLevels = FloodFillUtil.trySplit(level, brokenPos);

            if(TreeUtil.isRoot(belowState) && TreePhysicsConfig.REMOVE_ROOTED_DIRT.get()) {
                if(TreePhysicsConfig.DROP_HANGING_ROOTS.get()) {
                    Block.popResourceFromFace(level, belowPos, Direction.UP, Blocks.HANGING_ROOTS.asItem().getDefaultInstance());
                }
                level.setBlock(belowPos, Blocks.DIRT.defaultBlockState(), 2);
            }

            if(brokenFrom != null && TreeUtil.getLogAxis(brokenState) == Direction.Axis.Y) {
                brokenFrom = Sable.HELPER.projectOutOfSubLevel(level, brokenFrom);
                Vec3 breakDirection = brokenFrom.subtract(brokenPos.getCenter()).normalize().multiply(1, 0, 1);
                Vector3d forward = new Vector3d(JOMLConversion.toJOML(Direction.getNearest(breakDirection).getNormal()));
                forward.rotateAxis(Math.toRadians(level.getRandom().nextIntBetweenInclusive(-25, 25)), 0, 1, 0);

                Vector3d torque = forward.cross(0, 1, 0, new Vector3d()).mul(TreePhysicsConfig.IMPULSE_TORQUE.getAsDouble());
                Vector3d velocity = forward.negate(new Vector3d()).mul(TreePhysicsConfig.IMPULSE_FORCE.getAsDouble());

                for (ServerSubLevel subLevel : subLevels) {
                    SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
                    RigidBodyHandle handle = system.getPhysicsHandle(subLevel);
                    // Modded block updates during assembly can empty the new plot.
                    if (handle.isValid()) {
                        handle.addLinearAndAngularVelocity(velocity, torque);
                    }
                }
            }

        } else if (manager.isTree(brokenPos)) {

            List<TreeResult> results = findTreesAround(level, brokenPos);
            if(results.size() > 1) {
                results = new ArrayList<>(results.stream().sorted().toList());
                results.removeFirst();
                for (TreeResult result : results) {
                    BoundingBox3i box = BoundingBox3i.from(result.getBlocks());
                    SubLevelAssemblyHelper.assembleBlocks(level, brokenPos, result.getBlocks(), box);
                }
            }

        }
    }

    public static boolean isValidTree(BlockGetter blockGetter, BlockPos pos) {
        boolean rootless = TreePhysicsConfig.ROOTLESS_TREE_DETECTION.getAsBoolean();
        TreeResult tree = (rootless ? ROOTLESS_TREE_VALIDATOR : TREE_VALIDATOR).findBlocks(blockGetter, pos);
        if(tree != null) {
            return tree.hasRoot() || (rootless && tree.hasLeaves() && tree.hasDirt());
        }
        return false;
    }

    public static List<ServerSubLevel> trySplit(ServerLevel level, BlockPos pos) {
        if(!isValidTree(level, pos)) {
            return List.of();
        }

        List<ServerSubLevel> subLevels = new ArrayList<>();
        ServerTreeManager manager = (ServerTreeManager) TreeManager.get(level);

        List<TreeResult> treeResults = findTreesAround(level, pos);
        if(treeResults.isEmpty()) {
            return subLevels;
        }

        for (TreeResult tree : treeResults) {
            if(tree != null && !(TreePhysicsConfig.ROOTLESS_TREE_DETECTION.get() ? tree.hasRoot() || tree.hasDirt() : tree.hasRoot())) {
                Set<BlockPos> treeBlocks = tree.getBlocks(TreePhysicsTags.TREE);
                ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, pos, treeBlocks, new BoundingBox3i(pos, pos));
                if (!subLevel.isRemoved()) {
                    subLevels.add(subLevel);
                    manager.setTree(subLevel);
                }

                Set<BlockPos> fallingBlocks = tree.getBlocks(TreePhysicsTags.FALLS_FROM_TREES);
                for (BlockPos blockPos : fallingBlocks) {
                    BlockState state = level.getBlockState(blockPos);
                    if (PhysicsBlockPropertyHelper.getMass(level, pos, state) > 0.0) {
                        SubLevelAssemblyHelper.assembleBlocks(level, blockPos, List.of(blockPos), new BoundingBox3i(blockPos, blockPos));
                    }
                }

                for (BlockPos blockPos : tree.getBlocks()) {
                    level.setBlock(blockPos, Blocks.BARRIER.defaultBlockState(), 2);
                    level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }

        return subLevels;
    }

    public static List<TreeResult> findTreesAround(ServerLevel level, BlockPos pos) {
        List<TreeResult> results = new ArrayList<>();
        LongOpenHashSet blocksFound = new LongOpenHashSet();
        TreeFloodFill floodFill = TREE_FINDER.ignore(pos);

        for (BlockPos offset : DIRECTION_OFFSETS_CORNERS) {
            BlockPos startPos = pos.offset(offset);
            if(blocksFound.contains(startPos.asLong())) continue;

            TreeResult result = floodFill.findBlocks(level, startPos);
            if(result == null) continue;

            results.add(result);
            for (BlockPos block : result.getBlocks()) {
                blocksFound.add(block.asLong());
            }
        }

        return results;
    }

    public static boolean logRule(BlockPos fromPos, BlockPos toPos, BlockState fromState, BlockState toState, TreeResult result) {
        if(TreeUtil.isLog(fromState) && TreeUtil.isLog(toState)) {
            if(TreeUtil.isSameLogType(fromState, toState)) {
                return true;
            } else {
                BlockPos relative = toPos.subtract(fromPos);
                Direction direction = Direction.getNearest(relative.getX(), relative.getY(), relative.getZ());

                Direction.Axis fromAxis = TreeUtil.getLogAxis(fromState);
                Direction.Axis toAxis = TreeUtil.getLogAxis(toState);
                return fromAxis == direction.getAxis() && toAxis == direction.getAxis();
            }
        }

        return false;
    }

    public static boolean leafRule(BlockPos fromPos, BlockPos toPos, BlockState fromState, BlockState toState, TreeResult result) {
        if(TreeUtil.isLog(fromState) && TreeUtil.isLeaf(toState)) {
            return true;
        }

        if(TreeUtil.isLeaf(fromState) && TreeUtil.isLeaf(toState)) {
            if(TreeUtil.isSameLeafType(fromState, toState)) {
                int fromDistance = TreeUtil.getLeafDistance(fromState, fromPos, result);
                int toDistance = TreeUtil.getLeafDistance(toState, toPos, result);
                return toDistance > fromDistance;
            }
        }

        return false;
    }

    public static boolean attachmentRule(BlockPos fromPos, BlockPos toPos, BlockState fromState, BlockState toState, TreeResult result) {
        return !fromState.is(TreePhysicsTags.STAYS_ON_TREE) && toState.is(TreePhysicsTags.STAYS_ON_TREE);
    }

    public static boolean fallingBlockRule(BlockPos fromPos, BlockPos toPos, BlockState fromState, BlockState toState, TreeResult result) {
        return !fromState.is(TreePhysicsTags.FALLS_FROM_TREES) && toState.is(TreePhysicsTags.FALLS_FROM_TREES);
    }
}
