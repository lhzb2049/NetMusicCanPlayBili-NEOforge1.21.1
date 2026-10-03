package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.net.URL;
import java.net.http.HttpRequest.Builder;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public final class BiliRequestHeaders {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0";
   private static final String[] DESKTOP_USER_AGENT_PRESETS = new String[]{
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
   };
   public static final String WEB_REFERER = "https://www.bilibili.com/";
   public static final String WEB_ORIGIN = "https://www.bilibili.com";
   public static final String LIVE_REFERER = "https://live.bilibili.com/";
   public static final String LIVE_ORIGIN = "https://live.bilibili.com";
   public static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
   private static final AtomicInteger UA_INDEX = new AtomicInteger(ThreadLocalRandom.current().nextInt(DESKTOP_USER_AGENT_PRESETS.length));
   private static final AtomicInteger BILI_CDN_403_COUNT = new AtomicInteger();
   private static final int UA_SWITCH_403_THRESHOLD = Math.max(
      1, NcpbSystemProperties.intValue("ncpb.bili.user_agent.switch_403_threshold", "ncpb.ncpb.bili.user_agent.switch_403_threshold", 3)
   );

   private BiliRequestHeaders() {
   }

   public static String userAgent() {
      String override = BiliConfig.userAgent;
      if (override != null && !override.isBlank()) {
         return override.trim();
      } else if (!BiliConfig.rotateUserAgent) {
         return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0";
      } else {
         int index = Math.floorMod(UA_INDEX.get(), DESKTOP_USER_AGENT_PRESETS.length);
         return DESKTOP_USER_AGENT_PRESETS[index];
      }
   }

   public static void recordBiliCdnResponse(URL url, int statusCode) {
      if (BiliConfig.rotateUserAgent && isBiliHost(url)) {
         if (statusCode == 403) {
            int failures = BILI_CDN_403_COUNT.incrementAndGet();
            if (failures >= UA_SWITCH_403_THRESHOLD) {
               int next = UA_INDEX.updateAndGet(value -> Math.floorMod(value + 1, DESKTOP_USER_AGENT_PRESETS.length));
               BILI_CDN_403_COUNT.set(0);
               LOGGER.warn(
                  "Bilibili CDN returned HTTP 403 {} times; switching User-Agent preset to {}/{}: {}",
                  new Object[]{failures, next + 1, DESKTOP_USER_AGENT_PRESETS.length, DESKTOP_USER_AGENT_PRESETS[next]}
               );
            }
         } else if (statusCode == 200 || statusCode == 206) {
            BILI_CDN_403_COUNT.set(0);
         }
      }
   }

   public static Builder applyWebApiHeaders(Builder builder) {
      builder.header("User-Agent", userAgent()).header("Referer", "https://www.bilibili.com/").header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
      addCookie(builder);
      return builder;
   }

   public static Builder applyBiliCdnHeaders(Builder builder, URL url) {
      builder.header("User-Agent", userAgent())
         .header("Referer", refererFor(url))
         .header("Origin", originFor(url))
         .header("Accept", "*/*")
         .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
      if (isBiliHost(url)) {
         addCookie(builder);
      }

      return builder;
   }

   public static Builder applyLiveHeaders(Builder builder, URL url) {
      builder.header("User-Agent", userAgent())
         .header("Referer", "https://live.bilibili.com/")
         .header("Origin", "https://live.bilibili.com")
         .header("Accept", "*/*")
         .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
      if (url == null || isBiliHost(url)) {
         addCookie(builder);
      }

      return builder;
   }

   public static void addCookie(Builder builder) {
      String cookie = cookieHeader();
      if (!cookie.isBlank()) {
         builder.header("Cookie", cookie);
      }
   }

   public static String cookieHeader() {
      String fullCookie = BiliApiClient.webCookie;
      if (fullCookie != null && !fullCookie.isBlank()) {
         return fullCookie.trim();
      } else {
         String sessdata = BiliApiClient.sessdata;
         return sessdata != null && !sessdata.isBlank() ? "SESSDATA=" + sessdata.trim() : "";
      }
   }

   public static String refererFor(URL url) {
      return isBiliHost(url) ? "https://www.bilibili.com/" : "https://" + safeHost(url) + "/";
   }

   public static String originFor(URL url) {
      return isBiliHost(url) ? "https://www.bilibili.com" : "https://" + safeHost(url);
   }

   public static boolean isBiliHost(URL url) {
      String host = url.getHost();
      if (host == null) {
         return false;
      } else {
         String lower = host.toLowerCase(Locale.ROOT);
         return lower.contains("bilibili") || lower.contains("bilivideo") || lower.contains("hdslb") || lower.contains("mcdn");
      }
   }

   public static String safeHost(URL url) {
      String host = url.getHost();
      return host != null && !host.isBlank() ? host : "localhost";
   }
}
