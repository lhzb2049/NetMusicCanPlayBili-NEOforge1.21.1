package com.zhongbai233.net_music_can_play_bili.client.media;

import net.minecraft.client.Minecraft;

/**
 * 「这个媒体源算不算有画面」的唯一判定处，供四个消费端共用：
 * 中控台（{@code ControlConsoleRenderer}）、全息眼镜（{@code HolographicGlassesWorldScreenRenderer}）、
 * MP4 物品与 Pad（{@code MP4HandheldVideoClient} / 各 GUI）。
 *
 * <p>为什么要有它：这些消费端原先各自判 {@code BiliVideoStreamResolver.selectionOrNull(...) != null} 或
 * {@code isStoredVideoSelection(...)} 来决定「有没有画面」。本地视频与本地图片在那些判定里一律为 false，
 * 于是本地源在这四个地方都会被当成「纯音乐」。这里把「远端视频由调用方判、本地源由本类判」收敛成一处，
 * 且**纯逻辑、可离线跑矩阵验证**（离线入口是带 {@code gameDirectory} 参数的重载）。
 *
 * <p>本地图片也算「有画面」：它是海报式静态画面，与播放状态无关（阶段 1 投影仪就是这么定的）。
 */
public final class LocalMediaAdmission {
   private LocalMediaAdmission() {
   }

   /** 本地源的种类（远端 http/B 站视频由调用方另行判定）。 */
   public enum LocalKind {
      NONE,
      LOCAL_VIDEO,
      LOCAL_IMAGE;

      public boolean hasPicture() {
         return this != NONE;
      }
   }

   /** 纯逻辑入口：判定这串地址是不是「允许读取的本地视频/图片」。 */
   public static LocalKind localKind(String rawUrl, String gameDirectory) {
      if (rawUrl == null || rawUrl.isBlank()) {
         return LocalKind.NONE;
      }

      MediaSourceClassifier.Classification classification = MediaSourceClassifier.classifyThrottled(rawUrl, gameDirectory);
      if (!classification.allowed()) {
         return LocalKind.NONE;
      }

      SourceKind kind = classification.kind();
      if (kind == SourceKind.LOCAL_VIDEO) {
         // 本地视频还受阶段 2 的总开关约束：关掉后行为与阶段 0 完全一致
         return LocalVideoProperties.enabled() ? LocalKind.LOCAL_VIDEO : LocalKind.NONE;
      }

      return kind == SourceKind.LOCAL_IMAGE ? LocalKind.LOCAL_IMAGE : LocalKind.NONE;
   }

   /** 客户端便利重载（逐帧路径用；判定本身带 5 秒 TTL 记忆化，不会每帧做文件系统 I/O）。 */
   public static LocalKind localKind(String rawUrl) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.gameDirectory == null) {
         return LocalKind.NONE;
      }

      return localKind(rawUrl, minecraft.gameDirectory.getAbsolutePath());
   }

   /** 带判定的完整分类结果（需要路径/原因时用）。 */
   public static MediaSourceClassifier.Classification classification(String rawUrl, String gameDirectory) {
      return MediaSourceClassifier.classifyThrottled(rawUrl, gameDirectory);
   }

   /** 视频消费端是否应进入「有画面」状态：远端视频、本地视频、本地图片都算。 */
   public static boolean videoExpected(boolean remoteVideo, String rawUrl, String gameDirectory) {
      return remoteVideo || localKind(rawUrl, gameDirectory).hasPicture();
   }

   public static boolean videoExpected(boolean remoteVideo, String rawUrl) {
      return remoteVideo || localKind(rawUrl).hasPicture();
   }

   /** 是不是本地静态图片（需要按「海报」处理：与播放状态无关）。 */
   public static boolean staticImage(String rawUrl, String gameDirectory) {
      return localKind(rawUrl, gameDirectory) == LocalKind.LOCAL_IMAGE;
   }

   public static boolean staticImage(String rawUrl) {
      return localKind(rawUrl) == LocalKind.LOCAL_IMAGE;
   }
}
