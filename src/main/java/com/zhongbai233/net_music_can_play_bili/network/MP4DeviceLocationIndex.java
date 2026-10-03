package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities.ItemHandler;
import net.neoforged.neoforge.items.IItemHandler;

public final class MP4DeviceLocationIndex {
   private static final int GRAPH_TTL_TICKS = 1200;
   private static final int GRAPH_REBUILD_COOLDOWN_TICKS = 100;
   private static final int INDEX_MAX_DEPTH = 5;
   private static final int INDEX_MAX_NODES = 32;
   private static final int INDEX_MAX_STORAGE_NODES = 16;
   private static final int INDEX_MAX_FRONTIER_PER_DEPTH = 6;
   private static final int INDEX_MAX_BRANCHES_PER_NODE = 3;
   private static final int INDEX_MAX_TOTAL_BRANCH_POINTS = 3;
   private static final double ITEM_ENTITY_SEARCH_RADIUS = 4.0;
   private static final double CONTAINER_ENTITY_SEARCH_RADIUS = 6.0;
   private static final int FALLBACK_SCAN_RADIUS = 3;
   private static final int FALLBACK_MAX_BLOCK_ENTITIES = 64;
   private static final int FALLBACK_MAX_SLOTS = 1024;
   private static final Map<PlaybackSourceId, MP4DeviceLocationIndex.LocationRef> LOCATIONS = new ConcurrentHashMap<>();
   private static final Map<MP4DeviceLocationIndex.GraphKey, MP4DeviceLocationIndex.LogisticsGraph> GRAPHS = new ConcurrentHashMap<>();

   private MP4DeviceLocationIndex() {
   }

   public static void clear() {
      LOCATIONS.clear();
      GRAPHS.clear();
   }

   public static void recordPlayer(ServerLevel level, ServerPlayer player, UUID deviceId) {
      if (level != null && player != null && deviceId != null) {
         LOCATIONS.put(
            PlaybackSourceId.of(deviceId),
            MP4DeviceLocationIndex.LocationRef.player(
               level.dimension(), player.getUUID(), player.getId(), player.blockPosition(), MonotonicMediaClock.nowTick()
            )
         );
      }
   }

   public static void recordItemEntity(ServerLevel level, ItemEntity entity, UUID deviceId) {
      if (level != null && entity != null && deviceId != null) {
         LOCATIONS.put(
            PlaybackSourceId.of(deviceId),
            MP4DeviceLocationIndex.LocationRef.item(level.dimension(), entity.getId(), entity.blockPosition(), MonotonicMediaClock.nowTick())
         );
      }
   }

   public static void recordBlockContainer(ServerLevel level, BlockPos pos, int slot, UUID deviceId) {
      if (level != null && pos != null && deviceId != null) {
         LOCATIONS.put(
            PlaybackSourceId.of(deviceId), MP4DeviceLocationIndex.LocationRef.block(level.dimension(), pos.immutable(), slot, MonotonicMediaClock.nowTick())
         );
         ensureGraph(level, pos.immutable(), false);
      }
   }

   public static void recordContainerEntity(ServerLevel level, Entity entity, int slot, UUID deviceId) {
      if (level != null && entity != null && deviceId != null) {
         LOCATIONS.put(
            PlaybackSourceId.of(deviceId),
            MP4DeviceLocationIndex.LocationRef.containerEntity(level.dimension(), entity.getId(), entity.blockPosition(), slot, MonotonicMediaClock.nowTick())
         );
      }
   }

