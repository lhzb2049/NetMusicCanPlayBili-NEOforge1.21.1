package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackRuntimeProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.URL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

public class LiveStreamerSound extends SyncedMediaSound {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int BLOCK_STATE_GRACE_TICKS = 40;
   private static final int LIVE_STALL_TICKS = PlaybackRuntimeProperties.watchdog().liveStallTicks();
   private final BlockPos pos;
   private final PlaybackSourceId sourceId;
   private volatile int streamReadyTick = -1;
   private int lastAudioProgressTick = -1;
   private long lastObservedAudioMillis = -1L;
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private boolean sessionFinished;

   public LiveStreamerSound(BlockPos pos, URL streamUrl, int timeSecond, String sessionId) {
      this(pos, streamUrl, timeSecond, sessionId, PlaybackSync.parsePlaybackSourceId(streamUrl.toString()).orElse(null));
   }

   public LiveStreamerSound(BlockPos pos, URL streamUrl, int timeSecond, String sessionId, PlaybackSourceId sourceId) {
      super(streamUrl, timeSecond, null, sessionId, 0L);
      this.pos = pos;
      this.sourceId = sourceId;
      this.x = pos.getX() + 0.5;
      this.y = pos.getY() + 0.5;
      this.z = pos.getZ() + 0.5;
      this.volume = 0.0F;
      ModernTurntablePlaybackTracker.registerSound(this, pos, this.sessionId());
      this.refreshDecodeDemand();
   }

   public void tick() {
      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         this.stopAndFinish();
      } else {
         this.tick++;
         LiveStreamerBlockEntity streamer = level.getBlockEntity(this.pos) instanceof LiveStreamerBlockEntity live ? live : null;
         float configuredVolume = 4.0F * (streamer != null ? streamer.getVolume() : 1.0F);
         boolean presentationDemand = ClientAudioOutputRegistry.hasGeometricAudioDemand(this.pos, this.sourceId, this.sessionId());
         long nowNanos = System.nanoTime();
         this.volume = configuredVolume
            * ClientAudioOutputRegistry.areaGainForSource(this.pos, nowNanos)
            * this.presentationEnvelope.gain(this.streamReadyTick >= 0 && presentationDemand, nowNanos);
         this.refreshDecodeDemand();
         if (!ModernTurntablePlaybackTracker.isCurrent(this.pos, this.sessionId())) {
            this.stopAndFinish();
         } else if (this.tick > this.tickTimes + 50) {
            this.stopAndFinish();
         } else if (this.tick > 40 && !this.fixedSourceAvailable(streamer)) {
            this.stopAndFinish();
         } else {
            this.checkLiveStallWatchdog();
            if (this.tick % 20 == 0) {
               LiveStreamerVideoClient.sync(this.pos, this.sessionId());
            }

            if (MonotonicMediaClock.nowTick() % 8L == 0L) {
               RandomSource random = level.getRandom();

               for (int i = 0; i < 2; i++) {
                  level.addParticle(
                     ParticleTypes.NOTE,
                     this.x - 0.5 + random.nextDouble(),
                     this.y + 1.0 + random.nextDouble() * 0.35,
                     this.z - 0.5 + random.nextDouble(),
                     random.nextGaussian(),
                     random.nextGaussian(),
                     random.nextInt(3)
                  );
               }
            }
         }
      }
   }

   @Override
   protected void refreshDecodeDemand() {
      this.setDecodeDemand(ClientAudioOutputRegistry.hasPreparationDemand(this.pos, this.sourceId, this.sessionId()));
   }

   private void checkLiveStallWatchdog() {
      Minecraft minecraft = Minecraft.getInstance();
      if (this.streamReadyTick >= 0
         && !minecraft.isPaused()
         && minecraft.options != null
         && !(minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F)
         && !(minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F)) {
         ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(this.pos);
         boolean matchingTimeline = timeline.audioSessionId().isBlank() || this.sessionId().equals(timeline.audioSessionId());
         long observed = matchingTimeline ? Math.max(timeline.audibleMillis(), timeline.fedMillis()) : -1L;
         if (observed < 0L) {
            this.lastAudioProgressTick = this.tick;
         } else if (observed > this.lastObservedAudioMillis) {
            this.lastObservedAudioMillis = observed;
            this.lastAudioProgressTick = this.tick;
         } else {
            int stalledTicks = this.tick - Math.max(this.streamReadyTick, this.lastAudioProgressTick);
            if (stalledTicks >= LIVE_STALL_TICKS) {
               LOGGER.warn("直播音频长时间无进展，结束当前会话等待服务端重新同步: pos={} session={} stalled={}ms", new Object[]{this.pos, this.sessionId(), stalledTicks * 50L});
               this.stopAndFinish();
            }
         }
      } else {
         this.lastAudioProgressTick = this.tick;
      }
   }

   @Override
   protected void onStreamReady() {
      ModernTurntablePlaybackCoordinator.markIndexedStreamPlaying(this.pos, this.sourceId, this.sessionId());
      ModernTurntablePlaybackTracker.markStreamStarted(this.pos, this.sessionId());
      this.streamReadyTick = this.tick;
      this.lastAudioProgressTick = this.tick;
   }

   @Override
   protected void onDemandIdle() {
      ModernTurntablePlaybackTracker.unregisterSound(this);
   }

   @Override
   protected void onStreamFailure(Exception error) {
      Minecraft minecraft = Minecraft.getInstance();
      minecraft.execute(() -> {
         ModernTurntablePlaybackTracker.fail(this.pos, this.sessionId());
         this.finishSession();
         this.stop();
      });
   }

   private boolean fixedSourceAvailable(LiveStreamerBlockEntity streamer) {
      return streamer != null ? streamer.isPlaying() : this.sourceId != null && ClientAudioEndpointIndex.sourcePosition(this.sourceId) != null;
   }

   @Override
   protected void finishSession() {
      if (!this.sessionFinished) {
         this.sessionFinished = true;
         PlaybackSessionId.parse(this.sessionId())
            .ifPresent(
               session -> LiveRoomMetadataRegistry.remove(new LiveRoomMetadataRegistry.SourceKey(this.pos.getX(), this.pos.getY(), this.pos.getZ()), session)
            );
         ModernTurntablePlaybackTracker.unregisterSound(this);
         LiveStreamerVideoClient.forget(this.sessionId());
         ModernTurntablePlaybackCoordinator.finishSession(this.pos, this.sessionId());
      }
   }

   @Override
   protected String streamDebugName() {
      return "live streamer";
   }
}
