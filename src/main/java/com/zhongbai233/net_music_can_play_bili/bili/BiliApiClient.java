package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

public final class BiliApiClient {
   public static final int CODEC_H264 = 7;
   public static final int CODEC_HEVC = 12;
   public static final int CODEC_AV1 = 13;
   static final int FNVAL_DASH = 16;
   static final int FNVAL_4K = 128;
   static final int FNVAL_8K = 1024;
   static final int FNVAL_AV1 = 2048;
   private static final int[] STANDARD_AUDIO_ORDER = new int[]{30280, 30232, 30216};
   private static final int[] LOSSLESS_AUDIO_ORDER = new int[]{30251, 30280, 30232, 30216};
   private static final int[] DOLBY_AUDIO_ORDER = new int[]{30250, 30251, 30280, 30232, 30216};
   private static final int[] BEST_AUDIO_ORDER = new int[]{30250, 30251, 30280, 30232, 30216};
   private static final Duration SHORT_LINK_TIMEOUT = Duration.ofSeconds(5L);
   private static final HttpClient SHORT_LINK_HTTP = HttpClient.newBuilder().connectTimeout(SHORT_LINK_TIMEOUT).followRedirects(Redirect.ALWAYS).build();
   public static volatile String sessdata = BiliApiProperties.initialSessdata();
   public static volatile String webCookie = BiliApiProperties.initialWebCookie();

   private BiliApiClient() {
   }

   public static boolean isBiliVideoId(String input) {
      return BiliVideoReferenceParser.isVideoId(input);
   }

   public static BiliApiClient.VideoId extractVideoId(String raw) {
      return BiliVideoReferenceParser.extractVideoId(raw);
   }

   public static BiliApiClient.VideoId extractVideoIdLenient(String raw) {
      return BiliVideoReferenceParser.extractVideoIdLenient(raw);
   }

   public static boolean containsBiliVideoId(String input) {
      return extractVideoIdLenient(input) != null;
   }

   public static BiliApiClient.VideoId extractVideoIdLenientWithShortLink(String raw) {
      BiliApiClient.VideoId direct = extractVideoIdLenient(raw);
      if (direct == null && raw != null && !raw.isBlank() && BiliVideoReferenceParser.looksLikeShortLink(raw)) {
         String expanded = expandBiliShortLink(raw.trim());
         return expanded == null ? null : extractVideoIdLenient(expanded);
      } else {
         return direct;
      }
   }

   public static BiliApiClient.VideoSelection extractVideoSelectionLenient(String raw) {
      return BiliVideoReferenceParser.extractSelectionLenient(raw);
   }

   public static BiliApiClient.VideoSelection extractVideoSelectionLenientWithShortLink(String raw) {
      BiliApiClient.VideoSelection direct = extractVideoSelectionLenient(raw);
      if (direct == null && raw != null && !raw.isBlank() && BiliVideoReferenceParser.looksLikeShortLink(raw)) {
         String expanded = expandBiliShortLink(raw.trim());
         return expanded == null ? null : extractVideoSelectionLenient(expanded);
      } else {
         return direct;
      }
   }

   private static String expandBiliShortLink(String raw) {
      try {
         URI uri = URI.create(!raw.startsWith("http://") && !raw.startsWith("https://") ? "https://" + raw : raw);
         Builder builder = HttpRequest.newBuilder(uri).timeout(SHORT_LINK_TIMEOUT).GET();
         BiliRequestHeaders.applyWebApiHeaders(builder);
         HttpRequest req = builder.build();
         return sendApi(SHORT_LINK_HTTP, req, BodyHandlers.discarding(), "bili-api-short-link").uri().toString();
      } catch (Exception var4) {
         logger().debug("B站短链展开失败: {}", raw, var4);
         return null;
      }
   }

   public static boolean isStoredVideoSelection(String raw) {
      return parseStoredVideoSelection(raw) != null;
   }

   public static BiliApiClient.VideoSelection parseStoredVideoSelection(String raw) {
      return BiliVideoReferenceParser.parseStoredSelection(raw);
   }

   public static String formatStoredVideoSelection(BiliApiClient.VideoId videoId, int page) {
      return BiliVideoReferenceParser.formatStoredSelection(videoId, page);
   }

   public static BiliApiClient.VideoInfo getVideoInfo(BiliApiClient.VideoId id) throws Exception {
      return getVideoInfo(id, 1);
   }

