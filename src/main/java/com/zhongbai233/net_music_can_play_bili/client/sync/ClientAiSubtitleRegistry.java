package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;

public final class ClientAiSubtitleRegistry {
   private static final AiSubtitleSessionRegistry<BlockPos, BlockPos, LyricRecord> REGISTRY = new AiSubtitleSessionRegistry<>(
      (rawUrl, title) -> task(ClientMediaPreparer.buildAiSubtitleAsync(rawUrl, title))
   );

   private ClientAiSubtitleRegistry() {
   }

   public static void acquire(BlockPos consumerPos, BlockPos sourcePos, PlaybackSessionId sessionId, String rawUrl, String title) {
      if (consumerPos != null && sourcePos != null && sessionId != null && rawUrl != null && !rawUrl.isBlank()) {
         REGISTRY.acquire(consumerPos.immutable(), sourcePos.immutable(), sessionId, rawUrl, title);
      } else {
         release(consumerPos);
      }
   }

   public static ClientAiSubtitleRegistry.Snapshot snapshot(BlockPos sourcePos, PlaybackSessionId sessionId) {
      AiSubtitleSessionRegistry.Snapshot<LyricRecord> snapshot = REGISTRY.snapshot(sourcePos, sessionId);
      return new ClientAiSubtitleRegistry.Snapshot(
         ClientAiSubtitleRegistry.Status.valueOf(snapshot.status().name()), snapshot.result(), snapshot.failureReason()
      );
   }

   public static void release(BlockPos consumerPos) {
      if (consumerPos != null) {
         REGISTRY.release(consumerPos);
      }
   }

   public static void clear() {
      REGISTRY.clear();
   }

   public static void releaseChunk(int chunkX, int chunkZ) {
      REGISTRY.releaseMatching(pos -> Math.floorDiv(pos.getX(), 16) == chunkX && Math.floorDiv(pos.getZ(), 16) == chunkZ);
   }

   public static int activeSessions() {
      return REGISTRY.activeSessions();
   }

   public static int activeConsumers() {
      return REGISTRY.activeConsumers();
   }

   public static String describe() {
      AiSubtitleSessionRegistry.Diagnostics diagnostics = REGISTRY.diagnostics();
      return "aiSubtitles sessions="
         + diagnostics.sessions()
         + " consumers="
         + diagnostics.consumers()
         + " loading="
         + diagnostics.loading()
         + " ready="
         + diagnostics.ready()
         + " unavailable="
         + diagnostics.unavailable()
         + " failed="
         + diagnostics.failed();
   }

   private static AiSubtitleSessionRegistry.Task<LyricRecord> task(final CancellableTaskFuture<LyricRecord> future) {
      return new AiSubtitleSessionRegistry.Task<LyricRecord>() {
         @Override
         public CompletableFuture<LyricRecord> future() {
            return future;
         }

         @Override
         public void cancel() {
            future.cancel(true);
         }
      };
   }

   public record Snapshot(ClientAiSubtitleRegistry.Status status, LyricRecord lyricRecord, String failureReason) {
      public Snapshot(ClientAiSubtitleRegistry.Status status, LyricRecord lyricRecord, String failureReason) {
         failureReason = failureReason != null ? failureReason : "";
         this.status = status;
         this.lyricRecord = lyricRecord;
         this.failureReason = failureReason;
      }

      public boolean ready() {
         return this.status == ClientAiSubtitleRegistry.Status.READY && this.lyricRecord != null;
      }
   }

   public static enum Status {
      LOADING,
      READY,
      UNAVAILABLE,
      FAILED;
   }
}
