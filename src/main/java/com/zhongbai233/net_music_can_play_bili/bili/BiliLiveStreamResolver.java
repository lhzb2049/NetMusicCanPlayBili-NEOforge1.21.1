package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BiliLiveStreamResolver {
   public static final int LIVE_STATUS_OFFLINE = 0;
   public static final int LIVE_STATUS_LIVE = 1;
   public static final int LIVE_STATUS_CAROUSEL = 2;
   private static final String ROOM_INIT_API = "https://api.live.bilibili.com/room/v1/Room/room_init";
   private static final String ROOM_INFO_API = "https://api.live.bilibili.com/room/v1/Room/get_info";
   private static final String PLAY_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo";
   private static final String LEGACY_PLAY_URL_API = "https://api.live.bilibili.com/room/v1/Room/playUrl";
   private static final Duration API_TIMEOUT = Duration.ofSeconds(10L);
   private static final long ROOM_METADATA_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
   private static final int ROOM_METADATA_CACHE_LIMIT = 128;
   private static final ConcurrentHashMap<String, BiliLiveStreamResolver.CachedLiveMetadata> ROOM_METADATA = new ConcurrentHashMap<>();

   private BiliLiveStreamResolver() {
   }

   public static boolean isValidRoomId(String roomId) {
      if (roomId != null && !roomId.isBlank() && roomId.length() <= 16) {
         for (int i = 0; i < roomId.length(); i++) {
            if (roomId.charAt(i) < '0' || roomId.charAt(i) > '9') {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   public static BiliLiveStreamResolver.LiveRoom resolve(String roomId) throws IOException {
      if (!isValidRoomId(roomId)) {
         throw new IOException("非法的直播间号: " + roomId);
      } else {
         BiliLiveStreamResolver.RoomInit init = requestRoomInit(roomId);
         String realRoomId = init != null && init.roomId() > 0L ? Long.toString(init.roomId()) : roomId;
         JsonObject data = requestPlayInfo(realRoomId);
         int liveStatus = data.has("live_status") ? optInt(data, "live_status", 0) : (init != null ? init.liveStatus() : 0);
         List<BiliLiveStreamResolver.LiveStream> streams = parseStreams(data);
         if (streams.isEmpty()) {
            String legacy = requestLegacyFlvUrl(realRoomId);
            if (legacy != null) {
               streams = List.of(new BiliLiveStreamResolver.LiveStream("http_stream", "flv", "", legacy));
            }
         }

         return new BiliLiveStreamResolver.LiveRoom(realRoomId, liveStatus, streams, requestRoomMetadata(realRoomId));
      }
   }

   public static int queryLiveStatus(String roomId) throws IOException {
      if (!isValidRoomId(roomId)) {
         throw new IOException("非法的直播间号: " + roomId);
      } else {
         BiliLiveStreamResolver.RoomInit init = requestRoomInit(roomId);
         return init != null ? init.liveStatus() : -1;
      }
   }

   private static BiliLiveStreamResolver.RoomInit requestRoomInit(String roomId) throws IOException {
      JsonObject root;
      try {
         root = getJson("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + roomId);
      } catch (IOException var6) {
         return null;
      }

      int code = optInt(root, "code", -1);
      if (code == 60004) {
         throw new IOException("直播间不存在: " + roomId);
      } else if (code != 0) {
         return null;
      } else {
         JsonObject data = optObject(root, "data");
         if (data == null) {
            return null;
         } else {
            long realRoomId = optLong(data, "room_id", 0L);
            return new BiliLiveStreamResolver.RoomInit(realRoomId, optInt(data, "live_status", 0));
         }
      }
   }

   private static BiliLiveStreamResolver.LiveMetadata requestRoomMetadata(String roomId) {
      long now = System.nanoTime();
      BiliLiveStreamResolver.CachedLiveMetadata cached = ROOM_METADATA.get(roomId);
      if (cached != null && now - cached.storedNanos() >= 0L && now - cached.storedNanos() < ROOM_METADATA_TTL_NANOS) {
         return cached.metadata();
      } else {
         try {
            BiliLiveStreamResolver.LiveMetadata metadata = parseRoomMetadata(
               getJson("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId), roomId
            );
            cacheRoomMetadata(roomId, new BiliLiveStreamResolver.CachedLiveMetadata(metadata, now), now);
            return metadata;
         } catch (RuntimeException | IOException var5) {
            return cached != null ? cached.metadata() : BiliLiveStreamResolver.LiveMetadata.empty(roomId);
         }
      }
   }

   private static void cacheRoomMetadata(String roomId, BiliLiveStreamResolver.CachedLiveMetadata metadata, long now) {
      if (ROOM_METADATA.size() >= 128) {
         ROOM_METADATA.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().storedNanos();
            return age < 0L || age >= ROOM_METADATA_TTL_NANOS;
         });
      }

      while (ROOM_METADATA.size() >= 128) {
         String oldestKey = ROOM_METADATA.entrySet()
            .stream()
            .min(Comparator.comparingLong(entry -> entry.getValue().storedNanos()))
            .map(entry -> entry.getKey())
            .orElse(null);
         if (oldestKey == null || ROOM_METADATA.remove(oldestKey) == null) {
            break;
         }
      }

      ROOM_METADATA.put(roomId, metadata);
   }

   static BiliLiveStreamResolver.LiveMetadata parseRoomMetadata(JsonObject root, String fallbackRoomId) throws IOException {
      if (optInt(root, "code", -1) != 0) {
         throw new IOException("B站直播房间信息接口返回错误: code=" + optInt(root, "code", -1));
      } else {
         JsonObject data = optObject(root, "data");
         if (data == null) {
            throw new IOException("B站直播房间信息接口未返回 data");
         } else {
            long numericRoomId = optLong(data, "room_id", 0L);
            String roomId = numericRoomId > 0L ? Long.toString(numericRoomId) : fallbackRoomId;
            return new BiliLiveStreamResolver.LiveMetadata(
               roomId,
               optString(data, "title", ""),
               optString(data, "parent_area_name", ""),
               optString(data, "area_name", ""),
               optString(data, "live_time", "")
            );
         }
      }
   }

   public static List<BiliLiveStreamResolver.LiveStream> parseStreams(JsonObject data) {
      JsonArray streamArray = playurlStreams(data);
      if (streamArray == null) {
         return List.of();
      } else {
         List<BiliLiveStreamResolver.RankedStream> ranked = new ArrayList<>();
         Set<String> seenUrls = new LinkedHashSet<>();

         for (JsonElement streamElement : streamArray) {
            if (streamElement.isJsonObject()) {
               JsonObject stream = streamElement.getAsJsonObject();
               String protocol = optString(stream, "protocol_name", "");

               for (JsonElement formatElement : optArray(stream, "format")) {
                  if (formatElement.isJsonObject()) {
                     JsonObject format = formatElement.getAsJsonObject();
                     String formatName = optString(format, "format_name", "");
                     int rank = rank(protocol, formatName);
                     if (rank >= 0) {
                        collectCodecUrls(format, protocol, formatName, rank, ranked, seenUrls);
                     }
                  }
               }
            }
         }

         ranked.sort(Comparator.comparingInt(entry -> entry.rank()));
         return ranked.stream().map(entry -> entry.stream()).toList();
      }
   }

   private static void collectCodecUrls(
      JsonObject format, String protocol, String formatName, int rank, List<BiliLiveStreamResolver.RankedStream> ranked, Set<String> seenUrls
   ) {
      for (JsonElement codecElement : optArray(format, "codec")) {
         if (codecElement.isJsonObject()) {
            JsonObject codec = codecElement.getAsJsonObject();
            String codecName = optString(codec, "codec_name", "");
            if (!"hevc".equalsIgnoreCase(codecName)) {
               String baseUrl = optString(codec, "base_url", "");

               for (JsonElement urlElement : optArray(codec, "url_info")) {
                  if (urlElement.isJsonObject()) {
                     JsonObject urlInfo = urlElement.getAsJsonObject();
                     String full = optString(urlInfo, "host", "") + baseUrl + optString(urlInfo, "extra", "");
                     if (full.startsWith("http") && seenUrls.add(full)) {
                        ranked.add(new BiliLiveStreamResolver.RankedStream(rank, new BiliLiveStreamResolver.LiveStream(protocol, formatName, codecName, full)));
                     }
                  }
               }
            }
         }
      }
   }

   private static int rank(String protocol, String formatName) {
      if ("http_stream".equals(protocol) && "flv".equals(formatName)) {
         return 0;
      } else if ("http_hls".equals(protocol) && "ts".equals(formatName)) {
         return 1;
      } else {
         return "http_hls".equals(protocol) && "fmp4".equals(formatName) ? 2 : -1;
      }
   }

   private static JsonArray playurlStreams(JsonObject data) {
      JsonObject playurlInfo = optObject(data, "playurl_info");
      JsonObject playurl = playurlInfo != null ? optObject(playurlInfo, "playurl") : null;
      return playurl != null && playurl.has("stream") && playurl.get("stream").isJsonArray() ? playurl.getAsJsonArray("stream") : null;
   }

   private static JsonObject requestPlayInfo(String roomId) throws IOException {
      String url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id="
         + roomId
         + "&protocol=0,1&format=0,1,2&codec=0&qn=10000&platform=web&ptype=8";
      JsonObject root = getJson(url);
      int code = optInt(root, "code", -1);
      if (code != 0) {
         throw new IOException("B站直播接口返回错误: code=" + code + " message=" + optString(root, "message", ""));
      } else {
         JsonObject data = optObject(root, "data");
         if (data == null) {
            throw new IOException("B站直播接口未返回房间数据");
         } else {
            return data;
         }
      }
   }

   private static String requestLegacyFlvUrl(String roomId) {
      try {
         JsonObject root = getJson("https://api.live.bilibili.com/room/v1/Room/playUrl?cid=" + roomId + "&platform=web&qn=0");
         JsonObject data = optObject(root, "data");
         if (data == null) {
            return null;
         }

         for (JsonElement element : optArray(data, "durl")) {
            if (element.isJsonObject()) {
               String url = optString(element.getAsJsonObject(), "url", "");
               if (url.startsWith("http")) {
                  return url;
               }
            }
         }
      } catch (RuntimeException | IOException var6) {
      }

      return null;
   }

   private static JsonObject getJson(String url) throws IOException {
      Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(API_TIMEOUT).GET();
      BiliRequestHeaders.applyLiveHeaders(builder, null);

      try {
         HttpResponse<String> response = CancellableHttpRequestScope.sendOneBlocking(
            BiliWbiSigner.HTTP, builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8), HttpRequestCloseDiagnostics.global(), "bili-api-live-resolve"
         );
         if (response.statusCode() != 200) {
            throw new IOException("B站直播接口 HTTP " + response.statusCode());
         } else {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
               throw new IOException("B站直播接口返回了非 JSON 对象");
            } else {
               return parsed.getAsJsonObject();
            }
         }
      } catch (InterruptedException var4) {
         Thread.currentThread().interrupt();
         throw new IOException("解析直播地址时被中断", var4);
      } catch (RuntimeException var5) {
         throw new IOException("解析直播地址失败: " + var5.getMessage(), var5);
      }
   }

   public static String describeLiveStatus(int liveStatus) {
      return switch (liveStatus) {
         case 0 -> "未开播";
         case 1 -> "直播中";
         case 2 -> "轮播中";
         default -> "未知状态(" + liveStatus + ")";
      };
   }

   private static JsonObject optObject(JsonObject parent, String key) {
      return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
   }

   private static JsonArray optArray(JsonObject parent, String key) {
      return parent != null && parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : new JsonArray();
   }

   private static String optString(JsonObject parent, String key, String fallback) {
      if (parent != null && parent.has(key) && !parent.get(key).isJsonNull()) {
         try {
            return parent.get(key).getAsString().trim();
         } catch (RuntimeException var4) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   private static int optInt(JsonObject parent, String key, int fallback) {
      if (parent != null && parent.has(key) && !parent.get(key).isJsonNull()) {
         try {
            return parent.get(key).getAsInt();
         } catch (RuntimeException var4) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   private static long optLong(JsonObject parent, String key, long fallback) {
      if (parent != null && parent.has(key) && !parent.get(key).isJsonNull()) {
         try {
            return parent.get(key).getAsLong();
         } catch (RuntimeException var5) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   private static String safeMetadataText(String value, int maxLength) {
      String normalized = value == null ? "" : value.trim();
      return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
   }

   private record CachedLiveMetadata(BiliLiveStreamResolver.LiveMetadata metadata, long storedNanos) {
   }

   public record LiveMetadata(String roomId, String title, String parentAreaName, String areaName, String liveTime) {
      public LiveMetadata(String roomId, String title, String parentAreaName, String areaName, String liveTime) {
         roomId = BiliLiveStreamResolver.safeMetadataText(roomId, 32);
         title = BiliLiveStreamResolver.safeMetadataText(title, 256);
         parentAreaName = BiliLiveStreamResolver.safeMetadataText(parentAreaName, 64);
         areaName = BiliLiveStreamResolver.safeMetadataText(areaName, 64);
         liveTime = BiliLiveStreamResolver.safeMetadataText(liveTime, 64);
         this.roomId = roomId;
         this.title = title;
         this.parentAreaName = parentAreaName;
         this.areaName = areaName;
         this.liveTime = liveTime;
      }

      public static BiliLiveStreamResolver.LiveMetadata empty(String roomId) {
         return new BiliLiveStreamResolver.LiveMetadata(roomId, "", "", "", "");
      }
   }

   public record LiveRoom(String roomId, int liveStatus, List<BiliLiveStreamResolver.LiveStream> streams, BiliLiveStreamResolver.LiveMetadata metadata) {
      public LiveRoom(String roomId, int liveStatus, List<BiliLiveStreamResolver.LiveStream> streams, BiliLiveStreamResolver.LiveMetadata metadata) {
         streams = streams != null ? List.copyOf(streams) : List.of();
         metadata = metadata != null ? metadata : BiliLiveStreamResolver.LiveMetadata.empty(roomId);
         this.roomId = roomId;
         this.liveStatus = liveStatus;
         this.streams = streams;
         this.metadata = metadata;
      }

      public LiveRoom(String roomId, int liveStatus, List<BiliLiveStreamResolver.LiveStream> streams) {
         this(roomId, liveStatus, streams, BiliLiveStreamResolver.LiveMetadata.empty(roomId));
      }

      public boolean isLive() {
         return this.liveStatus == 1 || this.liveStatus == 2;
      }

      public List<String> flvUrls() {
         return this.streams.stream().filter(stream -> stream.isFlv()).map(stream -> stream.url()).toList();
      }

      public List<String> hlsUrls() {
         return this.streams.stream().filter(stream -> stream.isHls()).map(stream -> stream.url()).toList();
      }
   }

   public record LiveStream(String protocol, String format, String codec, String url) {
      public boolean isFlv() {
         return "flv".equals(this.format);
      }

      public boolean isHls() {
         return "http_hls".equals(this.protocol);
      }
   }

   private record RankedStream(int rank, BiliLiveStreamResolver.LiveStream stream) {
   }

   private record RoomInit(long roomId, int liveStatus) {
   }
}
