package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntablePlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackRuntimeProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class ModernTurntableSound extends SyncedMediaSound {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int BLOCK_STATE_GRACE_TICKS = 40;
   private static final int MINECART_MISSING_GRACE_TICKS = 200;
   private static final long STREAM_RETRY_DELAY_MILLIS = 750L;
   private static final PlaybackRuntimeProperties.Watchdog WATCHDOG = PlaybackRuntimeProperties.watchdog();
   private static final AtomicLong INSTANCES_CREATED = new AtomicLong();
   private final BlockPos pos;
   private final String rawUrl;
   private final String songName;
   private final PlaybackSourceId sourceId;
   private final long totalMillis;
   private boolean sessionFinished;
   private int minecartMissingTicks;
   private final SyncedStreamRecoveryRegistry.Registration recoveryRegistration;
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private volatile int streamReadyTick = -1;
   private volatile int lastAudioProgressTick = -1;
   private volatile long lastObservedAudioMillis = -1L;
   private volatile boolean watchdogRecoveryRequested;
   private boolean nowPlayingShown;

   public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord) {
      this(pos, songUrl, timeSecond, lyricRecord, "", 0L);
   }

   public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId) {
      this(pos, songUrl, timeSecond, lyricRecord, sessionId, 0L);
   }

   public ModernTurntableSound(BlockPos pos, URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId, long startOffsetMillis) {
      this(pos, songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis, "", "", Math.max(0, timeSecond) * 1000L);
   }

   public ModernTurntableSound(
      BlockPos pos,
      URL songUrl,
      int timeSecond,
      LyricRecord lyricRecord,
      String sessionId,
      long startOffsetMillis,
      String rawUrl,
      String songName,
      long totalMillis
   ) {
      this(
         pos,
         songUrl,
         timeSecond,
         lyricRecord,
         sessionId,
         startOffsetMillis,
         rawUrl,
         songName,
         totalMillis,
         PlaybackSync.parsePlaybackSourceId(songUrl.toString()).orElse(null)
      );
   }

   public ModernTurntableSound(
      BlockPos pos,
      URL songUrl,
      int timeSecond,
      LyricRecord lyricRecord,
      String sessionId,
      long startOffsetMillis,
      String rawUrl,
      String songName,
      long totalMillis,
      PlaybackSourceId sourceId
   ) {
      super(songUrl, timeSecond, lyricRecord, sessionId, startOffsetMillis);
      INSTANCES_CREATED.incrementAndGet();
      this.pos = pos;
      this.rawUrl = rawUrl != null ? rawUrl : "";
      this.songName = songName != null ? songName : "";
      this.sourceId = sourceId;
      this.totalMillis = Math.max(0L, totalMillis);
      this.x = pos.getX() + 0.5;
      this.y = pos.getY() + 0.5;
      this.z = pos.getZ() + 0.5;
      this.volume = 0.0F;
      this.recoveryRegistration = SyncedStreamRecoveryRegistry.register(this.playbackSessionId(), this::recoverStream);
      if (!ModernTurntablePlaybackTracker.onCancel(pos, this.sessionId(), () -> SyncedStreamRecoveryRegistry.unregister(this.recoveryRegistration))) {
         SyncedStreamRecoveryRegistry.unregister(this.recoveryRegistration);
      }

      ModernTurntablePlaybackTracker.registerSound(this, pos, this.sessionId());
      this.refreshDecodeDemand();
   }

   public static long instancesCreated() {
      return INSTANCES_CREATED.get();
   }

   public void tick() {
      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         this.stopAndFinish();
      } else {
         this.tick++;
         boolean movingSource = ClientMinecartAudioAnchors.isMoving(this.sessionId());
         Vec3 currentMovingPos = ClientMinecartAudioAnchors.currentPosition(this.sessionId());
         Vec3 movingPos = currentMovingPos != null ? currentMovingPos : ClientMinecartAudioAnchors.position(this.sessionId());
         if (movingPos != null) {
            this.x = movingPos.x;
            this.y = movingPos.y;
            this.z = movingPos.z;
         }

         if (movingSource) {
            this.minecartMissingTicks = currentMovingPos != null ? 0 : this.minecartMissingTicks + 1;
         }

         ModernTurntableBlockEntity turntable = level.getBlockEntity(this.pos) instanceof ModernTurntableBlockEntity modern ? modern : null;
         float configuredVolume = 4.0F * (turntable != null ? turntable.getVolume() : 1.0F);
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
         } else if (this.tick <= 40 || (movingSource ? this.minecartMissingTicks <= 200 : this.fixedSourceAvailable(turntable))) {
            if (turntable != null && !turntable.hasDisc() && this.tick > 0) {
               this.stopAndFinish();
            } else {
               if (this.lyricRecord != null) {
                  int lyricPos = this.effectiveLyricTick();
                  if (lyricPos >= 0) {
                     this.lyricRecord.updateCurrentLine(lyricPos);
                     if (turntable != null && turntable.isPlaying()) {
                        turntable.setClientLyricRecord(this.lyricRecord, this.sessionId(), lyricPos);
                     }
                  }
               }

               if (turntable != null && turntable.isPlaying()) {
                  ModernTurntablePlaybackDiagnostics.logEveryThreeSeconds(this.pos, this.sessionId());
               }

               this.showNowPlayingWhenAudible();
               this.checkAudioProgressWatchdog(turntable, movingSource);
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
         } else {
            this.stopAndFinish();
         }
      }
   }

   @Override
   protected void refreshDecodeDemand() {
      this.setDecodeDemand(ClientAudioOutputRegistry.hasPreparationDemand(this.pos, this.sourceId, this.sessionId()));
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
      SyncedStreamRecoveryRegistry.unregister(this.recoveryRegistration);
   }

   private void showNowPlayingWhenAudible() {
      if (!this.nowPlayingShown && this.streamReadyTick >= 0 && !this.songName.isBlank() && ClientAudioOutputRegistry.hasAudibleOutput(this.pos)) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.gui != null) {
            minecraft.gui.setNowPlaying(Component.literal(this.songName));
            this.nowPlayingShown = true;
         }
      }
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

   @Override
   protected String streamDebugName() {
      return "modern turntable";
   }

   private boolean recoverStream(SyncedStreamRecoveryRegistry.RecoveryRequest request) {
      if (!this.sessionFinished
         && !this.rawUrl.isBlank()
         && request.sessionId() != null
         && request.sessionId().equals(this.sessionId())
         && this.hasCurrentDemand()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.getConnection() != null) {
            ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(this.pos);
            boolean movingSource = ClientMinecartAudioAnchors.isMoving(this.sessionId());
            if ((movingSource || this.fixedSourceAvailable(turntable))
               && (!movingSource || ClientMinecartAudioAnchors.isMoving(this.sessionId()))
               && ModernTurntablePlaybackTracker.isCurrent(this.pos, this.sessionId())) {
               long delay = 750L * Math.max(1L, (long)request.attempt());
               ModernTurntablePlaybackTracker.markRecovering(this.pos, this.sessionId());
               LOGGER.warn(
                  "现代唱片机音频流断链，将刷新直链并自动续播: pos={} session={} attempt={} song='{}' reason={}",
                  new Object[]{this.pos, this.sessionId(), request.attempt(), this.songName, request.error() != null ? request.error().toString() : "unknown"}
               );
               CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                  Minecraft client = Minecraft.getInstance();
                  client.execute(() -> this.retryPlaybackOnClient(request.attempt()));
               });
               return true;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private void retryPlaybackOnClient(int attempt) {
      if (!this.sessionFinished && ModernTurntablePlaybackTracker.isCurrent(this.pos, this.sessionId()) && this.hasCurrentDemand()) {
         ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(this.pos);
         boolean movingSource = ClientMinecartAudioAnchors.isMoving(this.sessionId());
         if ((movingSource || this.fixedSourceAvailable(turntable)) && (!movingSource || ClientMinecartAudioAnchors.isMoving(this.sessionId()))) {
            long elapsedMillis = movingSource ? -1L : PlaybackClock.mediaMillis(this.pos);
            if (elapsedMillis < 0L) {
               elapsedMillis = this.startOffsetMillis + Math.max(0L, (long)this.tick) * 50L;
            }

            long durationMillis = this.totalMillis > 0L ? this.totalMillis : Math.max(0L, this.tickTimes * 50L);
            if (durationMillis <= 0L || elapsedMillis < durationMillis - 250L) {
               CompletableFuture<ClientMediaPreparer.PreparedMedia> prepare = ClientMediaPreparer.prepareAudioOnlyAsync(
                  this.rawUrl, this.songUrl.toString(), this.songName, true
               );
               if (!ModernTurntablePlaybackTracker.onCancel(this.pos, this.sessionId(), () -> prepare.cancel(false))) {
                  prepare.cancel(false);
               } else {
                  prepare.whenComplete((prepared, error) -> Minecraft.getInstance().execute(() -> this.finishRetryPrepare(attempt, prepared, error)));
               }
            }
         }
      }
   }

   private void finishRetryPrepare(int attempt, ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
      if (!this.sessionFinished && ModernTurntablePlaybackTracker.isActiveSession(this.pos, this.sessionId()) && this.hasCurrentDemand()) {
         ModernTurntableBlockEntity turntable = ModernTurntableTimeline.turntable(this.pos);
         boolean movingSource = ClientMinecartAudioAnchors.isMoving(this.sessionId());
         if ((movingSource || this.fixedSourceAvailable(turntable)) && (!movingSource || ClientMinecartAudioAnchors.isMoving(this.sessionId()))) {
            long elapsedMillis = movingSource ? -1L : PlaybackClock.mediaMillis(this.pos);
            if (elapsedMillis < 0L) {
               elapsedMillis = this.startOffsetMillis + Math.max(0L, (long)this.tick) * 50L;
            }

            long durationMillis = this.totalMillis > 0L ? this.totalMillis : Math.max(0L, this.tickTimes * 50L);
            if (durationMillis <= 0L || elapsedMillis < durationMillis - 250L) {
               if (error != null) {
                  LOGGER.warn("现代唱片机续播直链后台刷新失败，沿用旧直链: pos={} session={} reason={}", new Object[]{this.pos, this.sessionId(), error.toString()});
               }

               SyncedMediaPlaybackLauncher.LaunchResult launch = SyncedMediaPlaybackLauncher.fromPrepared(
                  this.rawUrl, this.songName, prepared, this.songUrl.toString(), this.sessionId(), Math.max(0L, elapsedMillis), durationMillis, this.pos, null
               );
               if (launch != null) {
                  if (launch.requestToken().isPresent()) {
                     MediaRequestToken requestToken = launch.requestToken().orElseThrow();
                     if (!ModernTurntablePlaybackTracker.onCancel(this.pos, this.sessionId(), () -> HttpAudioStreamHandler.cancelRequest(requestToken))) {
                        HttpAudioStreamHandler.cancelRequest(requestToken);
                        return;
                     }
                  }

                  LOGGER.warn(
                     "现代唱片机音频流自动续播: pos={} session={} attempt={} offset={}ms host={}",
                     new Object[]{this.pos, this.sessionId(), attempt, elapsedMillis, ClientMediaPreparer.hostOf(launch.playUrl())}
                  );
                  LyricRecord retryLyric = launch.lyricRecord() != null ? launch.lyricRecord() : this.lyricRecord;
                  long retryOffset = Math.max(0L, elapsedMillis);
                  SyncedMediaPlaybackLauncher.play(
                     new SyncedMediaPlaybackLauncher.LaunchResult(launch.playUrl(), retryLyric, launch.requestToken()),
                     this.songName,
                     (url, ignoredLyric) -> new ModernTurntableSound(
                        this.pos,
                        url,
                        Math.max(1, this.tickTimes / 20),
                        retryLyric,
                        this.sessionId(),
                        retryOffset,
                        this.rawUrl,
                        this.songName,
                        durationMillis,
                        this.sourceId
                     ),
                     false
                  );
                  this.retireForRecovery();
                  this.stop();
               }
            }
         }
      }
   }

   private void checkAudioProgressWatchdog(ModernTurntableBlockEntity turntable, boolean movingSource) {
      Minecraft minecraft = Minecraft.getInstance();
      if (this.watchdogRecoveryRequested
         || this.sessionFinished
         || this.streamReadyTick < 0
         || minecraft.isPaused()
         || minecraft.options == null
         || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
         || minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F) {
         this.lastAudioProgressTick = this.tick;
      } else if ((movingSource || this.fixedSourceAvailable(turntable)) && ModernTurntablePlaybackTracker.isCurrent(this.pos, this.sessionId())) {
         ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(this.pos);
         boolean matchingTimeline = timeline.audioSessionId().isBlank() || this.sessionId().equals(timeline.audioSessionId());
         long observed = matchingTimeline ? Math.max(timeline.audibleMillis(), timeline.fedMillis()) : -1L;
         if (observed > this.lastObservedAudioMillis) {
            this.lastObservedAudioMillis = observed;
            this.lastAudioProgressTick = this.tick;
         } else {
            long elapsedMillis = PlaybackClock.mediaMillis(this.pos);
            if (elapsedMillis < 0L) {
               elapsedMillis = this.startOffsetMillis + Math.max(0L, (long)this.tick) * 50L;
            }

            if (this.totalMillis <= 0L || elapsedMillis < this.totalMillis - WATCHDOG.audioEndGraceMillis()) {
               int stalledTicks = this.tick - Math.max(this.streamReadyTick, this.lastAudioProgressTick);
               int threshold = observed < 0L ? WATCHDOG.audioStartupStallTicks() : WATCHDOG.audioNoProgressTicks();
               if (stalledTicks >= threshold) {
                  this.watchdogRecoveryRequested = true;
                  IOException error = new IOException(
                     "audio timeline made no progress for "
                        + stalledTicks * 50L
                        + "ms (audible="
                        + timeline.audibleMillis()
                        + "ms, fed="
                        + timeline.fedMillis()
                        + "ms)"
                  );
                  LOGGER.warn(
                     "现代唱片机音频 watchdog 检测到无进展，准备重建: pos={} session={} observed={}ms stalled={}ms",
                     new Object[]{this.pos, this.sessionId(), observed, stalledTicks * 50L}
                  );
                  if (!SyncedStreamRecoveryRegistry.reportFailure(this.playbackSessionId(), this.songUrl, error)) {
                     LOGGER.warn("现代唱片机音频 watchdog 无法安排恢复，释放僵死会话等待服务器重新同步: pos={} session={}", this.pos, this.sessionId());
                     this.stopAndFinish();
                  }
               }
            }
         }
      }
   }

   private boolean fixedSourceAvailable(ModernTurntableBlockEntity turntable) {
      return turntable != null ? turntable.isPlaying() : this.sourceId != null && ClientAudioEndpointIndex.sourcePosition(this.sourceId) != null;
   }

   private boolean hasCurrentDemand() {
      return ClientAudioOutputRegistry.hasAudioDemand(this.pos, this.sourceId, this.sessionId());
   }

   private void retireForRecovery() {
      if (!this.sessionFinished) {
         this.sessionFinished = true;
         ModernTurntablePlaybackCoordinator.retireStreamForRecovery(this, this.recoveryRegistration);
      }
   }

   private int effectiveLyricTick() {
      int mediaTick = PlaybackClock.mediaTick(this.pos);
      return mediaTick >= 0 ? mediaTick : this.fallbackLyricTick();
   }

   @Override
   protected void finishSession() {
      if (!this.sessionFinished) {
         this.sessionFinished = true;
         ModernTurntablePlaybackCoordinator.finishSession(this.pos, this.sessionId());
      }
   }
}
