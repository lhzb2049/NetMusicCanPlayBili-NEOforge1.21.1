package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.slf4j.Logger;

final class MP4OffscreenGuiRenderer implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SCALE = MP4HandheldMediaProfile.SCREEN.offscreenScale();
   private static final int WIDTH = MP4GuiTexture.WIDTH;
   private static final int HEIGHT = MP4GuiTexture.HEIGHT;
   private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
   private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
   private final ResourceLocation textureId;
   private TextureTarget target;
   private RenderTargetTexture texture;
   private BufferSource guiBuffer;
   private boolean registered;
   private boolean failed;
   private boolean loggedReady;
   private int renderTicks;
   private static final int[] PLAY_TRIANGLE_ROWS = new int[]{1, 3, 5, 7, 9, 11, 9, 7, 5, 3, 1};

   MP4OffscreenGuiRenderer(String textureKey) {
      String safeKey = textureKey != null && !textureKey.isBlank() ? textureKey.toLowerCase(Locale.ROOT) : "fallback";
      this.textureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/mp4_gui_offscreen_" + safeKey);
   }

   ResourceLocation textureId(UUID deviceId) {
      this.request(deviceId);
      return this.textureId;
   }

   void request(UUID deviceId) {
      if (!this.failed) {
         try {
            this.ensureResources();
         } catch (RuntimeException var3) {
            this.failed = true;
            this.close();
            LOGGER.warn("MP4 离屏 GUI 初始化失败: {}", this.textureId, var3);
         }
      }
   }

   void renderFrameStart(UUID deviceId) {
      if (!this.failed) {
         try {
            this.ensureResources();
            MP4HandheldVideoClient.update(deviceId);
            this.render(deviceId);
            if (!this.loggedReady) {
               this.loggedReady = true;
               LOGGER.info(
                  "MP4 离屏 GUI 已启用: texture={} target={}x{} logical={}x{} scale={}",
                  new Object[]{this.textureId, TARGET_WIDTH, TARGET_HEIGHT, WIDTH, HEIGHT, Math.max(1, SCALE)}
               );
            }
         } catch (RuntimeException var3) {
            this.failed = true;
            this.close();
            LOGGER.warn("MP4 离屏 GUI 渲染失败: {}", this.textureId, var3);
         }
      }
   }

   private void ensureResources() {
      if (this.target == null) {
         this.target = new TextureTarget(TARGET_WIDTH, TARGET_HEIGHT, true, Minecraft.ON_OSX);
         this.target.setFilterMode(9729);
         this.texture = new RenderTargetTexture(this.target);
         Minecraft minecraft = Minecraft.getInstance();
         minecraft.getTextureManager().register(this.textureId, this.texture);
         this.registered = true;
         this.guiBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(4096));
      }
   }

   private void render(UUID deviceId) {
      Minecraft minecraft = Minecraft.getInstance();
      GuiGraphics graphics = new GuiGraphics(minecraft, this.guiBuffer);
      this.drawGui(graphics, MP4GuiViewState.capture(deviceId));
      RenderSystem.backupProjectionMatrix();
      RenderSystem.getModelViewStack().pushMatrix();
      RenderSystem.getModelViewStack().identity();
      RenderSystem.applyModelViewMatrix();
      RenderSystem.enableBlend();
      this.target.bindWrite(true);
      RenderSystem.enableScissor(0, 0, TARGET_WIDTH, TARGET_HEIGHT);
      RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
      RenderSystem.clear(16640, Minecraft.ON_OSX);
      Matrix4f projection = new Matrix4f().setOrtho(0.0F, TARGET_WIDTH, TARGET_HEIGHT, 0.0F, 1000.0F, 3000.0F);
      RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

      try {
         graphics.flush();
         this.guiBuffer.endBatch();
         this.texture.refreshView();
      } finally {
         RenderSystem.disableScissor();
         this.target.unbindWrite();
         minecraft.getMainRenderTarget().bindWrite(true);
         RenderSystem.restoreProjectionMatrix();
         RenderSystem.getModelViewStack().popMatrix();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.disableBlend();
      }
   }

   private void drawGui(GuiGraphics g, MP4GuiViewState view) {
      this.renderTicks = view.ticks();
      if (!view.transparentLandscapeVideoOverlay()) {
         this.fill(g, 0, 0, WIDTH, HEIGHT, -16448249);
      }

      if (view.landscape()) {
         this.withLandscape(g, () -> this.drawLandscape(g, view));
      } else {
         this.drawPortrait(g, view);
      }

      if (!view.transparentLandscapeVideoOverlay()) {
         this.drawDeviceFrame(g);
      }
   }

   private void drawPortrait(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 0, 0, WIDTH, HEIGHT, -16448249);
      this.fill(g, 2, 2, WIDTH - 4, HEIGHT - 4, -15592422);
      this.fill(g, 7, 7, WIDTH - 14, HEIGHT - 14, -15263198);
      this.text(g, 28, 27, "NET MUSIC", -4667938, true);
      this.text(g, 194, 27, this.gameTimeLabel(), -4667938, true);
      this.text(g, 25, 45, "MP4 队列", -7549953, false);
      this.text(g, 83, 45, view.playing() ? "播放中" : "已暂停", view.playing() ? -9568336 : -15251, false);
      this.fill(g, 30, 58, 196, 184, -13946816);
      this.fill(g, 35, 63, 186, 174, -14670284);
      if (view.lyricsEnabled()) {
         this.drawLyricsPanel(g, view, 39, 67, 178, 166);
      } else {
         this.drawAlbumArt(g, 39, 67, 178, 166);
      }

      this.centeredMarqueeText(g, 128, 254, 184, view.songTitle(), -1379585, false);
      this.centeredText(g, 128, 272, view.songSubtitle(), -7629654, true);
      this.progress(g, 28, 295, 200, 8, view.mediaProgress(), -13486006, -9123841);
      this.text(g, 29, 312, formatPlaybackTime(view.elapsedMillis()), -8944739, true);
      this.text(g, 198, 312, formatPlaybackTime(view.durationMillis()), -8944739, true);
      this.prevButton(g, 34, 333, 42, 42, -14933456, -4466433);
      this.playPauseButton(g, 101, 325, 54, 54, view.playing() ? -9381633 : -11666, -16314344, view.playing());
      this.nextButton(g, 180, 333, 42, 42, -14933456, -4466433);
      this.text(g, 26, 389, "VOL", -8417880, true);
      this.progress(g, 64, 393, 154, 6, view.volume(), -13486006, -8454204);
      this.smallToggle(g, 20, 411, 42, 14, view.biliLoginActive() ? -9744853 : -14670283, "B站");
      this.smallToggle(g, 68, 411, 58, 14, view.playbackModeActive() ? -13215147 : -14670283, view.playbackModeLabel());
      this.smallToggle(g, 136, 411, 58, 14, view.playlistOpen() ? -14464663 : -14670283, "列表");
      this.smallToggle(g, 203, 411, 32, 14, view.lyricsEnabled() ? -11717272 : -14670283, "词");
      if (view.playlistOpen()) {
         this.drawPlaylist(g, view);
      }

      if (view.subtitleMenuOpen()) {
         this.drawSubtitleMenu(g, view);
      }

      if (view.qualityMenuOpen()) {
         this.drawQualityMenu(g, view);
      }

      if (view.biliLoginVisible()) {
         this.drawBiliLoginOverlay(g, view);
      }

      this.drawHover(g, view, false);
   }

   private void drawLandscape(GuiGraphics g, MP4GuiViewState view) {
      if (view.hasVideoFrame()) {
         if (view.controlsVisible()) {
            this.fill(g, 0, 0, 448, 10, -1727723764);
            this.fill(g, 0, 246, 448, 10, -1727723764);
            this.fill(g, 0, 10, 10, 236, -1727723764);
            this.fill(g, 438, 10, 10, 236, -1727723764);
            this.outline(g, 7, 7, 434, 242, 1713844288);
         }
      } else {
         this.fill(g, 0, 0, 448, 256, -16447732);
         this.fill(g, 7, 7, 434, 242, -16315630);
         this.fill(g, 10, 10, 428, 236, -16314081);
         if (view.audioOnly()) {
            this.centeredText(g, 224, 114, "纯音乐", -857017601, false);
            this.centeredText(g, 224, 134, "当前音源没有视频画面", -1713574913, true);
         } else {
            this.centeredText(g, 224, 122, view.videoEnabled() ? view.videoStatusText() : "视频线路已关闭", -855638017, true);
         }
      }

      if (!view.videoSubtitle().isBlank() && view.lyricsEnabled()) {
         int subtitleY = view.controlsVisible() ? 151 : 199;
         this.drawLandscapeSubtitle(g, view.videoSubtitle(), subtitleY, view.controlsVisible());
      }

      if (view.controlsVisible()) {
         this.fill(g, 14, 14, 420, 26, 1711276032);
         this.text(g, 25, 21, "BiliBili 视频投影预览", -1379585, false);
         this.text(g, 165, 23, view.playing() ? "LIVE PLAYBACK" : "PAUSED", view.playing() ? -9568336 : -15251, true);
         this.smallToggle(g, 278, 18, 44, 16, view.playlistOpen() ? -870102679 : -1440733643, "列表");
         this.text(g, 333, 22, view.quality(), -2298881, true);
         this.text(g, 389, 22, this.gameTimeLabel(), -7743745, true);
         this.fill(g, 16, 171, 416, 66, -1442511092);
         this.progress(g, 32, 184, 384, 6, view.mediaProgress(), -13486006, -9123841);
         this.text(g, 32, 196, formatPlaybackTime(view.elapsedMillis()), -4667938, true);
         this.text(g, 389, 196, formatPlaybackTime(view.durationMillis()), -4667938, true);
         this.prevButton(g, 146, 204, 30, 24, -1440996816, -4466433);
         this.playPauseButton(g, 200, 198, 46, 34, view.playing() ? -579806977 : -570437010, -16314344, view.playing());
         this.nextButton(g, 270, 204, 30, 24, -1440996816, -4466433);
         this.text(g, 319, 208, "VOL", -4667938, true);
         this.progress(g, 348, 212, 70, 5, view.volume(), -13486006, -8454204);
         this.smallToggle(g, 28, 213, 48, 14, view.subtitleMenuOpen() ? -1437780632 : -1440733643, "字幕");
         this.smallToggle(g, 88, 213, 48, 14, view.repeatMode() > 0 ? -1436462539 : -1440733643, "循环");
      } else {
         this.text(g, 360, 18, view.videoResolutionLabel(), -1713574913, true);
      }

      if (view.playlistOpen()) {
         this.drawLandscapePlaylist(g, view);
      }

      if (view.subtitleMenuOpen() && view.controlsVisible()) {
         this.drawLandscapeSubtitleMenu(g, view);
      }

      if (view.qualityMenuOpen() && view.controlsVisible()) {
         this.drawLandscapeQualityMenu(g, view);
      }

      if (view.biliLoginVisible()) {
         this.drawLandscapeBiliLoginOverlay(g, view);
      }

      this.drawHover(g, view, true);
   }

   private void withLandscape(GuiGraphics g, Runnable draw) {
      g.pose().pushPose();
      g.pose().translate(0.0F, HEIGHT, 0.0F);
      g.pose().mulPose(Axis.ZP.rotation((float) (-Math.PI / 2)));
      draw.run();
      g.pose().popPose();
   }

   private void drawAlbumArt(GuiGraphics g, int x, int y, int w, int h) {
      this.fill(g, x, y, w, h, -13800746);
      this.fill(g, x + 8, y + 8, w - 16, h - 16, -1442511609);
      this.fill(g, x + w / 2 - 22, y + h / 2 - 22, 44, 44, -15263195);
      this.fill(g, x + w / 2 - 8, y + h / 2 - 8, 16, 16, -8923137);
   }

   private void drawLyricsPanel(GuiGraphics g, MP4GuiViewState view, int x, int y, int w, int h) {
      this.steppedGradientV(g, x, y, w, h, -15722715, -14411975, 14);
      this.fill(g, x + 7, y + 8, w - 14, h - 16, -1442511092);
      this.text(g, x + w / 2 - 20, y + 14, "滚动歌词", -7549953, true);
      String lyric = view.lyricLine();
      String translated = view.translatedLyricLine();
      boolean hasLyric = lyric != null && !lyric.isBlank();
      boolean hasTranslated = translated != null && !translated.isBlank();
      int maxTextWidth = w - 30;
      int lineHeight = 12;
      int contentCenterY = y + h / 2 + 4;
      if (!hasLyric && !hasTranslated) {
         this.centeredText(g, x + w / 2, contentCenterY - 8, "暂无歌词", -7629654, false);
         this.centeredText(g, x + w / 2, contentCenterY + 14, "NetMusic lyric loading...", -10721672, true);
         this.fill(g, x + 16, y + 124, w - 32, 1, 864865279);
      } else {
         if (hasLyric && hasTranslated) {
            this.drawWrappedCentered(g, x + w / 2, contentCenterY - lineHeight, maxTextWidth, lineHeight, lyric, -1379585, false);
            this.drawWrappedCentered(g, x + w / 2, contentCenterY + lineHeight + 2, maxTextWidth, lineHeight, translated, -6301953, false);
         } else if (hasLyric) {
            this.drawWrappedCentered(g, x + w / 2, contentCenterY, maxTextWidth, lineHeight, lyric, -1379585, false);
         } else {
            this.drawWrappedCentered(g, x + w / 2, contentCenterY, maxTextWidth, lineHeight, translated, -6301953, false);
         }

         this.fill(g, x + 16, y + 124, w - 32, 1, 864865279);
      }
   }

   private void drawPlaylist(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 18, 62, WIDTH - 36, 242, -300936162);
      this.text(g, 35, 78, "播放队列", -7549953, false);
      this.text(g, 150, 81, this.queuePageLabel(view), -7629654, true);
      if (view.queueSize() <= 0) {
         this.centeredText(g, WIDTH / 2, 183, "把 NetMusic 唱片放进 MP4", -7629654, true);
      } else {
         for (int i = 0; i < view.portraitQueueVisibleRows(); i++) {
            int index = view.queueScrollOffset() + i;
            if (index >= view.queueSize()) {
               break;
            }

            int rowY = 116 + i * 28;
            boolean selected = index == view.selectedQueueIndex();
            this.fill(g, 31, rowY, WIDTH - 70, 22, selected ? -14271649 : -15262422);
            this.fill(g, 40, rowY + 8, 5, 5, selected ? -9123841 : -13156008);
            this.marqueeText(g, 52, rowY + 6, WIDTH - 132, view.queueTitle(index), -1379585, true);
         }
      }
   }

   private void drawLandscapePlaylist(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 72, 44, 304, 76, -300936162);
      this.text(g, 88, 56, "播放列表 / 视频源", -7549953, false);

      for (int i = 0; i < view.landscapeQueueVisibleRows(); i++) {
         int index = view.queueScrollOffset() + i;
         if (index >= view.queueSize()) {
            break;
         }

         int rowY = 75 + i * 14;
         boolean selected = index == view.selectedQueueIndex();
         this.fill(g, 88, rowY, 248, 12, selected ? -14271649 : -15262422);
         this.fill(g, 93, rowY + 4, 4, 4, selected ? -9123841 : -13156008);
         this.marqueeText(g, 102, rowY + 1, 222, view.queueTitle(index), -1379585, true);
      }
   }

   private void drawQualityMenu(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 150, 46, 86, 132, -300936162);
      this.text(g, 165, 53, "画质", -7549953, true);

      for (int i = 0; i < view.qualities().size(); i++) {
         this.qualityOption(g, view, 160, 68 + i * 13, 66, 11, i);
      }
   }

   private void drawLandscapeQualityMenu(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 318, 42, 94, 132, -300936162);
      this.text(g, 350, 50, "画质", -7549953, true);

      for (int i = 0; i < view.qualities().size(); i++) {
         this.qualityOption(g, view, 330, 65 + i * 13, 70, 11, i);
      }
   }

   private void qualityOption(GuiGraphics g, MP4GuiViewState view, int x, int y, int w, int h, int index) {
      String quality = index >= 0 && index < view.qualities().size() ? view.qualities().get(index) : "";
      boolean selected = view.quality().equals(quality);
      this.fill(g, x, y, w, h, selected ? -14271649 : -15262422);
      this.outline(g, x, y, w, h, selected ? -9123841 : -13024940);
      this.text(g, x + 6, y + 1, quality, selected ? -1379585 : -4667938, true);
   }

   private void drawSubtitleMenu(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 132, 322, 96, 118, -300936162);
      this.text(g, 145, 328, "字幕设置", -7549953, true);
      this.subtitleOption(g, 146, 354, 44, 14, "关", !view.lyricsEnabled());
      this.subtitleOption(g, 146, 375, 44, 14, "主", view.lyricsEnabled() && view.subtitlePrimaryMode());
      this.subtitleOption(g, 146, 396, 44, 14, "副", view.lyricsEnabled() && !view.subtitlePrimaryMode());
      this.subtitleOption(g, 146, 417, 72, 14, "AI字幕", view.subtitleAiEnabled());
   }

   private void drawLandscapeSubtitleMenu(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 232, 48, 108, 114, -300936162);
      this.text(g, 248, 55, "字幕设置", -7549953, true);
      this.subtitleOption(g, 244, 72, 52, 14, "关", !view.lyricsEnabled());
      this.subtitleOption(g, 244, 94, 52, 14, "主", view.lyricsEnabled() && view.subtitlePrimaryMode());
      this.subtitleOption(g, 244, 116, 52, 14, "副", view.lyricsEnabled() && !view.subtitlePrimaryMode());
      this.subtitleOption(g, 244, 138, 82, 14, "AI字幕", view.subtitleAiEnabled());
   }

   private void subtitleOption(GuiGraphics g, int x, int y, int w, int h, String label, boolean selected) {
      this.fill(g, x, y, w, h, selected ? -14271649 : -15262422);
      this.outline(g, x, y, h, h, selected ? -9123841 : -10853512);
      if (selected) {
         this.text(g, x + 3, y + 2, "✓", -1379585, true);
      }

      this.text(g, x + h + 5, y + 2, label, -1379585, true);
   }

   private void drawBiliLoginOverlay(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 7, 7, WIDTH - 14, 398, -872085748);
      this.fill(g, 28, 76, 200, 260, -300936162);
      this.outline(g, 28, 76, 200, 260, -1720399873);
      this.centeredText(g, WIDTH / 2, 94, "B站账号登录", -1379585, false);
      this.centeredText(g, WIDTH / 2, 112, "使用 B站 APP 扫码", -4667938, true);
      this.drawQrImage(g, view.biliQrImage(), 58, 130, 140);
      this.centeredText(g, WIDTH / 2, 286, view.biliLoginStatusText(), -6619214, true);
      this.centeredText(g, WIDTH / 2, 308, "再次点击 B站 按钮关闭/重试", -7629654, true);
   }

   private void drawLandscapeBiliLoginOverlay(GuiGraphics g, MP4GuiViewState view) {
      this.fill(g, 7, 7, 434, 242, -872085748);
      this.fill(g, 124, 24, 200, 208, -300936162);
      this.outline(g, 124, 24, 200, 208, -1720399873);
      this.centeredText(g, 224, 42, "B站账号登录", -1379585, false);
      this.centeredText(g, 224, 60, "使用 B站 APP 扫码", -4667938, true);
      this.drawQrImage(g, view.biliQrImage(), 154, 76, 140);
      this.centeredText(g, 224, 222, view.biliLoginStatusText(), -6619214, true);
   }

   private void drawQrImage(GuiGraphics g, BufferedImage image, int x, int y, int size) {
      this.fill(g, x - 3, y - 3, size + 6, size + 6, -1);
      if (image == null) {
         this.fill(g, x, y, size, size, -13946816);
         this.centeredText(g, x + size / 2, y + size / 2 - 5, "二维码加载中", -4667938, true);
      } else {
         int srcW = Math.max(1, image.getWidth());
         int srcH = Math.max(1, image.getHeight());

         for (int dy = 0; dy < size; dy++) {
            int sy = Math.min(srcH - 1, dy * srcH / Math.max(1, size));
            int runColor = 0;
            int runStart = x;
            int runWidth = 0;

            for (int dx = 0; dx < size; dx++) {
               int sx = Math.min(srcW - 1, dx * srcW / Math.max(1, size));
               int color = 0xFF000000 | image.getRGB(sx, sy) & 16777215;
               if (runWidth == 0) {
                  runColor = color;
                  runStart = x + dx;
                  runWidth = 1;
               } else if (color == runColor) {
                  runWidth++;
               } else {
                  this.fill(g, runStart, y + dy, runWidth, 1, runColor);
                  runColor = color;
                  runStart = x + dx;
                  runWidth = 1;
               }
            }

            if (runWidth > 0) {
               this.fill(g, runStart, y + dy, runWidth, 1, runColor);
            }
         }
      }
   }

   private void drawHover(GuiGraphics g, MP4GuiViewState view, boolean landscape) {
      String control = view.hoverControlName();
      int color = 1728053247;
      switch (control) {
         case "PREVIOUS":
            this.outline(g, landscape ? 144 : 32, landscape ? 202 : 331, landscape ? 34 : 46, landscape ? 28 : 46, color);
            break;
         case "PLAY":
            this.outline(g, landscape ? 198 : 99, landscape ? 196 : 323, landscape ? 50 : 58, landscape ? 38 : 58, color);
            break;
         case "NEXT":
            this.outline(g, landscape ? 268 : 178, landscape ? 202 : 331, landscape ? 34 : 46, landscape ? 28 : 46, color);
            break;
         case "PROGRESS":
            this.outline(g, landscape ? 30 : 26, landscape ? 176 : 291, landscape ? 388 : 204, landscape ? 16 : 18, color);
            break;
         case "VOLUME":
            this.outline(g, landscape ? 346 : 62, landscape ? 207 : 388, landscape ? 74 : 158, landscape ? 14 : 12, color);
      }
   }

   private void drawDeviceFrame(GuiGraphics g) {
      this.outline(g, 1, 1, WIDTH - 2, HEIGHT - 2, -16645629);
      this.outline(g, 3, 3, WIDTH - 6, HEIGHT - 6, -869782216);
   }

   private void fill(GuiGraphics g, int x, int y, int w, int h, int color) {
      g.fill(x, y, x + w, y + h, color);
   }

   private void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
      if (w > 0 && h > 0) {
         g.fill(x, y, x + w, y + 1, color);
         g.fill(x, y + h - 1, x + w, y + h, color);
         g.fill(x, y, x + 1, y + h, color);
         g.fill(x + w - 1, y, x + w, y + h, color);
      }
   }

   private void text(GuiGraphics g, int x, int y, String value, int color, boolean small) {
      Font font = Minecraft.getInstance().font;
      if (value != null && !value.isEmpty()) {
         g.drawString(font, value, x, y, color);
      }
   }

   private void centeredText(GuiGraphics g, int centerX, int y, String value, int color, boolean small) {
      Font font = Minecraft.getInstance().font;
      int width = font.width(value);
      this.text(g, centerX - width / 2, y, value, color, small);
   }

   private void marqueeText(GuiGraphics g, int x, int y, int maxWidth, String text, int color, boolean small) {
      Font font = Minecraft.getInstance().font;
      float scale = 1.0F;
      int textWidth = Math.round(font.width(text) * scale);
      if (textWidth <= maxWidth) {
         this.text(g, x, y, text, color, small);
      } else {
         String padded = text + "    " + text;
         int scrollPeriod = Math.max(1, Math.round(font.width(padded) * scale) + maxWidth);
         int offset = this.renderTicks * 2 % scrollPeriod;
         int accumulated = 0;
         int start = 0;

         for (int i = 0; i < padded.length(); i++) {
            int cw = Math.round(font.width(String.valueOf(padded.charAt(i))) * scale);
            if (accumulated + cw > offset) {
               start = i;
               break;
            }

            accumulated += cw;
         }

         StringBuilder visible = new StringBuilder();
         int visibleWidth = 0;

         for (int i = start; i < padded.length(); i++) {
            char c = padded.charAt(i);
            int cw = Math.round(font.width(String.valueOf(c)) * scale);
            if (visibleWidth + cw > maxWidth) {
               break;
            }

            visible.append(c);
            visibleWidth += cw;
         }

         this.text(g, x, y, visible.toString(), color, small);
      }
   }

   private void centeredMarqueeText(GuiGraphics g, int centerX, int y, int maxWidth, String text, int color, boolean small) {
      Font font = Minecraft.getInstance().font;
      if (font.width(text) <= maxWidth) {
         this.centeredText(g, centerX, y, text, color, small);
      } else {
         this.marqueeText(g, centerX - maxWidth / 2, y, maxWidth, text, color, small);
      }
   }

   private void drawLandscapeSubtitle(GuiGraphics g, String subtitle, int y, boolean controlsVisible) {
      Font font = Minecraft.getInstance().font;
      int textWidth = Math.min(408, font.width(subtitle));
      int bgWidth = Math.min(420, Math.max(36, textWidth + 18));
      int bgX = 224 - bgWidth / 2;
      int bgY = y - 3;
      this.fill(g, bgX, bgY, bgWidth, 15, controlsVisible ? 1996488704 : 1711276032);
      this.fill(g, bgX + 1, bgY + 1, bgWidth - 2, 13, controlsVisible ? 1428172872 : 1141180172);
      this.centeredText(g, 224, y, subtitle, -220402689, true);
   }

   private void steppedGradientV(GuiGraphics g, int x, int y, int w, int h, int topColor, int bottomColor, int steps) {
      steps = Math.max(1, Math.min(h, steps));
      float stepH = (float)h / steps;

      for (int i = 0; i < steps; i++) {
         int sy = y + Math.round(i * stepH);
         int sh = Math.round((i + 1) * stepH) - Math.round(i * stepH);
         float t = (float)i / (steps - 1);
         int color = lerpColor(topColor, bottomColor, t);
         this.fill(g, x, sy, w, sh, color);
      }
   }

   private static int lerpColor(int c0, int c1, float t) {
      float r0 = (c0 >> 16 & 0xFF) / 255.0F;
      float g0 = (c0 >> 8 & 0xFF) / 255.0F;
      float b0 = (c0 & 0xFF) / 255.0F;
      float r1 = (c1 >> 16 & 0xFF) / 255.0F;
      float g1 = (c1 >> 8 & 0xFF) / 255.0F;
      float b1 = (c1 & 0xFF) / 255.0F;
      int r = Math.round((r0 + (r1 - r0) * t) * 255.0F);
      int g = Math.round((g0 + (g1 - g0) * t) * 255.0F);
      int b = Math.round((b0 + (b1 - b0) * t) * 255.0F);
      return 0xFF000000 | r << 16 | g << 8 | b;
   }

   private void drawWrappedCentered(GuiGraphics g, int centerX, int centerY, int maxWidth, int lineHeight, String text, int color, boolean small) {
      List<String> lines = this.wrapText(text, maxWidth, small);
      if (!lines.isEmpty()) {
         float blockH = lines.size() * lineHeight;
         float startY = centerY - blockH / 2.0F + lineHeight / 2.0F;

         for (int i = 0; i < lines.size(); i++) {
            this.centeredText(g, centerX, Math.round(startY + i * lineHeight), lines.get(i), color, small);
         }
      }
   }

   private List<String> wrapText(String text, int maxWidth, boolean small) {
      List<String> lines = new ArrayList<>();
      if (text != null && !text.isBlank()) {
         Font font = Minecraft.getInstance().font;
         float scale = 1.0F;
         String[] words = text.split(" ");
         StringBuilder currentLine = new StringBuilder();

         for (String word : words) {
            if (!word.isEmpty()) {
               String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
               int candidateWidth = Math.round(font.width(candidate) * scale);
               if (candidateWidth <= maxWidth) {
                  if (!currentLine.isEmpty()) {
                     currentLine.append(' ');
                  }

                  currentLine.append(word);
               } else {
                  if (!currentLine.isEmpty()) {
                     lines.add(currentLine.toString());
                     currentLine = new StringBuilder();
                  }

                  int wordWidth = Math.round(font.width(word) * scale);
                  if (wordWidth <= maxWidth) {
                     currentLine.append(word);
                  } else {
                     for (int i = 0; i < word.length(); i++) {
                        String ch = word.substring(i, i + 1);
                        String chCandidate = currentLine.isEmpty() ? ch : currentLine.toString() + ch;
                        int chWidth = Math.round(font.width(chCandidate) * scale);
                        if (chWidth <= maxWidth) {
                           currentLine.append(ch);
                        } else {
                           if (!currentLine.isEmpty()) {
                              lines.add(currentLine.toString());
                           }

                           currentLine = new StringBuilder(ch);
                        }
                     }
                  }
               }
            }
         }

         if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
         }

         return lines;
      } else {
         return lines;
      }
   }

   private void progress(GuiGraphics g, int x, int y, int w, int h, float progress, int bg, int fg) {
      this.fill(g, x, y, w, h, bg);
      int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
      this.fill(g, x, y, filled, h, fg);
      this.fill(g, x + filled - h, y - h / 2, h * 2, h * 2, -6511699);
      this.outline(g, x + filled - h, y - h / 2, h * 2, h * 2, -11841187);
   }

   private void prevButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg) {
      this.fill(g, x + 3, y + 4, w, h, 1426063360);
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      int triHSize = 6;
      int triWSize = this.playTriangleWidth();
      int barW = Math.max(2, triWSize / 2);
      int barH = triHSize * 2;
      int gap = 1;
      int iconW = barW + gap + triWSize;
      int iconStartX = x + w / 2 - iconW / 2;
      int iconCy = y + h / 2;
      this.fill(g, iconStartX, iconCy - barH / 2, barW, barH, fg);
      this.drawTriangleBackward(g, iconStartX + barW + gap + triWSize / 2, iconCy, fg);
   }

   private void nextButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg) {
      this.fill(g, x + 3, y + 4, w, h, 1426063360);
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      int triHSize = 6;
      int triWSize = this.playTriangleWidth();
      int barW = Math.max(2, triWSize / 2);
      int barH = triHSize * 2;
      int gap = 1;
      int iconW = triWSize + gap + barW;
      int iconStartX = x + w / 2 - iconW / 2;
      int iconCy = y + h / 2;
      this.drawTriangleForward(g, iconStartX + triWSize / 2, iconCy, fg);
      this.fill(g, iconStartX + triWSize + gap, iconCy - barH / 2, barW, barH, fg);
   }

   private void playPauseButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg, boolean playing) {
      this.fill(g, x + 3, y + 4, w, h, 1426063360);
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      if (playing) {
         int barW = Math.max(2, w / 14);
         int barH = Math.min(w, h) / 3;
         this.drawPauseBars(g, x + w / 2, y + h / 2, barW, barH, Math.max(2, w / 18), fg);
      } else {
         this.drawTriangleForward(g, x + w / 2, y + h / 2, fg);
      }
   }

   private int playTriangleWidth() {
      return PLAY_TRIANGLE_ROWS[PLAY_TRIANGLE_ROWS.length / 2];
   }

   private void drawTriangleForward(GuiGraphics g, int cx, int cy, int color) {
      int left = cx - this.playTriangleWidth() / 2;
      int top = cy - PLAY_TRIANGLE_ROWS.length / 2;

      for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
         this.fill(g, left, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
      }
   }

   private void drawTriangleBackward(GuiGraphics g, int cx, int cy, int color) {
      int right = cx + this.playTriangleWidth() / 2;
      int top = cy - PLAY_TRIANGLE_ROWS.length / 2;

      for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
         this.fill(g, right - PLAY_TRIANGLE_ROWS[row] + 1, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
      }
   }

   private void drawPauseBars(GuiGraphics g, int cx, int cy, int barW, int barH, int gap, int color) {
      int halfGap = gap / 2;
      int top = cy - barH / 2;
      this.fill(g, cx - halfGap - barW, top, barW, barH, color);
      this.fill(g, cx + halfGap, top, barW, barH, color);
   }

   private void smallToggle(GuiGraphics g, int x, int y, int w, int h, int bg, String label) {
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      this.centeredText(g, x + w / 2, y + 2, label, -2298881, true);
   }

   private String gameTimeLabel() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null) {
         return "--:--";
      } else {
         long dayTime = MonotonicMediaClock.nowTick() % 24000L;
         int totalMinutes = (int)((dayTime + 6000L) % 24000L * 1440L / 24000L);
         return String.format(Locale.ROOT, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
      }
   }

   private static String formatPlaybackTime(long millis) {
      long totalSeconds = Math.max(0L, millis) / 1000L;
      return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
   }

   private String queuePageLabel(MP4GuiViewState view) {
      int size = view.queueSize();
      return size <= 0 ? "0/0" : view.queueScrollOffset() + 1 + "/" + size;
   }

   @Override
   public void close() {
      if (this.registered) {
         Minecraft.getInstance().getTextureManager().release(this.textureId);
         this.registered = false;
      }

      if (this.target != null) {
         this.target.destroyBuffers();
         this.target = null;
      }

      this.texture = null;
      this.guiBuffer = null;
   }
}
