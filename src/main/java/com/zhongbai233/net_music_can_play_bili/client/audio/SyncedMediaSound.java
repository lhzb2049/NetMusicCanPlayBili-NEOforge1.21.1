package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.init.InitSounds;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

public abstract class SyncedMediaSound extends AbstractTickableSoundInstance {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Set<SyncedMediaSound> PENDING_DECODE_ADMISSION = ConcurrentHashMap.newKeySet();
   protected final URL songUrl;
   protected final int tickTimes;
   protected final LyricRecord lyricRecord;
   private final PlaybackSessionId playbackSessionId;
   protected final long startOffsetMillis;
   protected int tick;
   private volatile boolean streamCreationStarted;
   private volatile boolean demandIdleRetired;
   private final SyncedMediaSound.DeferredAudioStreamAdmission decodeAdmission = new SyncedMediaSound.DeferredAudioStreamAdmission();
   private final Set<SyncedMediaSound.OwnedAudioStream> ownedStreams = ConcurrentHashMap.newKeySet();

   protected SyncedMediaSound(URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId, long startOffsetMillis) {
      super((SoundEvent)InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
      this.songUrl = songUrl;
      this.tickTimes = Math.max(1, timeSecond) * 20;
      this.lyricRecord = lyricRecord;
      this.playbackSessionId = PlaybackSessionId.parse(sessionId)
         .orElseThrow(() -> new IllegalArgumentException("synced media sound requires a valid session id"));
      this.startOffsetMillis = Math.max(0L, startOffsetMillis);
   }

   protected final PlaybackSessionId playbackSessionId() {
      return this.playbackSessionId;
   }

   public final Optional<PlaybackSessionId> playbackSession() {
      return Optional.of(this.playbackSessionId);
   }

   public final String sessionId() {
      return this.playbackSessionId.value();
   }

   public final boolean canStartSilent() {
      return true;
   }

   public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
      if (this.isStopped()) {
         this.retireBeforeStreamReady();
      } else if (!this.decodeAdmission.isDecided()) {
         PENDING_DECODE_ADMISSION.add(this);
         if (this.decodeAdmission.isDecided()) {
            PENDING_DECODE_ADMISSION.remove(this);
         }
      }

      return this.decodeAdmission
         .future()
         .thenCompose(
            decision -> decision == SyncedMediaSound.DeferredAudioStreamAdmission.Decision.ATTACH_DRAINED_STREAM
               ? CompletableFuture.completedFuture(new SyncedMediaSound.DrainedAudioStream())
               : CompletableFuture.supplyAsync(this::openMediaOrTerminalStream, Util.backgroundExecutor())
         );
   }

   private AudioStream openMediaOrTerminalStream() {
      SyncedMediaSound.OwnedAudioStream openedStream = null;

      try {
         if (this.isStopped()) {
            this.retireBeforeStreamReady();
            return new SyncedMediaSound.DrainedAudioStream();
         } else {
            this.streamCreationStarted = true;
            this.onStreamStarting();
            if (this.isStopped()) {
               this.retireBeforeStreamReady();
               return new SyncedMediaSound.DrainedAudioStream();
            } else {
               long started = System.currentTimeMillis();
               SyncedMediaSound.OwnedAudioStream stream = openedStream = this.own(new NetMusicAudioStream(this.songUrl));
               if (this.isStopped()) {
                  this.closeWithoutFailure(stream);
                  this.retireBeforeStreamReady();
                  return new SyncedMediaSound.DrainedAudioStream();
               } else {
                  LOGGER.debug(
                     "{} audio stream ready: cost={}ms host={}",
                     new Object[]{this.streamDebugName(), System.currentTimeMillis() - started, this.songUrl.getHost()}
                  );
                  this.onStreamReady();
                  return stream;
               }
            }
         }
      } catch (CancellationException var6) {
         this.closeWithoutFailure(openedStream);
         this.retireBeforeStreamReady();
         return new SyncedMediaSound.DrainedAudioStream();
      } catch (Exception var7) {
         Exception e = var7;
         this.closeWithoutFailure(openedStream);
         BiliPlaybackDiagnostics.markFailed(this.songUrl, var7);
         this.onStreamFailure(var7);
         NetMusic.LOGGER.error("Failed to create {} audio stream for URL: {}", this.streamDebugName(), this.songUrl, var7);

         try {
            return this.errorCueStream(e);
         } catch (CompletionException var5) {
            throw var5;
         }
      }
   }

   protected final void setDecodeDemand(boolean decodeDemand) {
      if (decodeDemand) {
         this.decodeAdmission.approveMediaStream();
         PENDING_DECODE_ADMISSION.remove(this);
      }
   }

   protected final boolean streamCreationStarted() {
      return this.streamCreationStarted;
   }

   public static void tickPendingDecodeAdmissions() {
      for (SyncedMediaSound sound : PENDING_DECODE_ADMISSION) {
         if (sound.isStopped()) {
            sound.retireBeforeStreamReady();
         } else {
            sound.refreshDecodeDemand();
            if (sound.decodeAdmission.isDecided()) {
               PENDING_DECODE_ADMISSION.remove(sound);
            }
         }
      }
   }

