package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

public final class BiliLoginManager implements AutoCloseable {
   private static final URI DEFAULT_GENERATE_URI = URI.create("https://passport.bilibili.com/x/passport-login/web/qrcode/generate");
   private static final String DEFAULT_POLL_ENDPOINT = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
   private static final String DEFAULT_QR_IMAGE_ENDPOINT = "https://api.qrserver.com/v1/create-qr-code/";
   private final CancellableHttpRequestScope requests = new CancellableHttpRequestScope(HttpRequestCloseDiagnostics.global());
   private final HttpClient httpClient;
   private final URI generateUri;
   private final String pollEndpoint;
   private final String qrImageEndpoint;
   private final boolean applyProductionHeaders;
   private volatile String qrcodeKey;
   private volatile String qrUrl;
   private CompletableFuture<BiliLoginManager.State> generateFuture;
   private CompletableFuture<BiliLoginManager.State> pollFuture;

   public BiliLoginManager() {
      this(
         BiliWbiSigner.HTTP,
         DEFAULT_GENERATE_URI,
         "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
         "https://api.qrserver.com/v1/create-qr-code/",
         true
      );
   }

   BiliLoginManager(HttpClient httpClient, URI generateUri, String pollEndpoint, String qrImageEndpoint) {
      this(httpClient, generateUri, pollEndpoint, qrImageEndpoint, false);
   }