   public static Optional<MP4DeviceLocationIndex.ResolvedLocation> resolve(ServerLevel level, UUID deviceId) {
      if (level != null && deviceId != null) {
         MP4DeviceLocationIndex.LocationRef ref = LOCATIONS.get(PlaybackSourceId.of(deviceId));
         if (ref != null && ref.dimension() == level.dimension()) {
            long gameTime = MonotonicMediaClock.nowTick();
            Optional<MP4DeviceLocationIndex.ResolvedLocation> direct = verify(level, ref, deviceId, gameTime);
            if (direct.isPresent()) {
               return direct;
            } else {
               if (ref.type() == MP4DeviceLocationIndex.HolderType.PLAYER
                  || ref.type() == MP4DeviceLocationIndex.HolderType.BLOCK_CONTAINER
                  || ref.type() == MP4DeviceLocationIndex.HolderType.ITEM_ENTITY
                  || ref.type() == MP4DeviceLocationIndex.HolderType.CONTAINER_ENTITY) {
                  Optional<MP4DeviceLocationIndex.ResolvedLocation> relocated = relocateFromBlock(level, ref, deviceId, gameTime);
                  if (relocated.isPresent()) {
                     return relocated;
                  }
               }

               return Optional.empty();
            }
         } else {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   public static Optional<MP4DeviceLocationIndex.ResolvedLocation> relocateNear(ServerLevel level, UUID deviceId, BlockPos origin) {
      if (level != null && deviceId != null && origin != null) {
         long gameTime = MonotonicMediaClock.nowTick();
         MP4DeviceLocationIndex.LocationRef ref = MP4DeviceLocationIndex.LocationRef.block(level.dimension(), origin.immutable(), -1, gameTime);
         return relocateFromBlock(level, ref, deviceId, gameTime);
      } else {
         return Optional.empty();
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> verify(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      return switch (ref.type()) {
         case PLAYER -> verifyPlayer(level, ref, deviceId, gameTime);
         case ITEM_ENTITY -> verifyItemEntity(level, ref, deviceId, gameTime);
         case BLOCK_CONTAINER -> verifyBlockContainer(level, ref, deviceId, gameTime);
         case CONTAINER_ENTITY -> verifyContainerEntity(level, ref, deviceId, gameTime);
      };
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> verifyPlayer(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      ServerPlayer player = ref.holderId() != null ? level.getServer().getPlayerList().getPlayer(ref.holderId()) : null;
      if (player == null) {
         return Optional.empty();
      } else {
         ItemStack stack = MP4Item.findByDeviceId(player, deviceId);
         if (!isMp4(stack, deviceId)) {
            return Optional.empty();
         } else {
            recordPlayer(level, player, deviceId);
            return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(stack, 0, player.getId(), player.blockPosition(), -1, player.getUUID()));
         }
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> verifyItemEntity(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      if (level.getEntity(ref.entityId()) instanceof ItemEntity itemEntity && isMp4(itemEntity.getItem(), deviceId)) {
         recordItemEntity(level, itemEntity, deviceId);
         return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(itemEntity.getItem(), 1, itemEntity.getId(), itemEntity.blockPosition(), -1, deviceId));
      } else {
         return Optional.empty();
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> verifyBlockContainer(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      if (!level.isLoaded(ref.pos())) {
         return Optional.empty();
      } else if (level.getBlockEntity(ref.pos()) instanceof Container container) {
         Optional<MP4DeviceLocationIndex.SlotMatch> match = findInContainer(container, deviceId, ref.slotHint());
         if (match.isEmpty()) {
            return Optional.empty();
         } else {
            int slot = match.get().slot();
            recordBlockContainer(level, ref.pos(), slot, deviceId);
            return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(match.get().stack(), 2, -1, ref.pos(), slot, deviceId));
         }
      } else {
         return Optional.empty();
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> verifyContainerEntity(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      Entity entity = level.getEntity(ref.entityId());
      if (entity instanceof Container container) {
         Optional<MP4DeviceLocationIndex.SlotMatch> match = findInContainer(container, deviceId, ref.slotHint());
         if (match.isEmpty()) {
            return Optional.empty();
         } else {
            int slot = match.get().slot();
            recordContainerEntity(level, entity, slot, deviceId);
            return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(match.get().stack(), 3, entity.getId(), entity.blockPosition(), slot, deviceId));
         }
      } else {
         return Optional.empty();
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> relocateFromBlock(
      ServerLevel level, MP4DeviceLocationIndex.LocationRef ref, UUID deviceId, long gameTime
   ) {
      Optional<MP4DeviceLocationIndex.ResolvedLocation> graphHit = scanGraph(level, deviceId, ref.pos(), false);
      if (graphHit.isPresent()) {
         return graphHit;
      } else {
         Optional<MP4DeviceLocationIndex.ResolvedLocation> itemHit = scanItemEntities(level, deviceId, ref.pos());
         if (itemHit.isPresent()) {
            return itemHit;
         } else {
            Optional<MP4DeviceLocationIndex.ResolvedLocation> entityHit = scanContainerEntities(level, deviceId, ref.pos());
            if (entityHit.isPresent()) {
               return entityHit;
            } else {
               Optional<MP4DeviceLocationIndex.ResolvedLocation> rebuiltHit = scanGraph(level, deviceId, ref.pos(), true);
               return rebuiltHit.isPresent() ? rebuiltHit : fallbackScan(level, deviceId, ref.pos());
            }
         }
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> scanGraph(ServerLevel level, UUID deviceId, BlockPos origin, boolean forceRebuild) {
      MP4DeviceLocationIndex.LogisticsGraph graph = ensureGraph(level, origin, forceRebuild);
      if (graph == null) {
         return Optional.empty();
      } else {
         for (MP4DeviceLocationIndex.StorageNode node : graph.storageNodes()) {
            Optional<MP4DeviceLocationIndex.ResolvedLocation> found = scanBlockStorage(level, deviceId, node.pos());
            if (found.isPresent()) {
               return found;
            }
         }

         return Optional.empty();
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> scanBlockStorage(ServerLevel level, UUID deviceId, BlockPos pos) {
      if (!level.isLoaded(pos)) {
         return Optional.empty();
      } else {
         if (level.getBlockEntity(pos) instanceof Container container) {
            Optional<MP4DeviceLocationIndex.SlotMatch> match = findInContainer(container, deviceId, -1);
            if (match.isPresent()) {
               recordBlockContainer(level, pos, match.get().slot(), deviceId);
               return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(match.get().stack(), 2, -1, pos.immutable(), match.get().slot(), deviceId));
            }
         }

         IItemHandler handler = itemHandler(level, pos);
         if (handler == null) {
            return Optional.empty();
         } else {
            int max = Math.min(handler.getSlots(), 1024);

            for (int slot = 0; slot < max; slot++) {
               ItemStack stack = handler.getStackInSlot(slot);
               if (!stack.isEmpty() && isMp4(stack, deviceId)) {
                  recordBlockContainer(level, pos, slot, deviceId);
                  return Optional.of(new MP4DeviceLocationIndex.ResolvedLocation(stack, 2, -1, pos.immutable(), slot, deviceId));
               }
            }

            return Optional.empty();
         }
      }
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> scanItemEntities(ServerLevel level, UUID deviceId, BlockPos origin) {
      AABB area = new AABB(origin).inflate(4.0);

      for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, area)) {
         if (isMp4(itemEntity.getItem(), deviceId)) {
            recordItemEntity(level, itemEntity, deviceId);
            return Optional.of(
               new MP4DeviceLocationIndex.ResolvedLocation(itemEntity.getItem(), 1, itemEntity.getId(), itemEntity.blockPosition(), -1, deviceId)
            );
         }
      }

      return Optional.empty();
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> scanContainerEntities(ServerLevel level, UUID deviceId, BlockPos origin) {
      AABB area = new AABB(origin).inflate(6.0);

      for (Entity entity : level.getEntitiesOfClass(Entity.class, area, candidate -> candidate instanceof Container)) {
         if (entity instanceof Container container) {
            Optional<MP4DeviceLocationIndex.SlotMatch> match = findInContainer(container, deviceId, -1);
            if (match.isPresent()) {
               recordContainerEntity(level, entity, match.get().slot(), deviceId);
               return Optional.of(
                  new MP4DeviceLocationIndex.ResolvedLocation(match.get().stack(), 3, entity.getId(), entity.blockPosition(), match.get().slot(), deviceId)
               );
            }
         }
      }

      return Optional.empty();
   }

   private static Optional<MP4DeviceLocationIndex.ResolvedLocation> fallbackScan(ServerLevel level, UUID deviceId, BlockPos origin) {
      List<BlockPos> candidates = new ArrayList<>();
      BlockPos.betweenClosedStream(origin.offset(-3, -3, -3), origin.offset(3, 3, 3))
         .map(posx -> posx.immutable())
         .filter(level::isLoaded)
         .filter(posx -> level.getBlockEntity(posx) != null || itemHandler(level, posx) != null)
         .sorted(Comparator.comparingInt(posx -> posx.distManhattan(origin)))
         .limit(64L)
         .forEach(candidates::add);
      int slotsScanned = 0;

      for (BlockPos pos : candidates) {
         if (slotsScanned >= 1024) {
            break;
         }

         if (level.getBlockEntity(pos) instanceof Container container) {
            slotsScanned += container.getContainerSize();
         }

         Optional<MP4DeviceLocationIndex.ResolvedLocation> found = scanBlockStorage(level, deviceId, pos);
         if (found.isPresent()) {
            return found;
         }
      }

      return Optional.empty();
   }

   private static MP4DeviceLocationIndex.LogisticsGraph ensureGraph(ServerLevel level, BlockPos origin, boolean forceRebuild) {
      if (level != null && origin != null && level.isLoaded(origin)) {
         MP4DeviceLocationIndex.GraphKey key = new MP4DeviceLocationIndex.GraphKey(level.dimension(), origin.immutable());
         MP4DeviceLocationIndex.LogisticsGraph existing = GRAPHS.get(key);
         long gameTime = MonotonicMediaClock.nowTick();
         if (!forceRebuild && existing != null && gameTime - existing.createdGameTime() <= 1200L) {
            return existing;
         } else if (forceRebuild && existing != null && gameTime - existing.createdGameTime() < 100L) {
            return existing;
         } else {
            MP4DeviceLocationIndex.LogisticsGraph built = buildGraph(level, origin.immutable(), gameTime);
            GRAPHS.put(key, built);
            return built;
         }
      } else {
         return null;
      }
   }

   private static MP4DeviceLocationIndex.LogisticsGraph buildGraph(ServerLevel level, BlockPos origin, long gameTime) {
      List<MP4DeviceLocationIndex.StorageNode> storageNodes = new ArrayList<>();
      Set<BlockPos> visited = new HashSet<>();
      ArrayDeque<MP4DeviceLocationIndex.SearchNode> queue = new ArrayDeque<>();
      visited.add(origin);

      for (Direction direction : Direction.values()) {
         BlockPos next = origin.relative(direction).immutable();
         if (level.isLoaded(next)) {
            queue.add(new MP4DeviceLocationIndex.SearchNode(next, direction.getOpposite(), 1, 0));
            visited.add(next);
         }
      }

      int totalNodes = 0;
      int branchPoints = 0;
      Map<Integer, Integer> frontier = new HashMap<>();

      while (!queue.isEmpty() && totalNodes < 32 && storageNodes.size() < 16) {
         MP4DeviceLocationIndex.SearchNode node = queue.removeFirst();
         if (node.depth() <= 5) {
            int depthFrontier = frontier.compute(node.depth(), (ignored, value) -> value == null ? 1 : value + 1);
            if (depthFrontier <= 6) {
               MP4DeviceLocationIndex.NodeKind kind = classify(level, node.pos());
               if (kind != MP4DeviceLocationIndex.NodeKind.NONE) {
                  totalNodes++;
                  if (kind.canStore()) {
                     storageNodes.add(new MP4DeviceLocationIndex.StorageNode(node.pos(), node.depth(), score(kind, node.depth(), node.branchDepth())));
                  }

                  if (kind.canTransit()) {
                     List<MP4DeviceLocationIndex.SearchNode> nextNodes = new ArrayList<>();

                     for (Direction directionx : Direction.values()) {
                        if (directionx != node.cameFrom()) {
                           BlockPos next = node.pos().relative(directionx).immutable();
                           if (visited.add(next) && level.isLoaded(next)) {
                              MP4DeviceLocationIndex.NodeKind nextKind = classify(level, next);
                              if (nextKind != MP4DeviceLocationIndex.NodeKind.NONE) {
                                 nextNodes.add(
                                    new MP4DeviceLocationIndex.SearchNode(
                                       next, directionx.getOpposite(), node.depth() + 1, node.branchDepth() + (nextNodes.isEmpty() ? 0 : 1)
                                    )
                                 );
                              }
                           }
                        }
                     }

                     if (nextNodes.size() < 3) {
                        queue.addAll(nextNodes);
                     } else {
                        branchPoints++;

                        for (MP4DeviceLocationIndex.SearchNode next : nextNodes) {
                           MP4DeviceLocationIndex.NodeKind nextKind = classify(level, next.pos());
                           if (nextKind.canStore() && storageNodes.size() < 16) {
                              storageNodes.add(
                                 new MP4DeviceLocationIndex.StorageNode(next.pos(), next.depth(), score(nextKind, next.depth(), next.branchDepth()) - 30)
                              );
                           }
                        }

                        if (branchPoints > 3) {
                           break;
                        }
                     }
                  }
               }
            }
         }
      }

      storageNodes.sort(Comparator.<MP4DeviceLocationIndex.StorageNode>comparingInt(nodex -> nodex.score()).reversed());
      return new MP4DeviceLocationIndex.LogisticsGraph(gameTime, List.copyOf(storageNodes));
   }

   private static int score(MP4DeviceLocationIndex.NodeKind kind, int depth, int branchDepth) {
      return kind.baseScore() - depth * 8 - branchDepth * 10;
   }

   private static MP4DeviceLocationIndex.NodeKind classify(ServerLevel level, BlockPos pos) {
      if (!level.isLoaded(pos)) {
         return MP4DeviceLocationIndex.NodeKind.NONE;
      } else {
         BlockEntity blockEntity = level.getBlockEntity(pos);
         boolean hasItemHandler = itemHandler(level, pos) != null;
         if (blockEntity instanceof Container) {
            return !isKnownTransitBlock(level, pos) && !hasItemHandler
               ? MP4DeviceLocationIndex.NodeKind.STORAGE
               : MP4DeviceLocationIndex.NodeKind.STORAGE_AND_TRANSIT;
         } else if (hasItemHandler) {
            return MP4DeviceLocationIndex.NodeKind.STORAGE_AND_TRANSIT;
         } else {
            return isKnownTransitBlock(level, pos) ? MP4DeviceLocationIndex.NodeKind.TRANSIT : MP4DeviceLocationIndex.NodeKind.NONE;
         }
      }
   }

   private static boolean isKnownTransitBlock(ServerLevel level, BlockPos pos) {
      Block block = level.getBlockState(pos).getBlock();
      return block instanceof HopperBlock || block instanceof DropperBlock || block instanceof DispenserBlock;
   }

   private static IItemHandler itemHandler(ServerLevel level, BlockPos pos) {
      for (Direction direction : Direction.values()) {
         IItemHandler handler = (IItemHandler)level.getCapability(ItemHandler.BLOCK, pos, direction);
         if (handler != null) {
            return handler;
         }
      }

      return (IItemHandler)level.getCapability(ItemHandler.BLOCK, pos, null);
   }

   private static Optional<MP4DeviceLocationIndex.SlotMatch> findInContainer(Container container, UUID deviceId, int slotHint) {
      if (container != null && deviceId != null) {
         if (slotHint >= 0 && slotHint < container.getContainerSize()) {
            ItemStack hinted = container.getItem(slotHint);
            if (isMp4(hinted, deviceId)) {
               return Optional.of(new MP4DeviceLocationIndex.SlotMatch(slotHint, hinted));
            }
         }

         for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isMp4(stack, deviceId)) {
               return Optional.of(new MP4DeviceLocationIndex.SlotMatch(slot, stack));
            }
         }

         return Optional.empty();
      } else {
         return Optional.empty();
      }
   }

   private static boolean isMp4(ItemStack stack, UUID deviceId) {
      return !stack.isEmpty() && stack.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(stack));
   }

   private record GraphKey(ResourceKey<Level> dimension, BlockPos origin) {
   }

   private static enum HolderType {
      PLAYER,
      ITEM_ENTITY,
      BLOCK_CONTAINER,
      CONTAINER_ENTITY;
   }

   private record LocationRef(
      MP4DeviceLocationIndex.HolderType type, ResourceKey<Level> dimension, UUID holderId, int entityId, BlockPos pos, int slotHint, long lastSeenGameTime
   ) {
      static MP4DeviceLocationIndex.LocationRef player(ResourceKey<Level> dimension, UUID playerId, int entityId, BlockPos pos, long gameTime) {
         return new MP4DeviceLocationIndex.LocationRef(MP4DeviceLocationIndex.HolderType.PLAYER, dimension, playerId, entityId, pos.immutable(), -1, gameTime);
      }

      static MP4DeviceLocationIndex.LocationRef item(ResourceKey<Level> dimension, int entityId, BlockPos pos, long gameTime) {
         return new MP4DeviceLocationIndex.LocationRef(MP4DeviceLocationIndex.HolderType.ITEM_ENTITY, dimension, null, entityId, pos.immutable(), -1, gameTime);
      }

      static MP4DeviceLocationIndex.LocationRef block(ResourceKey<Level> dimension, BlockPos pos, int slot, long gameTime) {
         return new MP4DeviceLocationIndex.LocationRef(MP4DeviceLocationIndex.HolderType.BLOCK_CONTAINER, dimension, null, -1, pos.immutable(), slot, gameTime);
      }

      static MP4DeviceLocationIndex.LocationRef containerEntity(ResourceKey<Level> dimension, int entityId, BlockPos pos, int slot, long gameTime) {
         return new MP4DeviceLocationIndex.LocationRef(
            MP4DeviceLocationIndex.HolderType.CONTAINER_ENTITY, dimension, null, entityId, pos.immutable(), slot, gameTime
         );
      }
   }

   private record LogisticsGraph(long createdGameTime, List<MP4DeviceLocationIndex.StorageNode> storageNodes) {
   }

   private static enum NodeKind {
      NONE(false, false, 0),
      STORAGE(true, false, 70),
      TRANSIT(false, true, 80),
      STORAGE_AND_TRANSIT(true, true, 100);

      private final boolean canStore;
      private final boolean canTransit;
      private final int baseScore;

      private NodeKind(boolean canStore, boolean canTransit, int baseScore) {
         this.canStore = canStore;
         this.canTransit = canTransit;
         this.baseScore = baseScore;
      }

      boolean canStore() {
         return this.canStore;
      }

      boolean canTransit() {
         return this.canTransit;
      }

      int baseScore() {
         return this.baseScore;
      }
   }

   public record ResolvedLocation(ItemStack stack, int sourceType, int sourceEntityId, BlockPos sourcePos, int containerSlot, UUID ownerId) {
   }

   private record SearchNode(BlockPos pos, Direction cameFrom, int depth, int branchDepth) {
   }

   private record SlotMatch(int slot, ItemStack stack) {
   }

   private record StorageNode(BlockPos pos, int depth, int score) {
   }
}
