package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoZombieCloseSupervisor {
   private static final VideoZombieCloseSupervisor GLOBAL = new VideoZombieCloseSupervisor();
   private final ConcurrentMap<VideoZombieCloseSupervisor.Key, CompletableFuture<Void>> zombies = new ConcurrentHashMap<>();
   private final AtomicLong lateConvergences = new AtomicLong();

   public static VideoZombieCloseSupervisor global() {
      return GLOBAL;
   }

   public void track(
      String sessionId, long generation, CompletableFuture<Void> closeCompletion, CompletableFuture<Void> nativeTermination, CompletableFuture<Void> decodeExit
   ) {
      VideoZombieCloseSupervisor.Key key = new VideoZombieCloseSupervisor.Key(PlaybackSessionId.parse(sessionId), generation);
      CompletableFuture<Void> convergence = CompletableFuture.allOf(nonNull(closeCompletion), nonNull(nativeTermination), nonNull(decodeExit));
      if (completedNormally(convergence)) {
         this.lateConvergences.incrementAndGet();
      } else if (this.zombies.putIfAbsent(key, convergence) == null) {
         convergence.whenComplete((ignored, error) -> {
            if (error == null && this.zombies.remove(key, convergence)) {
               this.lateConvergences.incrementAndGet();
            }
         });
      }
   }

   VideoZombieCloseSupervisor.Snapshot snapshot() {
      return new VideoZombieCloseSupervisor.Snapshot(this.zombies.size(), this.lateConvergences.get());
   }

   void clearForTest() {
      this.zombies.clear();
      this.lateConvergences.set(0L);
   }

   private static CompletableFuture<Void> nonNull(CompletableFuture<Void> future) {
      return future != null ? future : CompletableFuture.completedFuture(null);
   }

   private static boolean completedNormally(CompletableFuture<Void> future) {
      if (future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) {
         try {
            future.join();
            return true;
         } catch (RuntimeException var2) {
            return false;
         }
      } else {
         return false;
      }
   }

   private record Key(Optional<PlaybackSessionId> playbackSessionId, long generation) {
   }

   record Snapshot(int activeZombies, long lateConvergences) {
   }
}
