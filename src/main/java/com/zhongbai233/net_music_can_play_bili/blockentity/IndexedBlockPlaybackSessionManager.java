package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableStopPacket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber
public final class IndexedBlockPlaybackSessionManager {
   private static final int SYNC_INTERVAL_TICKS = 20;
   private static final int MAX_SESSION_ID_LENGTH = 128;
   private static final Map<PlaybackSourceId, IndexedBlockPlaybackSessionManager.Session> SESSIONS = new ConcurrentHashMap<>();

   private IndexedBlockPlaybackSessionManager() {
   }

   public static Set<UUID> publishAndSync(
      ServerLevel level,
      Level anchorLevel,
      PlaybackSourceId sourceId,
      BlockPos sourcePos,
      String playUrl,
      String rawUrl,
      String songName,
      String sessionId,
      long elapsedMillis,
      long durationMillis,
      int remainingSeconds
   ) {
      return publishAndSync(
         level, anchorLevel, sourceId, sourcePos, playUrl, rawUrl, songName, sessionId, elapsedMillis, durationMillis, remainingSeconds, false
      );
   }

   public static Set<UUID> publishAndSync(
      ServerLevel level,
      Level anchorLevel,
      PlaybackSourceId sourceId,
      BlockPos sourcePos,
      String playUrl,
      String rawUrl,
      String songName,
      String sessionId,
      long elapsedMillis,
      long durationMillis,
      int remainingSeconds,
      boolean repeat
   ) {
      if (level != null && sourceId != null && sourcePos != null && playUrl != null && !playUrl.isBlank() && !PlaybackSessionId.parse(sessionId).isEmpty()) {
         long nowNanos = MonotonicMediaClock.nowNanos();
         IndexedBlockPlaybackSessionManager.Session next = SESSIONS.compute(
            sourceId,
            (ignored, current) -> {
               if (current != null && !current.sessionId.equals(sessionId)) {
                  stopRecipients(level, current);
                  current = null;
               }

               if (current == null) {
                  current = new IndexedBlockPlaybackSessionManager.Session(
                     level.dimension(),
                     anchorLevel,
                     sourcePos.immutable(),
                     playUrl,
                     rawUrl,
                     songName,
                     sessionId,
                     elapsedMillis,
                     durationMillis,
                     remainingSeconds,
                     repeat,
                     nowNanos
                  );
               } else {
                  current.update(anchorLevel, sourcePos, playUrl, rawUrl, songName, elapsedMillis, durationMillis, remainingSeconds, repeat, nowNanos);
               }

               return (IndexedBlockPlaybackSessionManager.Session)current;
            }
         );
         sync(level, sourceId, next, nowNanos);
         return Set.copyOf(next.syncedPlayers);
      } else {
         return Set.of();
      }
   }

   public static void remove(ServerLevel level, PlaybackSourceId sourceId) {
      IndexedBlockPlaybackSessionManager.Session removed = sourceId != null ? SESSIONS.remove(sourceId) : null;
      if (removed != null) {
         if (level != null) {
            stopRecipients(level, removed);
         }

         ModernTurntableAudienceSync.forgetSource(sourceId);
      }
   }

