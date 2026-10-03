package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAreaAudioZoneRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaDemandScheduler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundLifecyclePolicy;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class ClientMediaMovingSound extends SyncedMediaSound implements ClientMediaSoundHandle {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final UUID sourceId;
   private final boolean headphoneRouted;
   private final long totalMillis;
   private final ClientMediaSoundLifecyclePolicy lifecyclePolicy;
   private final String debugName;
   private float mediaVolume;
   private final AtomicBoolean finished = new AtomicBoolean();
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private volatile boolean streamReady;
   private final SyncedStreamRecoveryRegistry.Registration recoveryRegistration;

   public ClientMediaMovingSound(
      UUID sourceId,
      URL songUrl,
      int timeSecond,
      LyricRecord lyricRecord,
      String sessionId,
      long startOffsetMillis,
      float volume,
      boolean headphoneRouted,
      ClientMediaSoundLifecyclePolicy lifecyclePolicy,
      String debugName
   ) {
      super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
      this.sourceId = sourceId;
      this.headphoneRouted = headphoneRouted;
      this.totalMillis = Math.max(0L, (long)timeSecond) * 1000L;
      this.lifecyclePolicy = (ClientMediaSoundLifecyclePolicy)(lifecyclePolicy != null ? lifecyclePolicy : MP4MediaSoundLifecyclePolicy.INSTANCE);
      this.debugName = debugName != null && !debugName.isBlank() ? debugName : "ClientMedia";
      this.mediaVolume = Math.max(0.0F, Math.min(1.0F, volume));
      this.updatePositionAndAttenuation();
      LOGGER.trace(
         "{} 声音实例创建: source={} session={} headphoneRouted={} volume={} gain={} attenuation={}",
         new Object[]{this.debugName, sourceId, this.sessionId(), headphoneRouted, this.mediaVolume, volume, this.attenuation}
      );
      this.recoveryRegistration = SyncedStreamRecoveryRegistry.register(
         this.playbackSessionId(), request -> this.lifecyclePolicy.recoverAfterStreamFailure(sourceId, this.sessionId(), request.error())
      );
      boolean accepted = false;

      try {
         accepted = this.lifecyclePolicy.tryRegisterSound(sourceId, this.sessionId(), this);
      } finally {
         if (!accepted) {
            this.discardWithoutFinishing();
         }
      }

      if (!accepted) {
         LOGGER.trace("{} 声音实例因会话已换代而拒绝: source={} session={}", new Object[]{this.debugName, sourceId, this.sessionId()});
      }
   }

   @Override
   public boolean headphoneRouted() {
      return this.headphoneRouted;
   }

   @Override
   public boolean stopped() {
      return this.isStopped();
   }

   @Override
   public void discardWithoutFinishing() {
      if (this.finished.compareAndSet(false, true)) {
         SyncedStreamRecoveryRegistry.unregister(this.recoveryRegistration);
      }

      this.stop();
   }

   @Override
   public void setMediaVolume(float volume) {
      this.mediaVolume = Math.max(0.0F, Math.min(1.0F, volume));
      this.updatePositionAndAttenuation();
   }

   public void tick() {
      if (ClientMediaRetryHandler.isPending(this.sourceId, this.playbackSessionId())) {
         this.stop();
      } else if (!ClientMediaPlayback.isCurrent(this.sourceId, this.sessionId())) {
         this.stopAndFinish();
      } else if (!ClientMediaAudioRouting.canHear(this.sourceId, this.headphoneRouted)) {
         this.stopAndFinish();
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            this.tick++;
            if (this.tick == 1) {
               LOGGER.trace(
                  "{} 声音实例首 tick: source={} session={} headphoneRouted={} volume={} attenuation={} pos=({}, {}, {})",
                  new Object[]{this.debugName, this.sourceId, this.sessionId(), this.headphoneRouted, this.volume, this.attenuation, this.x, this.y, this.z}
               );
            }

            long elapsedMillis = ClientMediaPlayback.elapsedMillis(this.sourceId, this.sessionId(), this.startOffsetMillis + this.tick * 50L);
            int lyricTick = (int)Math.min(2147483647L, Math.max(0L, Math.round(Math.max(0L, elapsedMillis) / 50.0)));
            if (this.totalMillis > 0L && lyricTick > this.tickTimes + 50) {
               this.lifecyclePolicy.onCompleted(this.sourceId, this.sessionId());
               this.stopAndFinish();
            } else {
               this.updatePositionAndAttenuation();
               if (this.lyricRecord != null) {
                  this.lyricRecord.updateCurrentLine(lyricTick);
                  ClientMediaPlaybackRegistry.updateLyric(this.sourceId, this.sessionId(), this.lyricRecord, lyricTick);
               }
            }
         } else {
            this.stopAndFinish();
         }
      }
   }

   @Override
   protected void onStreamFailure(Exception error) {
      boolean retryScheduled = this.lifecyclePolicy.recoverAfterStreamFailure(this.sourceId, this.sessionId(), error);
      if (!retryScheduled) {
         this.finishSession();
      }
   }

   @Override
   protected void onStreamStarting() {
      LOGGER.trace(
         "{} 声音流开始创建: source={} session={} headphoneRouted={} urlHost={}",
         new Object[]{this.debugName, this.sourceId, this.sessionId(), this.headphoneRouted, this.songUrl.getHost()}
      );
   }

   @Override
   protected void onStreamReady() {
      ClientMediaDemandScheduler.markPlaying(this.sourceId, this.sessionId());
      this.streamReady = true;
      ClientMediaPlayback.markAudioStarted(this.sourceId, this.sessionId(), this.startOffsetMillis, this.totalMillis);
      LOGGER.trace(
         "{} 声音流已就绪: source={} session={} headphoneRouted={} offset={}ms urlHost={}",
         new Object[]{this.debugName, this.sourceId, this.sessionId(), this.headphoneRouted, this.startOffsetMillis, this.songUrl.getHost()}
      );
   }

   @Override
   protected String streamDebugName() {
      return this.debugName;
   }

   private void updatePositionAndAttenuation() {
      Vec3 pos = ClientMediaAudioRouting.audiblePosition(this.sourceId, this.headphoneRouted);
      this.x = pos.x;
      this.y = pos.y;
      this.z = pos.z;
      boolean privateOrLocal = this.headphoneRouted || ClientMediaPlayback.isLocalPlayerSource(this.sourceId);
      this.attenuation = Attenuation.NONE;
      float perceivedGain = ClientMediaPlayback.perceivedGain(this.mediaVolume);
      Minecraft minecraft = Minecraft.getInstance();
      boolean gameAudioEnabled = minecraft.options != null
         && minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F
         && minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) > 0.0F;
      if (privateOrLocal) {
         boolean audible = gameAudioEnabled && this.mediaVolume > 0.0F && ClientMediaAudioRouting.canHear(this.sourceId, this.headphoneRouted);
         this.volume = perceivedGain * this.presentationEnvelope.gain(this.streamReady && audible, System.nanoTime());
         this.setDecodeDemand(audible);
      } else {
         double distance = minecraft.player != null ? minecraft.player.position().distanceTo(pos) : Double.MAX_VALUE;
         float spatialGain = AudioUtils.spatialGainForDistance((float)distance, this.mediaVolume, perceivedGain);
         boolean allowed = gameAudioEnabled && ClientMediaAudioRouting.canHear(this.sourceId, this.headphoneRouted);
         boolean spatialDemand = spatialGain > 0.0F;
         long nowNanos = System.nanoTime();
         float areaGain = ClientAreaAudioZoneRegistry.gain(this.sourceId, nowNanos);
         this.volume = spatialGain * areaGain * this.presentationEnvelope.gain(this.streamReady && allowed && spatialDemand, nowNanos);
         boolean predicted = false;
         if (!spatialDemand && allowed && minecraft.player != null) {
            Vec3 listener = minecraft.player.position();
            Vec3 velocity = minecraft.player.getDeltaMovement();
            AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(64.0F, this.mediaVolume, perceivedGain);
            predicted = PlaybackApproachPredictor.willEnterSphere(
               listener.x, listener.y, listener.z, velocity.x, velocity.y, velocity.z, pos.x, pos.y, pos.z, profile.fadeEndDistance()
            );
         }

         this.setDecodeDemand(allowed && (spatialDemand || predicted));
      }
   }

   @Override
   protected void refreshDecodeDemand() {
      this.updatePositionAndAttenuation();
   }

   @Override
   protected void finishSession() {
      if (this.finished.compareAndSet(false, true)) {
         SyncedStreamRecoveryRegistry.unregister(this.recoveryRegistration);
         this.lifecyclePolicy.finish(this.sourceId, this.sessionId());
      }
   }
}