   public static BiliApiClient.VideoInfo getVideoInfo(BiliApiClient.VideoId id, int requestedPage) throws Exception {
      Map<String, String> params = new HashMap<>();
      id.putViewParam(params);
      Map<String, String> signed = BiliWbiSigner.signParams(params);
      String url = "https://api.bilibili.com/x/web-interface/view?" + BiliWbiSigner.buildQuery(signed);
      Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15L)).GET();
      BiliRequestHeaders.applyWebApiHeaders(builder);
      HttpRequest req = builder.build();
      HttpResponse<String> resp = sendApi(BiliWbiSigner.HTTP, req, BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-api-view");
      JsonObject body = BiliApiResponseParser.parse(resp, "view");
      int code = body.get("code").getAsInt();
      if (code != 0) {
         String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
         throw new RuntimeException("B站 view API 返回 code=" + code + ": " + msg);
      } else {
         JsonObject data = body.getAsJsonObject("data");
         long aid = data.get("aid").getAsLong();
         String title = data.get("title").getAsString();
         List<String> staffNames = new ArrayList<>();
         if (data.has("staff") && !data.get("staff").isJsonNull()) {
            for (JsonElement e : data.getAsJsonArray("staff")) {
               JsonObject member = e.getAsJsonObject();
               if (member.has("name") && !member.get("name").isJsonNull()) {
                  staffNames.add(member.get("name").getAsString());
               }
            }
         }

         if (staffNames.isEmpty() && data.has("owner") && !data.get("owner").isJsonNull()) {
            JsonObject owner = data.getAsJsonObject("owner");
            if (owner.has("name") && !owner.get("name").isJsonNull()) {
               staffNames.add(owner.get("name").getAsString());
            }
         }

         int duration = data.get("duration").getAsInt();
         JsonArray pages = data.getAsJsonArray("pages");
         int totalPages = pages != null && !pages.isEmpty() ? pages.size() : 1;
         int actualPage = 1;
         String part = "";
         long cid;
         if (pages != null && !pages.isEmpty()) {
            if (requestedPage < 1 || requestedPage > pages.size()) {
               throw new IllegalArgumentException("该视频只有 " + pages.size() + " 个分P");
            }

            actualPage = requestedPage;
            JsonObject page = pages.get(requestedPage - 1).getAsJsonObject();
            cid = page.get("cid").getAsLong();
            if (page.has("duration") && !page.get("duration").isJsonNull()) {
               duration = page.get("duration").getAsInt();
            }

            if (page.has("part") && !page.get("part").isJsonNull()) {
               part = page.get("part").getAsString();
            }
         } else {
            cid = data.get("cid").getAsLong();
         }

         return new BiliApiClient.VideoInfo(aid, title, staffNames, cid, duration, actualPage, totalPages, part, id);
      }
   }

   public static String getBestAudioUrl(BiliApiClient.VideoId id, long cid) throws Exception {
      return getBestAudioUrl(id, cid, true);
   }

   public static String getBestAudioUrl(BiliApiClient.VideoId id, long cid, boolean allowDolby) throws Exception {
      Map<String, String> params = new HashMap<>();
      id.putPlayUrlParam(params);
      params.put("cid", String.valueOf(cid));
      params.put("fnval", "4048");
      params.put("fnver", "0");
      params.put("fourk", "1");
      params.put("platform", "pc");
      Map<String, String> signed = BiliWbiSigner.signParams(params);
      String url = "https://api.bilibili.com/x/player/wbi/playurl?" + BiliWbiSigner.buildQuery(signed);
      Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15L));
      BiliRequestHeaders.applyWebApiHeaders(builder);
      HttpRequest req = builder.GET().build();
      HttpResponse<String> resp = sendApi(BiliWbiSigner.HTTP, req, BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-api-audio-playurl");
      JsonObject body = BiliApiResponseParser.parse(resp, "audio playurl");
      int code = body.get("code").getAsInt();
      if (code != 0) {
         String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
         throw new RuntimeException("B站 playurl API 返回 code=" + code + ": " + msg);
      } else {
         JsonObject data = body.getAsJsonObject("data");
         JsonObject dash = data != null && data.has("dash") && data.get("dash").isJsonObject() ? data.getAsJsonObject("dash") : null;
         if (dash == null) {
            boolean legacyDurl = data != null && data.has("durl") && data.get("durl").isJsonArray();
            throw new BiliApiResponseException("B站 audio playurl API 未返回 DASH 数据" + (legacyDurl ? "（仅返回 legacy durl）" : ""));
         } else {
            Map<Integer, List<String>> streams = new HashMap<>();
            JsonArray audioArr = dash.has("audio") && !dash.get("audio").isJsonNull() ? dash.getAsJsonArray("audio") : null;
            if (audioArr != null) {
               for (JsonElement e : audioArr) {
                  JsonObject a = e.getAsJsonObject();
                  addAudioStreamCandidates(streams, a);
               }
            }

            boolean nativeDolbyAvailable = Eac3NativeDecoder.isNativeAvailable();
            boolean dashHasDolby = dash.has("dolby") && !dash.get("dolby").isJsonNull();
            boolean dolbyOk = allowDolby && BiliConfig.dolbyEnabled && nativeDolbyAvailable;
            if (dolbyOk && dashHasDolby) {
               JsonObject dolby = dash.getAsJsonObject("dolby");
               if (dolby.has("audio") && !dolby.get("audio").isJsonNull()) {
                  for (JsonElement e : dolby.getAsJsonArray("audio")) {
                     JsonObject a = e.getAsJsonObject();
                     addAudioStreamCandidates(streams, a);
                  }
               }
            }

            String configuredAudioPreference = BiliApiProperties.audioPreference();
            boolean allowFlac = configuredAudioPreference.equals("auto")
               || configuredAudioPreference.equals("dolby")
               || configuredAudioPreference.equals("atmos")
               || configuredAudioPreference.equals("eac3")
               || configuredAudioPreference.equals("flac")
               || configuredAudioPreference.equals("lossless")
               || configuredAudioPreference.equals("hires")
               || configuredAudioPreference.equals("best");
            if (allowFlac && dash.has("flac") && !dash.get("flac").isJsonNull()) {
               JsonObject flac = dash.getAsJsonObject("flac");
               if (flac.has("audio") && !flac.get("audio").isJsonNull()) {
                  JsonObject a = flac.getAsJsonObject("audio");
                  addAudioStreamCandidates(streams, a);
               }
            }

            if (streams.isEmpty()) {
               throw new BiliApiClient.NoAudioStreamException("该视频没有可用的 DASH 音频流");
            } else {
               int selectedQuality = -1;
               String selectedUrl = null;
               List<String> selectedCandidates = List.of();
               String effectiveAudioPreference = effectiveAudioPreference(configuredAudioPreference);
               int[] qualityOrder = audioQualityOrder(effectiveAudioPreference, allowDolby);

               for (int qid : qualityOrder) {
                  List<String> candidates = streams.get(qid);
                  if (candidates != null && !candidates.isEmpty()) {
                     selectedQuality = qid;
                     selectedCandidates = BiliCdnSelector.orderCandidates(candidates);
                     selectedUrl = BiliCdnSelector.selectPreferred(selectedCandidates);
                     break;
                  }
               }

               if (selectedUrl == null) {
                  selectedQuality = streams.keySet().iterator().next();
                  selectedCandidates = BiliCdnSelector.orderCandidates(streams.get(selectedQuality));
                  selectedUrl = BiliCdnSelector.selectPreferred(selectedCandidates);
               }

               logger()
                  .debug(
                     "B站音频流选择摘要: id={} cid={} preference={} effectivePreference={} allowDolby={} dolbyConfig={} nativeDolby={} dashDolby={} qualities={} selected={}({}) candidateCount={} host={}",
                     new Object[]{
                        id.asInputText(),
                        cid,
                        configuredAudioPreference,
                        effectiveAudioPreference,
                        allowDolby,
                        BiliConfig.dolbyEnabled,
                        nativeDolbyAvailable,
                        dashHasDolby,
                        streams.keySet(),
                        selectedQuality,
                        audioQualityLabel(selectedQuality),
                        selectedCandidates.size(),
                        hostOf(selectedUrl)
                     }
                  );
               CdnUrlFallbacks.registerAlternates(selectedCandidates);
               return selectedUrl;
            }
         }
      }
   }

   private static void addAudioStreamCandidates(Map<Integer, List<String>> streams, JsonObject stream) {
      if (stream != null && stream.has("id") && !stream.get("id").isJsonNull()) {
         List<String> urls = extractStreamUrls(stream);
         if (!urls.isEmpty()) {
            for (String url : urls) {
               registerAudioSegmentBase(url, stream);
            }

            streams.computeIfAbsent(stream.get("id").getAsInt(), ignored -> new ArrayList<>()).addAll(urls);
         }
      }
   }

   private static List<String> extractStreamUrls(JsonObject stream) {
      Set<String> urls = new LinkedHashSet<>();
      addUrlField(urls, stream, "baseUrl");
      addUrlField(urls, stream, "base_url");
      addUrlField(urls, stream, "backupUrl");
      addUrlField(urls, stream, "backup_url");
      return new ArrayList<>(urls);
   }

   private static void addUrlField(Set<String> urls, JsonObject object, String field) {
      if (object != null && object.has(field) && !object.get(field).isJsonNull()) {
         JsonElement value = object.get(field);
         if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
               addUrlValue(urls, element);
            }
         } else {
            addUrlValue(urls, value);
         }
      }
   }

   private static void addUrlValue(Set<String> urls, JsonElement value) {
      if (value != null && !value.isJsonNull()) {
         String url = value.getAsString();
         if (url != null && !url.isBlank()) {
            urls.add(url);
         }
      }
   }

   private static int[] audioQualityOrder(String preference, boolean allowDolby) {
      return switch (preference) {
         case "auto", "best" -> allowDolby ? BEST_AUDIO_ORDER : LOSSLESS_AUDIO_ORDER;
         case "dolby", "atmos", "eac3" -> allowDolby ? DOLBY_AUDIO_ORDER : LOSSLESS_AUDIO_ORDER;
         case "flac", "lossless", "hires" -> LOSSLESS_AUDIO_ORDER;
         default -> STANDARD_AUDIO_ORDER;
      };
   }

   private static String effectiveAudioPreference(String configuredPreference) {
      return configuredPreference != null && !configuredPreference.isBlank() && !"auto".equals(configuredPreference) ? configuredPreference : "auto";
   }

   private static void registerAudioSegmentBase(String baseUrl, JsonObject stream) {
      long[] initRange = parseSegmentBaseRange(stream, "initialization");
      long[] indexRange = parseSegmentBaseRange(stream, "index_range");
      HttpAudioStreamHandler.registerSegmentBase(baseUrl, initRange[0], initRange[1], indexRange[0], indexRange[1]);
   }

   private static String hostOf(String value) {
      try {
         String host = URI.create(value).getHost();
         return host != null ? host : "unknown";
      } catch (Exception var2) {
         return "unknown";
      }
   }

   public static String getBestVideoUrl(BiliApiClient.VideoId id, long cid, int preferredQuality) throws Exception {
      return getBestVideoStream(id, cid, preferredQuality).baseUrl();
   }

   public static BiliApiClient.VideoStream getBestVideoStream(BiliApiClient.VideoId id, long cid, int preferredQuality) throws Exception {
      return getVideoStreamPlan(id, cid, preferredQuality).preferred();
   }

   public static BiliApiClient.VideoStreamPlan getVideoStreamPlan(BiliApiClient.VideoId id, long cid, int preferredQuality) throws Exception {
      Map<String, String> params = new HashMap<>();
      id.putPlayUrlParam(params);
      params.put("cid", String.valueOf(cid));
      params.put("qn", String.valueOf(preferredQuality));
      params.put("fnval", String.valueOf(videoFnval(preferredQuality)));
      params.put("fnver", "0");
      params.put("fourk", "1");
      params.put("high_quality", "1");
      params.put("platform", "pc");
      Map<String, String> signed = BiliWbiSigner.signParams(params);
      String url = "https://api.bilibili.com/x/player/wbi/playurl?" + BiliWbiSigner.buildQuery(signed);
      Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15L));
      BiliRequestHeaders.applyWebApiHeaders(builder);
      HttpRequest req = builder.GET().build();
      HttpResponse<String> resp = sendApi(BiliWbiSigner.HTTP, req, BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-api-video-playurl");
      List<BiliApiClient.VideoStream> streams = parseVideoStreams(BiliApiResponseParser.parse(resp, "video playurl").toString());
      BiliApiClient.VideoStreamPlan plan = buildVideoStreamPlan(streams, preferredQuality);
      BiliApiClient.VideoStream selected = plan.preferred();
      logger()
         .debug(
            "B站视频流选择摘要: id={} cid={} policy={} qualityCeiling={} available={} selected={} codec={} size={}x{} fps={} av1Candidates={} h264Candidates={} softwareAv1Candidates={} rejected={} host={}",
            new Object[]{
               id.asInputText(),
               cid,
               plan.codecPolicy().serializedName(),
               qualityLabel(preferredQuality),
               streams.stream().map(stream -> qualityLabel(stream.quality())).distinct().toList(),
               qualityLabel(selected.quality()),
               selected.codecId(),
               selected.width(),
               selected.height(),
               selected.frameRate(),
               plan.av1Candidates().size(),
               plan.h264Candidates().size(),
               plan.softwareAv1Candidates().size(),
               plan.diagnostics(),
               hostOf(selected.baseUrl())
            }
         );
      return registerVideoPlan(plan);
   }

   static List<BiliApiClient.VideoStream> parseVideoStreams(String responseBody) {
      JsonObject body = JsonParser.parseString(responseBody).getAsJsonObject();
      int code = body.get("code").getAsInt();
      if (code != 0) {
         String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
         throw new RuntimeException("B站 playurl API 返回 code=" + code + ": " + msg);
      } else {
         JsonObject dash = body.getAsJsonObject("data").getAsJsonObject("dash");
         JsonArray videoArr = dash.getAsJsonArray("video");
         if (videoArr != null && !videoArr.isEmpty()) {
            List<BiliApiClient.VideoStream> streams = new ArrayList<>();

            for (JsonElement e : videoArr) {
               JsonObject v = e.getAsJsonObject();
               int qid = v.get("id").getAsInt();
               int codecId = v.has("codecid") ? v.get("codecid").getAsInt() : 0;
               String baseUrl = v.has("baseUrl") && !v.get("baseUrl").isJsonNull()
                  ? v.get("baseUrl").getAsString()
                  : (v.has("base_url") && !v.get("base_url").isJsonNull() ? v.get("base_url").getAsString() : "");
               List<String> cdnCandidates = extractStreamUrls(v);
               if (cdnCandidates.isEmpty() && baseUrl != null && !baseUrl.isBlank()) {
                  cdnCandidates = List.of(baseUrl);
               }

               int width = v.has("width") && !v.get("width").isJsonNull() ? v.get("width").getAsInt() : 0;
               int height = v.has("height") && !v.get("height").isJsonNull() ? v.get("height").getAsInt() : 0;
               String frameRate = v.has("frameRate") && !v.get("frameRate").isJsonNull() ? v.get("frameRate").getAsString() : "";
               String codecs = v.has("codecs") && !v.get("codecs").isJsonNull() ? v.get("codecs").getAsString() : "";
               long[] initRange = parseSegmentBaseRange(v, "initialization");
               long[] indexRange = parseSegmentBaseRange(v, "index_range");
               streams.add(
                  new BiliApiClient.VideoStream(
                     qid, codecId, width, height, frameRate, codecs, baseUrl, initRange[0], initRange[1], indexRange[0], indexRange[1], cdnCandidates
                  )
               );
            }

            return List.copyOf(streams);
         } else {
            throw new RuntimeException("该视频没有 DASH 视频流");
         }
      }
   }

   static int videoFnval(int preferredQuality) {
      int fnval = 2064;
      if (videoQualityRank(preferredQuality) >= videoQualityRank(120)) {
         fnval |= 128;
      }

      if (videoQualityRank(preferredQuality) >= videoQualityRank(127)) {
         fnval |= 1024;
      }

      return fnval;
   }

   static BiliApiClient.VideoStreamPlan buildVideoStreamPlan(List<BiliApiClient.VideoStream> streams, int preferredQuality) {
      return buildVideoStreamPlan(streams, preferredQuality, BiliApiProperties.videoCodecPolicy());
   }

   static BiliApiClient.VideoStreamPlan buildVideoStreamPlan(
      List<BiliApiClient.VideoStream> streams, int preferredQuality, BiliApiClient.VideoCodecPolicy codecPolicy
   ) {
      Objects.requireNonNull(streams, "streams");
      Objects.requireNonNull(codecPolicy, "codecPolicy");
      int ceilingRank = videoQualityRank(preferredQuality);
      boolean requestedSpecial = isSpecialVideoQuality(preferredQuality);
      List<String> diagnostics = new ArrayList<>();
      List<BiliApiClient.VideoStream> accepted = new ArrayList<>();

      for (BiliApiClient.VideoStream stream : streams) {
         String rejection = rejectionReason(stream, ceilingRank, requestedSpecial);
         if (rejection == null) {
            accepted.add(preferCdn(stream));
         } else {
            diagnostics.add("q" + stream.quality() + "/" + stream.codecId() + ":" + rejection);
         }
      }

      Comparator<BiliApiClient.VideoStream> highestQualityFirst = (left, right) -> {
         int quality = Integer.compare(videoQualityRank(right.quality()), videoQualityRank(left.quality()));
         if (quality != 0) {
            return quality;
         } else {
            int width = Integer.compare(right.width(), left.width());
            return width != 0 ? width : left.baseUrl().compareTo(right.baseUrl());
         }
      };
      List<BiliApiClient.VideoStream> sortedAv1 = accepted.stream().filter(streamx -> streamx.codecId() == 13).sorted(highestQualityFirst).toList();
      List<BiliApiClient.VideoStream> sortedH264 = accepted.stream().filter(streamx -> streamx.codecId() == 7).sorted(highestQualityFirst).limit(1L).toList();
      List<BiliApiClient.VideoStream> av1;
      List<BiliApiClient.VideoStream> h264;
      List<BiliApiClient.VideoStream> softwareAv1;
      switch (codecPolicy) {
         case AUTO:
            av1 = selectAv1ProbeCandidates(sortedAv1);
            h264 = sortedH264;
            softwareAv1 = List.of();
            break;
         case PREFER_AV1:
            av1 = selectAv1ProbeCandidates(sortedAv1);
            h264 = sortedH264;
            softwareAv1 = List.of();
            break;
         case COMPATIBILITY:
            av1 = sortedAv1.stream().limit(1L).toList();
            h264 = sortedH264;
            softwareAv1 = List.of();
            if (sortedAv1.size() > av1.size()) {
               diagnostics.add("policy-compatibility:skipped-lower-av1-probes=" + (sortedAv1.size() - av1.size()));
            }
            break;
         case H264:
            av1 = List.of();
            h264 = sortedH264;
            softwareAv1 = List.of();
            if (!sortedAv1.isEmpty()) {
               diagnostics.add("policy-h264:excluded-av1=" + sortedAv1.size());
            }
            break;
         default:
            throw new IllegalStateException("Unhandled video codec policy " + codecPolicy);
      }

      BiliApiClient.VideoStreamPlan plan = new BiliApiClient.VideoStreamPlan(preferredQuality, codecPolicy, av1, h264, softwareAv1, diagnostics);
      if (plan.candidateOrder().isEmpty()) {
         throw new IllegalStateException("该视频在 codec policy=" + codecPolicy.serializedName() + " 下没有画质上限内且编码标识有效的 AV1/H.264 DASH 视频流: " + diagnostics);
      } else {
         return plan;
      }
   }

   private static List<BiliApiClient.VideoStream> selectAv1ProbeCandidates(List<BiliApiClient.VideoStream> sortedAv1) {
      if (sortedAv1.isEmpty()) {
         return List.of();
      } else {
         LinkedHashSet<BiliApiClient.VideoStream> probes = new LinkedHashSet<>();
         probes.add(sortedAv1.get(0));
         addHighestAtOrBelow(probes, sortedAv1, 120);
         addHighestAtOrBelow(probes, sortedAv1, 116);
         return List.copyOf(probes);
      }
   }

   private static void addHighestAtOrBelow(Set<BiliApiClient.VideoStream> result, List<BiliApiClient.VideoStream> sortedStreams, int qualityCeiling) {
      int ceilingRank = videoQualityRank(qualityCeiling);
      sortedStreams.stream().filter(stream -> videoQualityRank(stream.quality()) <= ceilingRank).findFirst().ifPresent(result::add);
   }

   private static String rejectionReason(BiliApiClient.VideoStream stream, int ceilingRank, boolean requestedSpecial) {
      if (stream.codecId() != 13 && stream.codecId() != 7) {
         return stream.codecId() == 12 ? "hevc-disabled" : "unsupported-codec";
      } else {
         String codecs = stream.codecs() == null ? "" : stream.codecs().trim().toLowerCase(Locale.ROOT);
         if (codecs.isEmpty()) {
            return "missing-codecs";
         } else if (stream.codecId() == 13 && !codecs.startsWith("av01.")) {
            return "codec-string-mismatch";
         } else if (stream.codecId() == 7 && !codecs.startsWith("avc1.")) {
            return "codec-string-mismatch";
         } else if (!requestedSpecial && isSpecialVideoQuality(stream.quality())) {
            return "special-quality-not-requested";
         } else if (videoQualityRank(stream.quality()) > ceilingRank) {
            return "above-quality-ceiling";
         } else {
            return stream.baseUrl() != null && !stream.baseUrl().isBlank() ? null : "missing-url";
         }
      }
   }

   private static BiliApiClient.VideoStream preferCdn(BiliApiClient.VideoStream stream) {
      List<String> ordered = BiliCdnSelector.orderCandidates(stream.cdnCandidates());
      String preferredUrl = BiliCdnSelector.selectPreferred(ordered);
      return new BiliApiClient.VideoStream(
         stream.quality(),
         stream.codecId(),
         stream.width(),
         stream.height(),
         stream.frameRate(),
         stream.codecs(),
         preferredUrl.isBlank() ? stream.baseUrl() : preferredUrl,
         stream.initStart(),
         stream.initEnd(),
         stream.indexStart(),
         stream.indexEnd(),
         ordered
      );
   }

   private static BiliApiClient.VideoStreamPlan registerVideoPlan(BiliApiClient.VideoStreamPlan plan) {
      for (BiliApiClient.VideoStream stream : plan.fallbackOrder()) {
         if (stream.hasSegmentBase()) {
            Fmp4NativeVideoDecoder.registerSegmentBase(stream.baseUrl(), stream.initStart(), stream.initEnd(), stream.indexStart(), stream.indexEnd());
         }

         CdnUrlFallbacks.registerAlternates(stream.cdnCandidates());
      }

      return plan;
   }

   private static long[] parseSegmentBaseRange(JsonObject stream, String key) {
      if (stream.has("segment_base") && !stream.get("segment_base").isJsonNull()) {
         JsonObject segmentBase = stream.getAsJsonObject("segment_base");
         if (segmentBase.has(key) && !segmentBase.get(key).isJsonNull()) {
            String raw = segmentBase.get(key).getAsString();
            int dash = raw.indexOf(45);
            if (dash > 0 && dash < raw.length() - 1) {
               try {
                  long start = Long.parseLong(raw.substring(0, dash).trim());
                  long end = Long.parseLong(raw.substring(dash + 1).trim());
                  return end >= start ? new long[]{start, end} : new long[]{-1L, -1L};
               } catch (NumberFormatException var9) {
                  return new long[]{-1L, -1L};
               }
            } else {
               return new long[]{-1L, -1L};
            }
         } else {
            return new long[]{-1L, -1L};
         }
      } else {
         return new long[]{-1L, -1L};
      }
   }

   public static boolean isSpecialVideoQuality(int quality) {
      return quality == 100 || quality == 125 || quality == 126 || quality == 129;
   }

   public static String qualityLabel(int quality) {
      return "q" + quality + "(" + switch (quality) {
         case 6 -> "240P 极速";
         case 16 -> "360P";
         case 32 -> "480P";
         case 64 -> "720P";
         case 74 -> "720P60";
         case 80 -> "1080P";
         case 100 -> "智能修复/特性";
         case 112 -> "1080P+";
         case 116 -> "1080P60";
         case 120 -> "4K";
         case 125 -> "HDR 真彩色/特性";
         case 126 -> "杜比视界/特性";
         case 127 -> "8K";
         case 129 -> "HDR Vivid/特性";
         default -> "unknown";
      } + ")";
   }

   public static String audioQualityLabel(int quality) {
      return switch (quality) {
         case 30216 -> "64K AAC";
         case 30232 -> "132K AAC";
         case 30250 -> "Dolby Atmos/EC-3";
         case 30251 -> "Hi-Res/FLAC";
         case 30280 -> "192K AAC";
         default -> "unknown";
      };
   }

   private static int videoQualityRank(int quality) {
      return switch (quality) {
         case 6 -> 10;
         case 16 -> 20;
         case 32 -> 30;
         case 64 -> 40;
         case 74 -> 45;
         case 80 -> 50;
         case 112 -> 60;
         case 116 -> 65;
         case 120 -> 70;
         case 127 -> 80;
         default -> isSpecialVideoQuality(quality) ? -1 : quality;
      };
   }

   public static String getBilingualSubtitleAsNetEaseLyric(BiliApiClient.VideoInfo info) throws Exception {
      return BiliSubtitleApi.getBilingualLyric(info, BiliApiClient.SubtitlePreference.HUMAN_ONLY);
   }

   public static String getBilingualSubtitleAsNetEaseLyric(BiliApiClient.VideoInfo info, boolean allowAi) throws Exception {
      return BiliSubtitleApi.getBilingualLyric(info, allowAi ? BiliApiClient.SubtitlePreference.HUMAN_OR_AI : BiliApiClient.SubtitlePreference.HUMAN_ONLY);
   }

   public static String getBilingualSubtitleAsNetEaseLyric(BiliApiClient.VideoInfo info, BiliApiClient.SubtitlePreference preference) throws Exception {
      return BiliSubtitleApi.getBilingualLyric(info, preference);
   }

   static List<BiliApiClient.SubtitleInfo> selectSubtitleCandidates(List<BiliApiClient.SubtitleInfo> all, BiliApiClient.SubtitlePreference preference) {
      return BiliSubtitleApi.selectCandidates(all, preference);
   }

   static List<BiliApiClient.SubtitleInfo> getAllSubtitles(BiliApiClient.VideoInfo info) throws Exception {
      return BiliSubtitleApi.getAll(info);
   }

   static <T> HttpResponse<T> sendApi(HttpClient client, HttpRequest request, BodyHandler<T> handler, String kind) throws Exception {
      return CancellableHttpRequestScope.sendOneBlocking(client, request, handler, HttpRequestCloseDiagnostics.global(), kind);
   }

   public static String buildPlaceholderNetEaseLyric(BiliApiClient.VideoInfo info, String note) {
      return BiliSubtitleApi.buildPlaceholder(info, note);
   }

   private static Logger logger() {
      return BiliApiClient.LoggerHolder.INSTANCE;
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }

   public static final class NoAudioStreamException extends Exception {
      public NoAudioStreamException(String message) {
         super(message);
      }
   }

   public record PlannedVideoCandidate(BiliApiClient.VideoStream stream, BiliApiClient.VideoDecodePreference decodePreference) {
      public PlannedVideoCandidate(BiliApiClient.VideoStream stream, BiliApiClient.VideoDecodePreference decodePreference) {
         Objects.requireNonNull(stream, "stream");
         Objects.requireNonNull(decodePreference, "decodePreference");
         this.stream = stream;
         this.decodePreference = decodePreference;
      }
   }

   public record SubtitleInfo(String lan, String url, boolean aiGenerated) {
      public SubtitleInfo(String lan, String url) {
         this(lan, url, BiliSubtitleApi.isAiLanguage(lan));
      }

      public boolean isAiGenerated() {
         return this.aiGenerated || BiliSubtitleApi.isAiLanguage(this.lan);
      }

      public boolean isJsonSubtitle() {
         return this.url != null && this.url.contains(".json");
      }

      public String normalizedUrl() {
         if (this.url == null) {
            return "";
         } else {
            return this.url.startsWith("//") ? "https:" + this.url : this.url;
         }
      }
   }

   public static enum SubtitlePreference {
      HUMAN_ONLY,
      HUMAN_OR_AI,
      AI_ONLY;
   }

   public static enum VideoCodecPolicy {
      AUTO("auto"),
      PREFER_AV1("prefer-av1"),
      COMPATIBILITY("compatibility"),
      H264("h264");

      private final String serializedName;

      private VideoCodecPolicy(String serializedName) {
         this.serializedName = serializedName;
      }

      public String serializedName() {
         return this.serializedName;
      }

      static BiliApiClient.VideoCodecPolicy parse(String raw) {
         String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);

         for (BiliApiClient.VideoCodecPolicy policy : values()) {
            if (policy.serializedName.equals(normalized)) {
               return policy;
            }
         }

         return AUTO;
      }
   }

   public static enum VideoDecodePreference {
      HARDWARE_REQUIRED,
      AUTO,
      SOFTWARE_ONLY;
   }

   public record VideoId(String kind, String value) {
      public static BiliApiClient.VideoId bvid(String value) {
         return new BiliApiClient.VideoId("bvid", value);
      }

      public static BiliApiClient.VideoId aid(String value) {
         return new BiliApiClient.VideoId("aid", value);
      }

      public boolean isBvid() {
         return "bvid".equals(this.kind);
      }

      public String asInputText() {
         return this.isBvid() ? this.value : "av" + this.value;
      }

      public void putViewParam(Map<String, String> params) {
         params.put(this.kind, this.value);
      }

      public void putPlayUrlParam(Map<String, String> params) {
         params.put(this.isBvid() ? "bvid" : "avid", this.value);
      }
   }

   public record VideoInfo(
      long aid, String title, List<String> staffNames, long cid, int duration, int page, int totalPages, String part, BiliApiClient.VideoId videoId
   ) {
      public String displayTitle() {
         if (this.totalPages <= 1) {
            return this.title;
         } else {
            String cleanPart = this.part == null ? "" : this.part.trim();
            return cleanPart.isEmpty() ? this.title + " - P" + this.page : this.title + " - P" + this.page + " " + cleanPart;
         }
      }
   }

   public record VideoSelection(BiliApiClient.VideoId videoId, int page) {
   }

   public record VideoStream(
      int quality,
      int codecId,
      int width,
      int height,
      String frameRate,
      String codecs,
      String baseUrl,
      long initStart,
      long initEnd,
      long indexStart,
      long indexEnd,
      List<String> cdnCandidates
   ) {
      public VideoStream(int quality, int codecId, int width, int height, String frameRate, String codecs, String baseUrl) {
         this(
            quality,
            codecId,
            width,
            height,
            frameRate,
            codecs,
            baseUrl,
            -1L,
            -1L,
            -1L,
            -1L,
            baseUrl != null && !baseUrl.isBlank() ? List.of(baseUrl) : List.of()
         );
      }

      public VideoStream(
         int quality,
         int codecId,
         int width,
         int height,
         String frameRate,
         String codecs,
         String baseUrl,
         long initStart,
         long initEnd,
         long indexStart,
         long indexEnd
      ) {
         this(
            quality,
            codecId,
            width,
            height,
            frameRate,
            codecs,
            baseUrl,
            initStart,
            initEnd,
            indexStart,
            indexEnd,
            baseUrl != null && !baseUrl.isBlank() ? List.of(baseUrl) : List.of()
         );
      }

      public boolean hasSegmentBase() {
         return this.initStart >= 0L && this.initEnd >= this.initStart && this.indexStart >= 0L && this.indexEnd >= this.indexStart;
      }

      public String codecName() {
         return switch (this.codecId) {
            case 7 -> "H.264";
            case 12 -> "HEVC";
            case 13 -> "AV1";
            default -> "codecId=" + this.codecId;
         };
      }

      public String displaySize() {
         return this.width > 0 && this.height > 0 ? this.width + "x" + this.height : "unknown";
      }

      public String qualityLabel() {
         return BiliApiClient.qualityLabel(this.quality);
      }
   }

   public record VideoStreamPlan(
      int requestedQualityCeiling,
      BiliApiClient.VideoCodecPolicy codecPolicy,
      List<BiliApiClient.VideoStream> av1Candidates,
      List<BiliApiClient.VideoStream> h264Candidates,
      List<BiliApiClient.VideoStream> softwareAv1Candidates,
      List<String> diagnostics
   ) {
      public VideoStreamPlan(
         int requestedQualityCeiling,
         BiliApiClient.VideoCodecPolicy codecPolicy,
         List<BiliApiClient.VideoStream> av1Candidates,
         List<BiliApiClient.VideoStream> h264Candidates,
         List<BiliApiClient.VideoStream> softwareAv1Candidates,
         List<String> diagnostics
      ) {
         Objects.requireNonNull(codecPolicy, "codecPolicy");
         av1Candidates = List.copyOf(av1Candidates);
         h264Candidates = List.copyOf(h264Candidates);
         softwareAv1Candidates = List.copyOf(softwareAv1Candidates);
         diagnostics = List.copyOf(diagnostics);
         this.requestedQualityCeiling = requestedQualityCeiling;
         this.codecPolicy = codecPolicy;
         this.av1Candidates = av1Candidates;
         this.h264Candidates = h264Candidates;
         this.softwareAv1Candidates = softwareAv1Candidates;
         this.diagnostics = diagnostics;
      }

      public VideoStreamPlan(
         int requestedQualityCeiling,
         List<BiliApiClient.VideoStream> av1Candidates,
         List<BiliApiClient.VideoStream> h264Candidates,
         List<BiliApiClient.VideoStream> softwareAv1Candidates,
         List<String> diagnostics
      ) {
         this(requestedQualityCeiling, BiliApiClient.VideoCodecPolicy.AUTO, av1Candidates, h264Candidates, softwareAv1Candidates, diagnostics);
      }

      public BiliApiClient.VideoStream preferred() {
         List<BiliApiClient.PlannedVideoCandidate> ordered = this.candidateOrder();
         if (!ordered.isEmpty()) {
            return ordered.get(0).stream();
         } else {
            throw new IllegalStateException("该视频没有可用的 AV1/H.264 DASH 视频流");
         }
      }

      public List<BiliApiClient.PlannedVideoCandidate> candidateOrder() {
         List<BiliApiClient.PlannedVideoCandidate> result = new ArrayList<>(
            this.av1Candidates.size() + this.h264Candidates.size() + this.softwareAv1Candidates.size()
         );
         switch (this.codecPolicy) {
            case AUTO:
            case COMPATIBILITY:
               addCandidates(result, this.av1Candidates, BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED);
               addCandidates(result, this.h264Candidates, BiliApiClient.VideoDecodePreference.AUTO);
               addCandidates(result, this.softwareAv1Candidates, BiliApiClient.VideoDecodePreference.SOFTWARE_ONLY);
               break;
            case PREFER_AV1:
               addCandidates(result, this.av1Candidates, BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED);
               addCandidates(result, this.softwareAv1Candidates, BiliApiClient.VideoDecodePreference.SOFTWARE_ONLY);
               addCandidates(result, this.h264Candidates, BiliApiClient.VideoDecodePreference.AUTO);
               break;
            case H264:
               addCandidates(result, this.h264Candidates, BiliApiClient.VideoDecodePreference.AUTO);
         }

         return List.copyOf(result);
      }

      public List<BiliApiClient.VideoStream> fallbackOrder() {
         return this.candidateOrder().stream().map(candidate -> Objects.requireNonNull(candidate, "candidate").stream()).toList();
      }

      private static void addCandidates(
         List<BiliApiClient.PlannedVideoCandidate> target, List<BiliApiClient.VideoStream> streams, BiliApiClient.VideoDecodePreference preference
      ) {
         streams.forEach(stream -> target.add(new BiliApiClient.PlannedVideoCandidate(stream, preference)));
      }
   }
}
