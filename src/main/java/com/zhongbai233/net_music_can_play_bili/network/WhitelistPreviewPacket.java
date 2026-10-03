package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaIoExecutor;
import io.netty.handler.codec.DecoderException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

public record WhitelistPreviewPacket(
   UUID previewId,
   String title,
   String rawUrl,
   String audioUrl,
   String videoUrl,
   int videoWidth,
   int videoHeight,
   int fps,
   int codecId,
   int durationSeconds,
   long elapsedMillis,
   boolean playing,
   long requestId
) implements CustomPacketPayload {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_URL_LENGTH = 8192;
   private static final int MAX_TITLE_LENGTH = 256;
   public static final Type<WhitelistPreviewPacket> TYPE = new Type(NetworkPayloadIds.id("whitelist_preview"));
   public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistPreviewPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, WhitelistPreviewPacket>() {
      public WhitelistPreviewPacket decode(RegistryFriendlyByteBuf buffer) {
         return new WhitelistPreviewPacket(
            buffer.readUUID(),
            buffer.readUtf(256),
            buffer.readUtf(8192),
            buffer.readUtf(8192),
            buffer.readUtf(8192),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarLong(),
            buffer.readBoolean(),
            WhitelistPreviewPacket.readRequestId(buffer)
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, WhitelistPreviewPacket packet) {
         buffer.writeUUID(packet.previewId());
         buffer.writeUtf(WhitelistPreviewPacket.safe(packet.title()), 256);
         buffer.writeUtf(WhitelistPreviewPacket.safe(packet.rawUrl()), 8192);
         buffer.writeUtf(WhitelistPreviewPacket.safe(packet.audioUrl()), 8192);
         buffer.writeUtf(WhitelistPreviewPacket.safe(packet.videoUrl()), 8192);
         buffer.writeVarInt(Math.max(1, packet.videoWidth()));
         buffer.writeVarInt(Math.max(1, packet.videoHeight()));
         buffer.writeVarInt(Math.max(1, packet.fps()));
         buffer.writeVarInt(packet.codecId());
         buffer.writeVarInt(Math.max(0, packet.durationSeconds()));
         buffer.writeVarLong(Math.max(0L, packet.elapsedMillis()));
         buffer.writeBoolean(packet.playing());
         buffer.writeVarLong(Math.max(0L, packet.requestId()));
      }
   };

   public WhitelistPreviewPacket(
      UUID previewId,
      String title,
      String rawUrl,
      String audioUrl,
      String videoUrl,
      int videoWidth,
      int videoHeight,
      int fps,
      int codecId,
      int durationSeconds,
      long elapsedMillis,
      boolean playing
   ) {
      this(previewId, title, rawUrl, audioUrl, videoUrl, videoWidth, videoHeight, fps, codecId, durationSeconds, elapsedMillis, playing, 0L);
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void resolveAndSend(ServerPlayer player, String idOrLink, long elapsedMillis, boolean playing) {
      resolveAndSend(player, idOrLink, elapsedMillis, playing, 0L);
   }

   public static void resolveAndSend(ServerPlayer player, String idOrLink, long elapsedMillis, boolean playing, long requestId) {
      if (player != null && idOrLink != null && !idOrLink.isBlank()) {
         String audioOnlyUrl = audioOnlyUrl(idOrLink);
         if (audioOnlyUrl != null) {
            player.sendSystemMessage(Component.literal("正在准备音频预览...").withStyle(ChatFormatting.GRAY));
            MediaIoExecutor.<WhitelistPreviewPacket>supply(() -> audioOnlyPacket(audioOnlyUrl, elapsedMillis, playing, requestId))
               .whenComplete((packet, error) -> player.level().getServer().execute(() -> {
                  if (error != null) {
                     LOGGER.warn("白名单审核音频预览解析失败，将使用未知时长: {}", audioOnlyUrl, error);
                     PacketDistributor.sendToPlayer(
                        player, fallbackAudioOnlyPacket(audioOnlyUrl, elapsedMillis, playing, requestId), new CustomPacketPayload[0]
                     );
                  } else {
                     PacketDistributor.sendToPlayer(player, packet, new CustomPacketPayload[0]);
                  }
               }));
         } else {
            BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(idOrLink);
            if (selection == null) {
               player.sendSystemMessage(Component.literal("当前条目不是可预览的 B站视频。").withStyle(ChatFormatting.YELLOW));
            } else {
               player.sendSystemMessage(Component.literal("正在准备预览...").withStyle(ChatFormatting.GRAY));
               MediaIoExecutor.<WhitelistPreviewPacket>supply(
                     () -> {
                        try {
                           BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
                           UUID previewId = UUID.nameUUIDFromBytes(
                              ("whitelist-preview:" + selection.videoId().asInputText() + ":" + selection.page()).getBytes(StandardCharsets.UTF_8)
                           );
                           long durationMillis = Math.max(0L, (long)info.duration()) * 1000L;
                           long startMillis = clampMillis(elapsedMillis, durationMillis);
                           String sessionId = previewSession(previewId, startMillis);
                           String storedSelection = BiliApiClient.formatStoredVideoSelection(selection.videoId(), selection.page());
                           String syncedSelection = PlaybackSync.withSync(storedSelection, sessionId, startMillis, durationMillis);
                           return new WhitelistPreviewPacket(
                              previewId,
                              info.displayTitle(),
                              storedSelection,
                              syncedSelection,
                              storedSelection,
                              1,
                              1,
                              30,
                              0,
                              Math.max(0, info.duration()),
                              startMillis,
                              playing,
                              requestId
                           );
                        } catch (Exception var15) {
                           throw new IllegalStateException(var15);
                        }
                     }
                  )
                  .whenComplete((packet, error) -> player.level().getServer().execute(() -> {
                     if (error != null) {
                        LOGGER.warn("白名单审核预览解析失败: {}", idOrLink, error);
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        player.sendSystemMessage(Component.literal("预览解析失败：" + cause.getMessage()).withStyle(ChatFormatting.RED));
                     } else {
                        PacketDistributor.sendToPlayer(player, packet, new CustomPacketPayload[0]);
                     }
                  }));
            }
         }
      }
   }

   public static MP4PlaybackSyncPacket toAudioSync(WhitelistPreviewPacket packet) {
      String sessionId = previewSession(packet.previewId(), packet.elapsedMillis());
      return new MP4PlaybackSyncPacket(
         packet.previewId(),
         packet.previewId(),
         0,
         -1,
         0.0,
         0.0,
         0.0,
         packet.playing(),
         0,
         packet.audioUrl(),
         packet.rawUrl(),
         packet.title(),
         packet.durationSeconds(),
         850,
         sessionId,
         packet.elapsedMillis(),
         false
      );
   }

   public static String sessionId(UUID previewId, long elapsedMillis) {
      return previewSession(previewId, elapsedMillis);
   }

   public static MP4PlaybackSyncPacket stopAudio(UUID previewId) {
      return MP4PlaybackSyncPacket.stop(previewId, previewId, 0);
   }

   public static void handle(WhitelistPreviewPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> WhitelistPreviewScreen.openOrUpdate(payload));
   }

   private static String previewSession(UUID previewId, long elapsedMillis) {
      return previewId + "-whitelist-preview-" + Math.max(0L, elapsedMillis);
   }

   private static WhitelistPreviewPacket audioOnlyPacket(String rawUrl, long elapsedMillis, boolean playing, long requestId) {
      String safeRawUrl = Objects.requireNonNull(rawUrl, "rawUrl");
      SongInfo original = new SongInfo(safeRawUrl, Objects.requireNonNull(audioTitle(safeRawUrl)), 0, false);
      SongInfo resolved = (SongInfo)MusicPlayResolverManager.resolve(original).join();
      String playUrl = resolved != null && resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : safeRawUrl;
      String title = resolved != null && resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName : audioTitle(safeRawUrl);
      int durationSeconds = resolved != null ? Math.max(0, resolved.songTime) : 0;
      return audioOnlyPacket(safeRawUrl, playUrl, title, durationSeconds, elapsedMillis, playing, requestId);
   }

   private static WhitelistPreviewPacket fallbackAudioOnlyPacket(String rawUrl, long elapsedMillis, boolean playing, long requestId) {
      return audioOnlyPacket(rawUrl, rawUrl, audioTitle(rawUrl), 0, elapsedMillis, playing, requestId);
   }

   private static WhitelistPreviewPacket audioOnlyPacket(
      String rawUrl, String playUrl, String title, int durationSeconds, long elapsedMillis, boolean playing, long requestId
   ) {
      UUID previewId = UUID.nameUUIDFromBytes(("whitelist-preview-audio:" + rawUrl).getBytes(StandardCharsets.UTF_8));
      long durationMillis = Math.max(0, durationSeconds) * 1000L;
      long startMillis = clampMillis(elapsedMillis, durationMillis);
      String sessionId = previewSession(previewId, startMillis);
      String syncedAudioUrl = PlaybackSync.withSync(playUrl, sessionId, startMillis, durationMillis);
      return new WhitelistPreviewPacket(
         previewId, title, rawUrl, syncedAudioUrl, "", 1, 1, 30, 0, Math.max(0, durationSeconds), startMillis, playing, requestId
      );
   }

   private static String audioOnlyUrl(String idOrLink) {
      String value = idOrLink == null ? "" : idOrLink.trim();
      if (value.regionMatches(true, 0, "url:", 0, 4)) {
         value = value.substring(4).trim();
      }

      if (value.regionMatches(true, 0, "bili:", 0, 5)) {
         return null;
      } else {
         return !value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("ftp://") ? null : value;
      }
   }

   private static String audioTitle(String rawUrl) {
      try {
         URI uri = URI.create(rawUrl);
         String host = uri.getHost();
         return host != null && !host.isBlank() ? "白名单音频预览 · " + host : "白名单音频预览";
      } catch (Exception var3) {
         return "白名单音频预览";
      }
   }

   private static long clampMillis(long value, long totalMillis) {
      long safe = Math.max(0L, value);
      return totalMillis > 0L ? Math.min(totalMillis, safe) : safe;
   }

   private static long readRequestId(RegistryFriendlyByteBuf buffer) {
      long value = buffer.readVarLong();
      if (value < 0L) {
         throw new DecoderException("Invalid whitelist preview request id: " + value);
      } else {
         return value;
      }
   }

   private static String safe(String value) {
      return value == null ? "" : value;
   }
}