   public static void cancelPendingDecodeAdmissions() {
      for (SyncedMediaSound sound : PENDING_DECODE_ADMISSION) {
         sound.retireBeforeStreamReady();
      }
   }

   protected abstract void refreshDecodeDemand();

   private AudioStream errorCueStream(Exception cause) {
      try {
         InputStream errorSound = Minecraft.getInstance().getResourceManager().open(NetMusicSound.ERROR_SOUND);
         return this.own(new JOrbisAudioStream(errorSound));
      } catch (Exception var4) {
         CompletionException failure = new CompletionException(cause);
         failure.addSuppressed(var4);
         throw failure;
      }
   }

   protected int fallbackLyricTick() {
      return (int)Math.min(2147483647L, Math.max(0L, Math.round(Math.max(0L, this.startOffsetMillis) / 50.0)));
   }

   protected void stopAndFinish() {
      this.finishSession();
      this.stop();
      this.drainAllocatedChannel();
      this.closeOwnedStreams();
   }

   void stopFromTracker() {
      this.stopAndFinish();
   }

   void stopForDemandIdle() {
      this.demandIdleRetired = true;
      PENDING_DECODE_ADMISSION.remove(this);
      this.onDemandIdle();
      this.stop();
      this.drainAllocatedChannel();
      this.closeOwnedStreams();
   }

   protected void onDemandIdle() {
   }

   protected void onStreamStarting() {
   }

   protected void onStreamReady() {
   }

   protected void onStreamFailure(Exception error) {
      this.finishSession();
   }

   protected abstract void finishSession();

   protected abstract String streamDebugName();

   private void retireBeforeStreamReady() {
      PENDING_DECODE_ADMISSION.remove(this);
      if (!this.demandIdleRetired) {
         this.finishSession();
      }

      this.drainAllocatedChannel();
      this.closeOwnedStreams();
   }

   private void drainAllocatedChannel() {
      PENDING_DECODE_ADMISSION.remove(this);
      this.decodeAdmission.drainAllocatedChannel();
   }

   private SyncedMediaSound.OwnedAudioStream own(AudioStream stream) {
      SyncedMediaSound.OwnedAudioStream owned = new SyncedMediaSound.OwnedAudioStream(stream);
      this.ownedStreams.add(owned);
      return owned;
   }

   private void closeOwnedStreams() {
      for (SyncedMediaSound.OwnedAudioStream stream : this.ownedStreams) {
         this.closeWithoutFailure(stream);
      }
   }

   private void closeWithoutFailure(SyncedMediaSound.OwnedAudioStream stream) {
      if (stream != null) {
         try {
            stream.close();
         } catch (IOException var3) {
            LOGGER.debug("Failed to close cancelled {} audio stream", this.streamDebugName(), var3);
         }
      }
   }

   static final class DeferredAudioStreamAdmission {
      private final CompletableFuture<SyncedMediaSound.DeferredAudioStreamAdmission.Decision> decision = new CompletableFuture<>();

      CompletableFuture<SyncedMediaSound.DeferredAudioStreamAdmission.Decision> future() {
         return this.decision;
      }

      boolean approveMediaStream() {
         return this.decision.complete(SyncedMediaSound.DeferredAudioStreamAdmission.Decision.OPEN_MEDIA_STREAM);
      }

      boolean drainAllocatedChannel() {
         return this.decision.complete(SyncedMediaSound.DeferredAudioStreamAdmission.Decision.ATTACH_DRAINED_STREAM);
      }

      boolean isDecided() {
         return this.decision.isDone();
      }

      static enum Decision {
         OPEN_MEDIA_STREAM,
         ATTACH_DRAINED_STREAM;
      }
   }

   private static final class DrainedAudioStream implements AudioStream {
      private static final AudioFormat FORMAT = new AudioFormat(Encoding.PCM_SIGNED, 44100.0F, 16, 1, 2, 44100.0F, false);
      private static final int SILENT_BYTES = 882;
      private final AtomicBoolean delivered = new AtomicBoolean();
      private final AtomicBoolean closed = new AtomicBoolean();

      public AudioFormat getFormat() {
         return FORMAT;
      }

      public ByteBuffer read(int size) {
         if (!this.closed.get() && size >= FORMAT.getFrameSize() && this.delivered.compareAndSet(false, true)) {
            int byteCount = Math.min(size, 882);
            byteCount -= byteCount % FORMAT.getFrameSize();
            return ByteBuffer.allocateDirect(byteCount);
         } else {
            return null;
         }
      }

      public void close() {
         this.closed.set(true);
      }
   }

   private static final class OwnedAudioStream implements AudioStream {
      private final AudioStream delegate;
      private final AudioFormat format;
      private final AtomicBoolean closed = new AtomicBoolean();

      private OwnedAudioStream(AudioStream delegate) {
         this.delegate = delegate;
         this.format = delegate.getFormat();
      }

      public AudioFormat getFormat() {
         return this.format;
      }

      public ByteBuffer read(int size) throws IOException {
         return this.closed.get() ? null : this.delegate.read(size);
      }

      public void close() throws IOException {
         if (this.closed.compareAndSet(false, true)) {
            this.delegate.close();
         }
      }
   }
}