   private BiliLoginManager(HttpClient httpClient, URI generateUri, String pollEndpoint, String qrImageEndpoint, boolean applyProductionHeaders) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      this.generateUri = Objects.requireNonNull(generateUri, "generateUri");
      this.pollEndpoint = requireEndpoint(pollEndpoint, "pollEndpoint");
      this.qrImageEndpoint = requireEndpoint(qrImageEndpoint, "qrImageEndpoint");
      this.applyProductionHeaders = applyProductionHeaders;
   }

   public String getQrUrl() {
      return this.qrUrl;
   }

   public String getQrcodeKey() {
      return this.qrcodeKey;
   }

   public synchronized CompletableFuture<BiliLoginManager.State> generate() {
      if (this.requests.isClosed()) {
         return CompletableFuture.completedFuture(BiliLoginManager.State.FAILED);
      } else if (this.generateFuture != null && !this.generateFuture.isDone()) {
         return this.generateFuture;
      } else {
         try {
            Builder builder = HttpRequest.newBuilder(this.generateUri).timeout(Duration.ofSeconds(10L)).GET();
            this.applyHeaders(builder);
            HttpRequest req = builder.build();
            this.generateFuture = this.requests
               .sendAsync(this.httpClient, req, BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-login-generate")
               .handle((resp, errorx) -> this.parseGeneratedResponse((HttpResponse<String>)resp, errorx));
            return this.generateFuture;
         } catch (RuntimeException var3) {
            logger().error("生成二维码请求启动异常", var3);
            return CompletableFuture.completedFuture(BiliLoginManager.State.FAILED);
         }
      }
   }

   private BiliLoginManager.State parseGeneratedResponse(HttpResponse<String> resp, Throwable error) {
      if (error != null) {
         if (!isCancellation(error)) {
            logger().error("生成二维码异常", error);
         }

         return BiliLoginManager.State.FAILED;
      } else {
         try {
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            int code = root.get("code").getAsInt();
            if (code != 0) {
               logger().error("生成二维码失败: code={}", code);
               return BiliLoginManager.State.FAILED;
            } else {
               JsonObject data = root.getAsJsonObject("data");
               this.qrcodeKey = data.get("qrcode_key").getAsString();
               this.qrUrl = data.get("url").getAsString();
               return BiliLoginManager.State.PENDING;
            }
         } catch (Exception var6) {
            logger().error("生成二维码响应解析异常", var6);
            return BiliLoginManager.State.FAILED;
         }
      }
   }

   public synchronized CompletableFuture<BiliLoginManager.State> poll() {
      if (this.requests.isClosed() || this.qrcodeKey == null || this.qrcodeKey.isBlank()) {
         return CompletableFuture.completedFuture(BiliLoginManager.State.FAILED);
      } else if (this.pollFuture != null && !this.pollFuture.isDone()) {
         return this.pollFuture;
      } else {
         try {
            String url = appendQuery(this.pollEndpoint, "qrcode_key=" + URLEncoder.encode(this.qrcodeKey, StandardCharsets.UTF_8));
            Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10L)).GET();
            this.applyHeaders(builder);
            HttpRequest req = builder.build();
            this.pollFuture = this.requests
               .sendAsync(this.httpClient, req, BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-login-poll")
               .handle((resp, errorx) -> this.parsePollResponse((HttpResponse<String>)resp, errorx));
            return this.pollFuture;
         } catch (RuntimeException var4) {
            logger().error("轮询登录请求启动异常", var4);
            return CompletableFuture.completedFuture(BiliLoginManager.State.FAILED);
         }
      }
   }

   private BiliLoginManager.State parsePollResponse(HttpResponse<String> resp, Throwable error) {
      if (error != null) {
         if (!isCancellation(error)) {
            logger().error("轮询登录状态异常", error);
         }

         return this.requests.isClosed() ? BiliLoginManager.State.FAILED : BiliLoginManager.State.PENDING;
      } else {
         try {
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            int code = root.get("code").getAsInt();
            int dataCode = root.has("data") && !root.get("data").isJsonNull() ? root.getAsJsonObject("data").get("code").getAsInt() : -1;
            switch (dataCode) {
               case 0:
                  HttpHeaders headers = resp.headers();
                  Map<String, String> cookiePairs = new LinkedHashMap<>();
                  String sessdata = "";

                  for (String setCookie : headers.allValues("Set-Cookie")) {
                     BiliLoginManager.CookiePair pair = parseCookiePair(setCookie);
                     if (pair != null) {
                        cookiePairs.put(pair.name(), pair.value());
                        if ("SESSDATA".equals(pair.name())) {
                           sessdata = pair.value();
                        }
                     }
                  }

                  if (!sessdata.isBlank()) {
                     BiliApiClient.sessdata = sessdata;
                     BiliApiClient.webCookie = buildCookieHeader(cookiePairs);
                     BiliConfig.save();
                     logger().info("B站登录成功, 已保存 Web Cookie 字段数={}", cookiePairs.size());
                     return BiliLoginManager.State.SUCCESS;
                  }

                  logger().warn("登录成功但未找到 SESSDATA cookie, Set-Cookie 字段数={}", cookiePairs.size());
                  return BiliLoginManager.State.FAILED;
               case 86038:
                  return BiliLoginManager.State.EXPIRED;
               case 86090:
                  return BiliLoginManager.State.SCANNED;
               case 86101:
                  return BiliLoginManager.State.PENDING;
               default:
                  logger().warn("未知轮询状态: dataCode={}, code={}", dataCode, code);
                  return BiliLoginManager.State.PENDING;
            }
         } catch (Exception var12) {
            logger().error("轮询登录响应解析异常", var12);
            return BiliLoginManager.State.PENDING;
         }
      }
   }

   public CompletableFuture<byte[]> loadQrImage(String qrContentUrl) {
      if (!this.requests.isClosed() && qrContentUrl != null && !qrContentUrl.isBlank()) {
         String encodedUrl = URLEncoder.encode(qrContentUrl, StandardCharsets.UTF_8);
         String qrImageUrl = appendQuery(this.qrImageEndpoint, "size=180x180&data=" + encodedUrl);
         HttpRequest request = HttpRequest.newBuilder(URI.create(qrImageUrl))
            .header("User-Agent", "Mozilla/5.0")
            .timeout(Duration.ofSeconds(10L))
            .GET()
            .build();
         return this.requests.sendAsync(this.httpClient, request, BodyHandlers.ofByteArray(), "bili-login-qr-image").handle((response, error) -> {
            if (error != null) {
               if (!isCancellation(error)) {
                  logger().error("加载二维码图片异常", error);
               }

               return null;
            } else {
               return response.statusCode() >= 200 && response.statusCode() < 300 ? response.body() : null;
            }
         });
      } else {
         return CompletableFuture.completedFuture(null);
      }
   }

   @Override
   public void close() {
      this.requests.close();
   }

   int activeRequestCount() {
      return this.requests.activeRequests();
   }

   private static boolean isCancellation(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof CancellationException) {
            return true;
         }
      }

      return false;
   }

   private static String requireEndpoint(String endpoint, String name) {
      if (endpoint != null && !endpoint.isBlank()) {
         return endpoint;
      } else {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   private static String appendQuery(String endpoint, String query) {
      return endpoint + (endpoint.contains("?") ? "&" : "?") + query;
   }

   private void applyHeaders(Builder builder) {
      if (this.applyProductionHeaders) {
         BiliRequestHeaders.applyWebApiHeaders(builder);
      }
   }

   private static Logger logger() {
      return BiliLoginManager.LoggerHolder.INSTANCE;
   }

   private static BiliLoginManager.CookiePair parseCookiePair(String setCookie) {
      if (setCookie != null && !setCookie.isBlank()) {
         int semicolon = setCookie.indexOf(59);
         String pair = semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
         int equals = pair.indexOf(61);
         if (equals > 0 && equals < pair.length() - 1) {
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            return !name.isBlank() && !value.isBlank() ? new BiliLoginManager.CookiePair(name, value) : null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static String buildCookieHeader(Map<String, String> cookiePairs) {
      StringBuilder header = new StringBuilder();

      for (Entry<String, String> entry : cookiePairs.entrySet()) {
         if (header.length() > 0) {
            header.append("; ");
         }

         header.append(entry.getKey()).append('=').append(entry.getValue());
      }

      return header.toString();
   }

   private record CookiePair(String name, String value) {
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }

   public static enum State {
      PENDING,
      SCANNED,
      SUCCESS,
      EXPIRED,
      FAILED;
   }
}