   public static boolean contains(PlaybackSourceId sourceId) {
      return sourceId != null && SESSIONS.containsKey(sourceId);
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      if (event.getServer().getTickCount() % 20 == 0) {
         for (Entry<PlaybackSourceId, IndexedBlockPlaybackSessionManager.Session> indexed : SESSIONS.entrySet()) {
            IndexedBlockPlaybackSessionManager.Session session = indexed.getValue();
            ServerLevel level = event.getServer().getLevel(session.dimension);
            if (level == null) {
               if (SESSIONS.remove(indexed.getKey(), session)) {
                  ModernTurntableAudienceSync.forgetSource(indexed.getKey());
               }
            } else {
               long nowNanos = MonotonicMediaClock.nowNanos();
               long elapsed = session.elapsedAt(nowNanos);
               if (session.durationMillis <= 0L || elapsed < session.durationMillis) {
                  sync(level, indexed.getKey(), session, nowNanos);
               } else if (session.repeat) {
                  stopRecipients(level, session);
                  session.restartForIndexedRepeat(nowNanos);
                  sync(level, indexed.getKey(), session, nowNanos);
               } else if (SESSIONS.remove(indexed.getKey(), session)) {
                  stopRecipients(level, session);
                  ModernTurntableAudienceSync.forgetSource(indexed.getKey());
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         ModernTurntableAudienceSync.forgetPlayer(player.getUUID());
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      SESSIONS.clear();
      ModernTurntableAudienceSync.clearSubscriptions();
   }

   private static void sync(ServerLevel level, PlaybackSourceId sourceId, IndexedBlockPlaybackSessionManager.Session session, long nowNanos) {
      long elapsed = session.elapsedAt(nowNanos);
      int remaining = session.durationMillis > 0L ? (int)Math.max(1L, (session.durationMillis - elapsed + 999L) / 1000L) : session.remainingSeconds;
      Set<UUID> recipients = ModernTurntableAudienceSync.syncNearbyPlayers(
         level,
         session.anchorLevel,
         session.sourcePos,
         sourceId,
         session.syncedPlayers,
         session.playUrl,
         session.rawUrl,
         session.songName,
         session.sessionId,
         elapsed,
         session.durationMillis,
         remaining,
         96
      );
      session.syncedPlayers.clear();
      session.syncedPlayers.addAll(recipients);
      session.knownPlayers.addAll(recipients);
   }

   private static void stopRecipients(ServerLevel level, IndexedBlockPlaybackSessionManager.Session session) {
      PlaybackSessionId parsed = PlaybackSessionId.parse(session.sessionId).orElse(null);
      if (parsed != null) {
         ModernTurntableStopPacket packet = new ModernTurntableStopPacket(session.sourcePos, parsed.value());

         for (UUID playerId : session.knownPlayers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.level() == level) {
               PacketDistributor.sendToPlayer(player, packet, new CustomPacketPayload[0]);
            }
         }
      }
   }

   private static final class Session {
      private final ResourceKey<Level> dimension;
      private final String rootSessionId;
      private Level anchorLevel;
      private BlockPos sourcePos;
      private String playUrl;
      private String rawUrl;
      private String songName;
      private String sessionId;
      private long repeatGeneration;
      private boolean repeat;
      private long baseElapsedMillis;
      private long durationMillis;
      private int remainingSeconds;
      private MonotonicMediaClock.Anchor playbackClock = MonotonicMediaClock.paused(0L);
      private final Set<UUID> syncedPlayers = new HashSet<>();
      private final Set<UUID> knownPlayers = new HashSet<>();

      private Session(
         ResourceKey<Level> dimension,
         Level anchorLevel,
         BlockPos sourcePos,
         String playUrl,
         String rawUrl,
         String songName,
         String sessionId,
         long elapsedMillis,
         long durationMillis,
         int remainingSeconds,
         boolean repeat,
         long nowNanos
      ) {
         this.dimension = dimension;
         this.rootSessionId = sessionId;
         this.sessionId = sessionId;
         this.update(anchorLevel, sourcePos, playUrl, rawUrl, songName, elapsedMillis, durationMillis, remainingSeconds, repeat, nowNanos);
      }

      private void update(
         Level anchorLevel,
         BlockPos sourcePos,
         String playUrl,
         String rawUrl,
         String songName,
         long elapsedMillis,
         long durationMillis,
         int remainingSeconds,
         boolean repeat,
         long nowNanos
      ) {
         this.anchorLevel = anchorLevel;
         this.sourcePos = sourcePos.immutable();
         this.playUrl = playUrl;
         this.rawUrl = rawUrl != null ? rawUrl : "";
         this.songName = songName != null ? songName : "";
         this.repeat = repeat;
         this.baseElapsedMillis = Math.max(0L, elapsedMillis);
         this.durationMillis = Math.max(0L, durationMillis);
         this.remainingSeconds = Math.max(1, remainingSeconds);
         this.playbackClock = MonotonicMediaClock.running(this.baseElapsedMillis, nowNanos);
      }

      private void restartForIndexedRepeat(long nowNanos) {
         this.repeatGeneration++;
         String suffix = "~indexed-repeat-" + this.repeatGeneration;
         int rootLength = Math.min(this.rootSessionId.length(), 128 - suffix.length());
         this.sessionId = PlaybackSessionId.of(this.rootSessionId.substring(0, rootLength) + suffix).value();
         this.baseElapsedMillis = 0L;
         this.remainingSeconds = (int)Math.max(1L, (this.durationMillis + 999L) / 1000L);
         this.playbackClock = MonotonicMediaClock.running(0L, nowNanos);
         this.syncedPlayers.clear();
         this.knownPlayers.clear();
      }

      private long elapsedAt(long nowNanos) {
         return this.playbackClock.elapsedMillis(nowNanos, this.durationMillis);
      }
   }
}
