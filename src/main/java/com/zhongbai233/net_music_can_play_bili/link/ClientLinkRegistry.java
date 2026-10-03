package com.zhongbai233.net_music_can_play_bili.link;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.core.BlockPos;

public final class ClientLinkRegistry {
   private static final Map<BlockPos, Set<BlockPos>> LINKS = new ConcurrentHashMap<>();
   private static final Map<BlockPos, Set<BlockPos>> SUBTITLE_PROJECTION_LINKS = new ConcurrentHashMap<>();

   private ClientLinkRegistry() {
   }

   public static void link(BlockPos sourcePos, BlockPos targetPos) {
      if (sourcePos != null && targetPos != null) {
         unlink(sourcePos);
         LINKS.computeIfAbsent(targetPos.immutable(), k -> new CopyOnWriteArraySet<>()).add(sourcePos.immutable());
      }
   }

   public static void linkSubtitleProjector(BlockPos sourcePos, BlockPos targetPos) {
      link(sourcePos, targetPos);
      SUBTITLE_PROJECTION_LINKS.computeIfAbsent(targetPos.immutable(), k -> new CopyOnWriteArraySet<>()).add(sourcePos.immutable());
   }

   public static void unlink(BlockPos sourcePos) {
      if (sourcePos != null) {
         removeSource(LINKS, sourcePos);
         removeSource(SUBTITLE_PROJECTION_LINKS, sourcePos);
      }
   }

   public static void unlink(BlockPos sourcePos, BlockPos targetPos) {
      Set<BlockPos> sources = LINKS.get(targetPos);
      if (sources != null) {
         sources.remove(sourcePos);
         if (sources.isEmpty()) {
            LINKS.remove(targetPos);
         }
      }

      Set<BlockPos> projectionSources = SUBTITLE_PROJECTION_LINKS.get(targetPos);
      if (projectionSources != null) {
         projectionSources.remove(sourcePos);
         if (projectionSources.isEmpty()) {
            SUBTITLE_PROJECTION_LINKS.remove(targetPos);
         }
      }
   }

   public static boolean isTargetLinked(BlockPos targetPos) {
      Set<BlockPos> sources = LINKS.get(targetPos);
      return sources != null && !sources.isEmpty();
   }

   public static boolean isSubtitleProjectionTarget(BlockPos targetPos) {
      Set<BlockPos> sources = SUBTITLE_PROJECTION_LINKS.get(targetPos);
      return sources != null && !sources.isEmpty();
   }

   public static Set<BlockPos> getSources(BlockPos targetPos) {
      return LINKS.getOrDefault(targetPos, Set.of());
   }

   public static void clear() {
      LINKS.clear();
      SUBTITLE_PROJECTION_LINKS.clear();
   }

   private static void removeSource(Map<BlockPos, Set<BlockPos>> links, BlockPos sourcePos) {
      links.values().forEach(set -> set.remove(sourcePos));
      links.entrySet().removeIf(e -> e.getValue().isEmpty());
   }
}
