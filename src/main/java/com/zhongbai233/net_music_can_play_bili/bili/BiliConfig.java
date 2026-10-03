package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class BiliConfig {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili.json");
   public static volatile boolean dolbyEnabled = true;
   public static volatile boolean dolbyJocEnabled = true;
   public static volatile int dolbyMaxObjectSources = 64;
   public static volatile double stereoCrossfeed = 0.16;
   public static volatile String userAgent = BiliApiProperties.initialUserAgent();
   public static volatile boolean rotateUserAgent = BiliApiProperties.rotateUserAgent();

   private BiliConfig() {
   }

   public static void load() {
      try {
         if (Files.exists(CONFIG_FILE)) {
            String content = Files.readString(CONFIG_FILE);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            String sessdata = root.has("sessdata") ? root.get("sessdata").getAsString() : "";
            if (!sessdata.isBlank()) {
               BiliApiClient.sessdata = sessdata;
               LOGGER.info("已加载 B站登录状态");
            }

            String webCookie = root.has("webCookie") ? root.get("webCookie").getAsString() : "";
            if (!webCookie.isBlank()) {
               BiliApiClient.webCookie = webCookie;
            } else if (!sessdata.isBlank()) {
               BiliApiClient.webCookie = "SESSDATA=" + sessdata;
            }

            if (root.has("dolbyEnabled")) {
               dolbyEnabled = root.get("dolbyEnabled").getAsBoolean();
            }

            if (root.has("dolbyJocEnabled")) {
               dolbyJocEnabled = root.get("dolbyJocEnabled").getAsBoolean();
            }

            if (root.has("dolbyMaxObjectSources")) {
               dolbyMaxObjectSources = root.get("dolbyMaxObjectSources").getAsInt();
            }

            if (root.has("stereoCrossfeed")) {
               stereoCrossfeed = root.get("stereoCrossfeed").getAsDouble();
            }

            if (root.has("userAgent")) {
               String configuredUserAgent = root.get("userAgent").getAsString();
               if (!configuredUserAgent.isBlank()) {
                  userAgent = configuredUserAgent;
               }
            }

            if (root.has("rotateUserAgent")) {
               rotateUserAgent = root.get("rotateUserAgent").getAsBoolean();
            }

            BiliCdnSelector.load(root);
         }
      } catch (Exception var5) {
         LOGGER.warn("加载 B站配置失败", var5);
      }
   }

   public static void save() {
      try {
         JsonObject root = new JsonObject();
         root.addProperty("sessdata", BiliApiClient.sessdata != null ? BiliApiClient.sessdata : "");
         root.addProperty("webCookie", BiliApiClient.webCookie != null ? BiliApiClient.webCookie : "");
         root.addProperty("dolbyEnabled", dolbyEnabled);
         root.addProperty("dolbyJocEnabled", dolbyJocEnabled);
         root.addProperty("dolbyMaxObjectSources", dolbyMaxObjectSources());
         root.addProperty("stereoCrossfeed", stereoCrossfeed);
         root.addProperty("userAgent", userAgent != null ? userAgent : "");
         root.addProperty("rotateUserAgent", rotateUserAgent);
         BiliCdnSelector.save(root);
         Files.createDirectories(CONFIG_FILE.getParent());
         Files.writeString(CONFIG_FILE, root.toString());
         LOGGER.info("B站登录状态已保存");
      } catch (IOException var1) {
         LOGGER.warn("保存 B站配置失败", var1);
      }
   }

   public static float stereoCrossfeedAmount() {
      return (float)Math.max(0.0, Math.min(0.45, stereoCrossfeed));
   }

   public static int dolbyMaxObjectSources() {
      return Math.max(0, Math.min(64, dolbyMaxObjectSources));
   }
}
