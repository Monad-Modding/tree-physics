package com.farcr.treephysics.api.manager;

import com.farcr.treephysics.api.util.TreeUtil;
import com.farcr.treephysics.index.TreePhysicsConfig;
import com.farcr.treephysics.networking.UpdateClientTrees;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import foundry.veil.api.network.VeilPacketManager;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerTreeManager extends SavedData implements TreeManager {
    public static final String ID = "treephysics_trees";

    private final Map<UUID, TreeData> trees = new Object2ObjectOpenHashMap<>();
    private ServerLevel level;

    public ServerTreeManager() {
        this(null);
    }

    public ServerTreeManager(ServerLevel level) {
        this.level = level;
    }

    private static final double STOPPED_THRESHOLD = 0.15;

    public void physicsTick(ServerLevel level, SubLevelPhysicsSystem system, PhysicsPipeline pipeline, double timeStep) {
        if(this.trees.isEmpty()) return;

        for (TreeData tree : this.trees.values()) {
            ServerSubLevel subLevel = (ServerSubLevel) tree.getSubLevel(this.level);
            if(subLevel == null || subLevel.isRemoved()) {
                continue;
            }

            RigidBodyHandle handle = system.getPhysicsHandle(subLevel);
            if (!handle.isValid()) {
                continue;
            }

            Vector3d linearVel = handle.getLinearVelocity(new Vector3d());
            Vector3d angularVel = handle.getAngularVelocity(new Vector3d());
            if(linearVel.length() < STOPPED_THRESHOLD && angularVel.length() < STOPPED_THRESHOLD) {
                tree.stoppedTicks++;
            } else {
                tree.stoppedTicks = 0;
            }

            int gravityTicks = TreePhysicsConfig.GRAVITY_MULTIPLIER_TICKS.getAsInt();
            if(gravityTicks == -1 || tree.lifeTicks <= gravityTicks) {
                Vector3d gravity = DimensionPhysicsData.getGravity(level);

                double gravityScale = 1.0 - TreeUtil.getUprightness(subLevel);
                gravityScale *= TreePhysicsConfig.GRAVITY_MULTIPLIER.getAsDouble();

                handle.addLinearAndAngularVelocity(gravity.mul(timeStep * gravityScale), JOMLConversion.ZERO);
            }
        }
    }

    public void tick() {
        if(this.trees.isEmpty()) return;

        for (TreeData tree : this.trees.values()) {
            SubLevel subLevel = tree.getSubLevel(this.level);
            if(subLevel == null || subLevel.isRemoved()) {
                continue;
            }

            /*if(tree.lifeTicks % 2 == 0 && tree.leafBreakProgress > -1 && tree.leafBreakProgress < 8) {
                int distance = LeavesBlock.DECAY_DISTANCE - tree.leafBreakProgress;
                float breakChance = Math.min(distance < 2 ? 0 : 1, (distance * distance) / 25.0f);

                for (BlockPos pos : TreeUtil.plotIterator(subLevel)) {
                    BlockState state = level.getBlockState(pos);
                    if(!(state.getBlock() instanceof LeavesBlock)) continue;
                    if(state.getValue(LeavesBlock.DISTANCE) != distance) continue;

                    boolean shouldBreak = level.getRandom().nextFloat() <= breakChance;
                    if(shouldBreak) {
                        level.destroyBlock(pos, true);
                    }
                }

                tree.leafBreakProgress++;
            }*/

            if(this.shouldDespawn(tree)) {
                if(TreePhysicsConfig.DROP_ITEMS_ON_DESPAWN.get()) {
                    for (BlockPos pos : TreeUtil.plotIterator(subLevel)) {
                        if(!level.getBlockState(pos).isAir()) {
                            level.destroyBlock(pos, true);
                        }
                    }
                }
                subLevel.markRemoved();
                continue;
            }

            tree.lifeTicks++;
            this.setDirty(true);
        }

    }

    public void setTree(SubLevel subLevel) {
        TreeData data = new TreeData(subLevel.getUniqueId());
        data.updateTreeData(this.level);
        this.trees.put(subLevel.getUniqueId(), data);
        this.setDirty();
        this.sendAllTrees();
    }

    public TreeData getTreeData(SubLevel subLevel) {
        return this.trees.get(subLevel.getUniqueId());
    }

    public void updateTree(@Nullable SubLevel subLevel) {
        if(subLevel == null) return;
        TreeData data = this.trees.get(subLevel.getUniqueId());
        if(data != null) {
            int logCount = data.logs;
            data.updateTreeData(this.level);
            if(logCount != data.logs) {
                this.setDirty();
            }
        }
    }

    public void decrementLogs(@Nullable SubLevel subLevel) {
        if(subLevel == null) return;
        TreeData data = this.trees.get(subLevel.getUniqueId());
        if(data != null) {
            data.logs = Math.max(0, data.logs - 1);
            this.setDirty();
        }
    }

    public void unsetTree(SubLevel subLevel) {
        this.trees.remove(subLevel.getUniqueId());
        this.setDirty();
        this.sendAllTrees();
    }

    public void setSplitFrom(SubLevel subLevel, SubLevel split) {
        TreeData originalTree = this.trees.get(subLevel.getUniqueId());
        if(originalTree != null) {
            TreeData data = new TreeData(split.getUniqueId()).copy(originalTree);
            data.updateTreeData(this.level);
            originalTree.updateTreeData(this.level);
            this.trees.put(split.getUniqueId(), data);
            this.sendAllTrees();
            this.setDirty();
        }
    }

    public void startBreakingLeaves(SubLevel subLevel) {
        TreeData data = this.trees.get(subLevel.getUniqueId());
        if(data != null) {
            data.leafBreakProgress = 0;
            this.setDirty();
        }
    }

    public int removeAll() {
        int count = trees.size();
        trees.clear();
        this.setDirty();
        return count;
    }

    @Override
    public void setDirty(boolean dirty) {
        super.setDirty(dirty);
    }

    private void sendAllTrees() {
        VeilPacketManager.all(this.level.getServer())
                .sendPacket(new UpdateClientTrees(this.level.dimension(), this.trees.keySet().stream().toList()));
    }

    public static void sendUpdatePacket(Player player) {
        MinecraftServer server = player.getServer();
        if(server != null) {
            for (ServerLevel serverLevel : server.getAllLevels()) {
                ServerTreeManager handler = ServerTreeManager.get(serverLevel);
                VeilPacketManager.player((ServerPlayer) player).sendPacket(new UpdateClientTrees(serverLevel.dimension(), handler.trees.keySet().stream().toList()));
            }
        }
    }

    @Override
    public boolean isTree(@Nullable SubLevel subLevel) {
        return subLevel != null && trees.containsKey(subLevel.getUniqueId());
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    private static ServerTreeManager create(ServerLevel level, CompoundTag tag, HolderLookup.Provider registries) {
        ServerTreeManager handler = new ServerTreeManager(level);
        ListTag list = (ListTag) tag.get(ID);
        handler.loadTrees(list);
        return handler;
    }

    public static ServerTreeManager get(ServerLevel level) {
        ServerTreeManager handler = level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ServerTreeManager::new, (tag, provider) -> create(level, tag, provider), null),
                ID);
        handler.level = level;
        return handler;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put(ID, this.saveTheTrees());
        return tag;
    }

    private void loadTrees(ListTag list) {
        List<TreeData> treeData = TreeData.CODEC.listOf().parse(NbtOps.INSTANCE, list).getOrThrow();
        for (TreeData data : treeData) {
            this.trees.put(data.subLevelId, data);
        }
    }

    private ListTag saveTheTrees() {
        List<TreeData> values = this.trees.values().stream().toList();
        return (ListTag) TreeData.CODEC.listOf().encodeStart(NbtOps.INSTANCE, values).getOrThrow();
    }

    private boolean shouldDespawn(TreeData data) {
        TreePhysicsConfig.DespawnBehavior behavior = TreePhysicsConfig.DESPAWN_BEHAVIOR.get();
        if(behavior == TreePhysicsConfig.DespawnBehavior.NO_DESPAWN) {
            return false;
        }

        int despawnTime = TreePhysicsConfig.DESPAWN_TIME.getAsInt();

        if(despawnTime == -1) {
            if(data.stoppedTicks < 20) return false;
        } else if(data.lifeTicks < despawnTime) {
            return false;
        }

        if(behavior == TreePhysicsConfig.DespawnBehavior.DESPAWN_ALL) {
            return true;
        }

        return data.logs <= TreePhysicsConfig.SMALL_TREE_MAXIMUM.get();
    }
}
