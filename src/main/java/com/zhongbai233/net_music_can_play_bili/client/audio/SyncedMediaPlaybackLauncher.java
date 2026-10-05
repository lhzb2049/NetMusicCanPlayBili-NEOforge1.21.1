package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection;
import com.zhongbai233.net_music_can_play_bili.client.media.MediaLogThrottle;
import com.zhongbai233.net_music_can_play_bili.client.media.MediaSourceClassifier;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

public final class SyncedMediaPlaybackLauncher {
   private static final Logger LOGGER = LogUtils.getLogger();

   private SyncedMediaPlaybackLauncher() {
   }

   public static SyncedMediaPlaybackLauncher.LaunchResult prepare(
      String rawUrl,
      String playUrl,
      String songName,
      boolean allowDolby,
      boolean enableLyrics,
      String sessionId,
      long elapsedMillis,
      long totalMillis,
      BlockPos pos,
      UUID ownerId
   ) {
      if (!ClientMemoryProtection.allowMediaStart()) {
         return null;
      } else {
         ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(rawUrl, playUrl, songName, allowDolby);
         return fromPrepared(rawUrl, songName, prepared, playUrl, sessionId, elapsedMillis, totalMillis, pos, ownerId);
      }
   }

   public static SyncedMediaPlaybackLauncher.LaunchResult fromPrepared(
      String rawUrl,
      String songName,
      ClientMediaPreparer.PreparedMedia prepared,
      String fallbackPlayUrl,
      String sessionId,
      long elapsedMillis,
      long totalMillis,
      BlockPos pos,
      UUID ownerId
   ) {
      return fromPrepared(rawUrl, songName, prepared, fallbackPlayUrl, sessionId, elapsedMillis, totalMillis, pos, ownerId, null);
   }

   public static SyncedMediaPlaybackLauncher.LaunchResult fromPrepared(
      String rawUrl,
      String songName,
      ClientMediaPreparer.PreparedMedia prepared,
      String fallbackPlayUrl,
      String sessionId,
      long elapsedMillis,
      long totalMillis,
      BlockPos pos,
      UUID ownerId,
      PlaybackSync.MinecartAnchor minecartAnchor
   ) {
      if (!ClientMemoryProtection.allowMediaStart()) {
         return null;
      } else {
         String playUrl = prepared != null ? prepared.playUrl() : fallbackPlayUrl;
         if (!PlayableMediaUrl.isHttp(playUrl)) {
            MediaSourceClassifier.Classification source = MediaSourceClassifier.classifyThrottled(
               playUrl, Minecraft.getInstance().gameDirectory.getAbsolutePath()
            );
            if (MediaLogThrottle.shouldLog("audio-non-http|" + playUrl, source.summary())) {
               LOGGER.warn(
                  "拒绝注册非 HTTP(S) 同步媒体地址: song='{}' 源分类={} allowed={} 原因={} value='{}'",
                  new Object[]{songName, source.kind(), source.allowed(), source.reason(), playUrl}
               );
            }

            return null;
         } else {
            LyricRecord lyricRecord = prepared != null ? prepared.lyricRecord() : null;
            PlaybackRequest playbackRequest = PlaybackRequest.now(
               playUrl,
               pos,
               sessionId,
               Math.max(0L, elapsedMillis),
               Math.max(0L, totalMillis),
               ownerId,
               minecartAnchor != null ? minecartAnchor.entityUuid() : null
            );
            HttpAudioStreamHandler.RegisteredRequest request = HttpAudioStreamHandler.registerRequest(playbackRequest);
            BiliPlaybackDiagnostics.beginPlayback(songName, rawUrl, request.url());
            return new SyncedMediaPlaybackLauncher.LaunchResult(request.url(), lyricRecord, request.requestToken());
         }
      }
   }

   public static boolean play(SyncedMediaPlaybackLauncher.LaunchResult launch, String songName, BiFunction<URL, LyricRecord, SoundInstance> soundFactory) {
      return play(launch, songName, soundFactory, true);
   }

   public static boolean play(
      SyncedMediaPlaybackLauncher.LaunchResult launch, String songName, BiFunction<URL, LyricRecord, SoundInstance> soundFactory, boolean announceImmediately
   ) {
      if (!ClientMemoryProtection.allowMediaStart() || launch == null || launch.playUrl() == null || launch.playUrl().isBlank()) {
         return false;
      } else if (!PlayableMediaUrl.isHttp(launch.playUrl())) {
         LOGGER.warn("拒绝播放非 HTTP(S) 同步媒体地址: song='{}' value='{}'", songName, launch.playUrl());
         return false;
      } else {
         LyricRecord lyricRecord = launch.lyricRecord();
         if (announceImmediately) {
            try {
               MusicPlayManager.play(launch.playUrl(), songName, url -> soundFactory.apply(url, lyricRecord));
               return true;
            } catch (RuntimeException var9) {
               LOGGER.warn("同步媒体立即提交失败: song='{}' value='{}' reason={}", new Object[]{songName, launch.playUrl(), var9.toString()});
               return false;
            }
         } else {
            Optional<String> finalUrl;
            try {
               finalUrl = MusicPlayManager.getFinalUrl(launch.playUrl());
            } catch (RuntimeException var11) {
               LOGGER.warn("NetMusic 最终地址解析失败: song='{}' value='{}' reason={}", new Object[]{songName, launch.playUrl(), var11.toString()});
               return false;
            }

            if (finalUrl.isEmpty()) {
               return false;
            } else {
               String resolved = finalUrl.get();
               if (!PlayableMediaUrl.isHttp(resolved)) {
                  LOGGER.warn("NetMusic 返回非 HTTP(S) 最终地址: song='{}' value='{}'", songName, resolved);
                  return false;
               } else {
                  try {
                     URL url = new URI(resolved).toURL();
                     Minecraft minecraft = Minecraft.getInstance();
                     if (minecraft.isSameThread()) {
                        return submitSound(minecraft, soundFactory.apply(url, lyricRecord), songName);
                     } else {
                        minecraft.execute(() -> submitSound(minecraft, soundFactory.apply(url, lyricRecord), songName));
                        return true;
                     }
                  } catch (URISyntaxException | RuntimeException | MalformedURLException var10) {
                     LOGGER.warn("同步媒体地址解析/提交失败: song='{}' value='{}' reason={}", new Object[]{songName, resolved, var10.toString()});
                     return false;
                  }
               }
            }
         }
      }
   }

   private static boolean submitSound(Minecraft minecraft, SoundInstance sound, String songName) {
      if (sound == null) {
         LOGGER.warn("同步媒体声音工厂返回空实例: song='{}'", songName);
         return false;
      } else {
         minecraft.getSoundManager().play(sound);
         LOGGER.debug(
            "同步媒体声音引擎已接受: song='{}' sound={} volume={} canStartSilent={}",
            new Object[]{songName, sound.getLocation(), sound.getVolume(), sound.canStartSilent()}
         );
         return true;
      }
   }

   public record LaunchResult(String playUrl, LyricRecord lyricRecord, Optional<MediaRequestToken> requestToken) {
      public LaunchResult(String playUrl, LyricRecord lyricRecord, Optional<MediaRequestToken> requestToken) {
         requestToken = requestToken == null ? Optional.empty() : requestToken;
         this.playUrl = playUrl;
         this.lyricRecord = lyricRecord;
         this.requestToken = requestToken;
      }

      public LaunchResult(String playUrl, LyricRecord lyricRecord) {
         this(playUrl, lyricRecord, Optional.empty());
      }
   }
}
