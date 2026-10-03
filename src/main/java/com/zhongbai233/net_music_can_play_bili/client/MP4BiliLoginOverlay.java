package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.bili.BiliLoginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

public final class MP4BiliLoginOverlay {
   private static BiliLoginManager loginManager;
   private static volatile BufferedImage qrImage;
   private static volatile String statusText = "";
   private static volatile boolean visible;
   private static volatile boolean done;
   private static int pollTick;
   private static int closeTick = -1;
   private static volatile int version;
   private static volatile int generation;

   private MP4BiliLoginOverlay() {
   }

   public static boolean visible() {
      return visible;
   }

   public static BufferedImage qrImage() {
      return qrImage;
   }

   public static String statusText() {
      return statusText;
   }

   public static int version() {
      return version;
   }

   public static void toggle() {
      if (visible) {
         close();
      } else {
         open();
      }
   }

   public static void open() {
      if (loginManager != null) {
         loginManager.close();
      }

      loginManager = new BiliLoginManager();
      BiliLoginManager manager = loginManager;
      int currentGeneration = ++generation;
      qrImage = null;
      statusText = "正在生成二维码...";
      visible = true;
      done = false;
      pollTick = 0;
      closeTick = -1;
      markChanged();
      manager.generate().thenAccept(state -> {
         if (isCurrent(manager, currentGeneration)) {
            if (state == BiliLoginManager.State.PENDING) {
               statusText = "请用 B站APP 扫描二维码";
               markChanged();
               loadQrImage(manager, currentGeneration, manager.getQrUrl());
            } else {
               statusText = "二维码生成失败，请重试";
               done = true;
               markChanged();
            }
         }
      });
   }

   public static void close() {
      BiliLoginManager manager = loginManager;
      visible = false;
      generation++;
      done = false;
      closeTick = -1;
      pollTick = 0;
      qrImage = null;
      loginManager = null;
      if (manager != null) {
         manager.close();
      }

      statusText = "";
      markChanged();
   }

   public static void tick() {
      if (visible && loginManager != null) {
         if (done) {
            if (closeTick >= 0) {
               closeTick--;
               if (closeTick <= 0) {
                  close();
               } else {
                  markChanged();
               }
            }
         } else {
            pollTick++;
            if (pollTick % 40 == 0) {
               BiliLoginManager manager = loginManager;
               int currentGeneration = generation;
               manager.poll().thenAccept(state -> {
                  if (isCurrent(manager, currentGeneration)) {
                     switch (state) {
                        case PENDING:
                           statusText = "请用 B站APP 扫描二维码";
                           break;
                        case SCANNED:
                           statusText = "已扫描，请在手机上确认登录";
                           break;
                        case SUCCESS:
                           statusText = "登录成功！";
                           done = true;
                           closeTick = 40;
                           break;
                        case EXPIRED:
                           statusText = "二维码已过期，点 B站 重试";
                           done = true;
                           break;
                        case FAILED:
                           statusText = "登录失败，点 B站 重试";
                           done = true;
                     }

                     markChanged();
                  }
               });
            }
         }
      }
   }

   private static void loadQrImage(BiliLoginManager manager, int currentGeneration, String qrContentUrl) {
      if (qrContentUrl != null && !qrContentUrl.isBlank()) {
         manager.loadQrImage(qrContentUrl).thenApply(bytes -> {
            if (bytes == null) {
               return null;
            } else {
               try {
                  BufferedImage var2x;
                  try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                     var2x = ImageIO.read(in);
                  }

                  return var2x;
               } catch (Exception var6) {
                  return null;
               }
            }
         }).thenAccept(image -> {
            if (isCurrent(manager, currentGeneration)) {
               if (image != null) {
                  qrImage = image;
                  markChanged();
               } else {
                  statusText = "二维码图片加载失败，请重试";
                  done = true;
                  markChanged();
               }
            }
         });
      } else {
         statusText = "二维码地址为空，请重试";
         done = true;
         markChanged();
      }
   }

   private static void markChanged() {
      version++;
   }

   private static boolean isCurrent(BiliLoginManager manager, int currentGeneration) {
      return visible && loginManager == manager && generation == currentGeneration;
   }
}
