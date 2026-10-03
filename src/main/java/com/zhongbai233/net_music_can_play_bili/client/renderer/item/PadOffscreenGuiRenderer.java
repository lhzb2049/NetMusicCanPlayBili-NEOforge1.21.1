package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.PadHandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.PadRenderProperties;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.slf4j.Logger;

final class PadOffscreenGuiRenderer implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final PadRenderProperties.Offscreen PROPERTIES = PadRenderProperties.offscreen();
   private static final int SCALE = PROPERTIES.scale();
   private static final float MAP_PAN_RENDER_BLOCKS = PROPERTIES.mapPanRenderBlocks();
   private static final float MAP_YAW_RENDER_DEGREES = PROPERTIES.mapYawRenderDegrees();
   private static final int PLAYBACK_REFRESH_TICKS = PROPERTIES.playbackRefreshTicks();
   private static final int WIDTH = 448;
   private static final int HEIGHT = 256;
   private static final int TARGET_WIDTH = 448 * Math.max(1, SCALE);
   private static final int TARGET_HEIGHT = 256 * Math.max(1, SCALE);
   private static final long MAX_GUI_FPS = PROPERTIES.maxFps();
   private static final long MIN_FRAME_INTERVAL_NANOS = MAX_GUI_FPS <= 0L ? 0L : 1000000000L / MAX_GUI_FPS;
   private final ResourceLocation textureId;
   private TextureTarget target;
   private RenderTargetTexture texture;
   private BufferSource guiBuffer;
   private boolean registered;
   private boolean failed;
   private boolean loggedReady;
   private PadGuiViewState lastView;
   private int lastRenderedTick = Integer.MIN_VALUE;
   private long lastRenderedNanos;
   private long lastFocusRevision = Long.MIN_VALUE;
   private final PadMapRenderContext mapRenderContext;
   private static final int[] PLAY_TRIANGLE_ROWS = new int[]{1, 3, 5, 7, 9, 11, 9, 7, 5, 3, 1};

   PadOffscreenGuiRenderer(String textureKey) {
      String safeKey = textureKey != null && !textureKey.isBlank() ? textureKey.toLowerCase(Locale.ROOT) : "fallback";
      this.textureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/pad_gui_offscreen_" + safeKey);
      this.mapRenderContext = new PadMapRenderContext(safeKey);
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
            LOGGER.warn("Pad 离屏 GUI 初始化失败: {}", this.textureId, var3);
         }
      }
   }

   void tickMapLayer(UUID deviceId) {
      if (!this.failed) {
         try {
            PadGuiViewState view = PadGuiViewState.capture(deviceId);
            this.mapRenderContext.tick(view.map());
         } catch (RuntimeException var3) {
            this.failed = true;
            this.close();
            LOGGER.warn("Pad 地图离屏层 tick 更新失败: {}", this.textureId, var3);
         }
      }
   }

   void renderFrameStart(UUID deviceId, float partialTick) {
      if (!this.failed) {
         try {
            this.ensureResources();
            MP4HandheldVideoClient.update(deviceId, PadHandheldMediaProfile.INSTANCE);
            PadGuiViewState view = PadGuiViewState.capture(deviceId, partialTick);
            long now = System.nanoTime();
            if (!this.shouldRender(view)) {
               return;
            }

            if (!this.shouldRenderNow(view, now)) {
               return;
            }

            long started = System.nanoTime();
            this.render(view);
            this.lastView = view;
            this.lastRenderedTick = view.ticks();
            this.lastFocusRevision = view.focusRevision();
            PadPerfLogger.recordGuiFrame(System.nanoTime() - started);
            if (!this.loggedReady) {
               this.loggedReady = true;
               LOGGER.info(
                  "Pad 离屏 GUI 已启用: texture={} target={}x{} logical={}x{} scale={}",
                  new Object[]{this.textureId, TARGET_WIDTH, TARGET_HEIGHT, 448, 256, Math.max(1, SCALE)}
               );
            }
         } catch (RuntimeException var8) {
            this.failed = true;
            this.close();
            LOGGER.warn("Pad 离屏 GUI 渲染失败: {}", this.textureId, var8);
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

   private boolean shouldRender(PadGuiViewState view) {
      if (this.lastView == null) {
         return true;
      } else if (view.focusRevision() != this.lastFocusRevision
         || view.document().sequence() != this.lastView.document().sequence()
         || view.document().locked() != this.lastView.document().locked()
         || view.document().mediaEntries().size() != this.lastView.document().mediaEntries().size()
         || view.document().triggerPoints().size() != this.lastView.document().triggerPoints().size()
         || view.map() != this.lastView.map()) {
         return true;
      } else {
         return !this.mapPanChanged(view, this.lastView) && !this.yawChanged(view.playerYaw(), this.lastView.playerYaw())
            ? this.shouldRefreshPlayback(view)
            : true;
      }
   }

   private boolean mapPanChanged(PadGuiViewState view, PadGuiViewState previous) {
      float dx = view.playerX() - previous.playerX();
      float dz = view.playerZ() - previous.playerZ();
      return dx * dx + dz * dz >= MAP_PAN_RENDER_BLOCKS * MAP_PAN_RENDER_BLOCKS;
   }

   private boolean yawChanged(float yaw, float previousYaw) {
      float delta = Math.abs(Math.floorMod(Math.round((yaw - previousYaw) * 100.0F + 18000.0F), 36000) / 100.0F - 180.0F);
      return delta >= MAP_YAW_RENDER_DEGREES;
   }

   private boolean shouldRefreshPlayback(PadGuiViewState view) {
      return view.deviceId() != null && ClientMediaPlayback.hasPlayback(view.deviceId()) && view.ticks() - this.lastRenderedTick >= PLAYBACK_REFRESH_TICKS;
   }

   private boolean shouldRenderNow(PadGuiViewState view, long now) {
      return this.lastView != null && view.map() == this.lastView.map() && view.focusRevision() == this.lastFocusRevision
         ? MIN_FRAME_INTERVAL_NANOS <= 0L || now - this.lastRenderedNanos >= MIN_FRAME_INTERVAL_NANOS
         : true;
   }

   private void render(PadGuiViewState view) {
      Minecraft minecraft = Minecraft.getInstance();
      GuiGraphics graphics = new GuiGraphics(minecraft, this.guiBuffer);
      this.drawGui(graphics, view);
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
         this.lastRenderedNanos = System.nanoTime();
      }
   }

   private void drawGui(GuiGraphics g, PadGuiViewState view) {
      if (view.document().locked()) {
         if (view.transparentVideoOverlay()) {
            this.drawLockedVideoOverlay(g, view);
         } else {
            this.drawMapCard(g, view, 0, 0, 448, 256, false);
            this.drawLockedAudioProgress(g, view);
         }
      } else {
         this.fill(g, 0, 0, 448, 256, -16314344);
         this.fill(g, 3, 3, 442, 250, -15721946);
         this.drawStatusBar(g, view);
         this.drawMapCard(g, view, 14, 44, 270, 198, true);
         this.drawMediaLibrary(g, view, 300, 48, 132, 98);
         if (this.hasSelectedPoint(view)) {
            this.drawPointEditor(g, view, 300, 130, 132, 86);
         } else {
            this.drawPlaybackCard(g, view, 300, 166, 132, 42);
         }

         this.drawBottomHint(g, view);
         this.drawFocusFeedback(g);
         this.outline(g, 1, 1, 446, 254, -16644598);
         this.outline(g, 5, 5, 438, 246, 1714924031);
      }
   }

   private void drawLockedVideoOverlay(GuiGraphics g, PadGuiViewState view) {
      if (view.controlsVisible()) {
         this.drawLockedVideoControls(g, view);
      } else {
         this.drawLockedVideoSubtitle(g, view, 206);
      }

      this.drawFocusFeedback(g);
   }

   private void drawFocusFeedback(GuiGraphics g) {
      boolean locked = this.lastView != null && this.lastView.document().locked();
      this.drawControlFeedback(g, "MAP", locked ? 0 : 14, locked ? 0 : 44, locked ? 448 : 270, locked ? 256 : 198);
      if (!locked) {
         this.drawControlFeedback(g, "MEDIA", 300, 48, 132, 98);
         this.drawControlFeedback(g, "EDITOR", 300, 130, 132, 86);
         this.drawControlFeedback(g, "PLAYBACK", 300, 166, 132, 42);
         this.drawControlFeedback(g, "PUBLISH", 300, 220, 132, 18);
      }
   }

   private void drawControlFeedback(GuiGraphics g, String control, int x, int y, int w, int h) {
      if (PadFocusState.pressControl(control)) {
         this.outline(g, x - 2, y - 2, w + 4, h + 4, -11930);
         this.fill(g, x, y, w, h, 587190630);
      } else if (PadFocusState.hoverControl(control)) {
         this.outline(g, x - 1, y - 1, w + 2, h + 2, -1433807105);
      }
   }

   private void drawStatusBar(GuiGraphics g, PadGuiViewState view) {
      this.fill(g, 8, 8, 432, 26, -15260104);
      this.text(g, 20, 16, "PAD MAP / 横屏", -4331521);
      String right = view.document().locked() ? "LOCKED" : "DRAFT";
      this.text(g, 428 - Minecraft.getInstance().font.width(right), 16, right, view.document().locked() ? -14249 : -9444726);
   }

   private void drawMapCard(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h, boolean framed) {
      if (framed) {
         this.fill(g, x, y, w, h, -14996936);
         this.outline(g, x, y, w, h, -13677984);
      }

      int inset = framed ? 6 : 0;
      int innerX = x + inset;
      int innerY = y + inset;
      int innerW = w - inset * 2;
      int innerH = h - inset * 2;
      PadMapProjection.Rect mapRect = framed
         ? PadMapProjection.fitRect(innerX, innerY, innerW, innerH, PadMapLayerTexture.VIEW_WIDTH, PadMapLayerTexture.VIEW_HEIGHT, 1.75F)
         : new PadMapProjection.Rect(innerX, innerY, innerW, innerH);
      this.mapRenderContext.draw(g, view, mapRect);
      if (framed) {
         this.drawMapHoleFrame(g, x, y, w, h, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
      }
   }

   private void drawMapHoleFrame(GuiGraphics g, int x, int y, int w, int h, int innerX, int innerY, int innerW, int innerH) {
      this.outline(g, x, y, w, h, -13677984);
      this.outline(g, innerX - 1, innerY - 1, innerW + 2, innerH + 2, 1714924031);
   }

   private void drawMediaLibrary(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
      this.fill(g, x, y, w, h, -15656153);
      this.outline(g, x, y, w, h, -13677984);
      this.text(g, x + 10, y + 10, "媒体库 " + view.document().mediaEntries().size(), -1515320);
      this.text(g, x + 88, y + 10, "点位 " + view.document().triggerPoints().size(), -14249);
      if (view.document().mediaEntries().isEmpty()) {
         this.centeredText(g, x + w / 2, y + 52, "暂无歌曲", -8483165);
         this.centeredText(g, x + w / 2, y + 68, "把唱片放到 Pad", -11378317);
      } else {
         int row = 0;

         for (PadMediaEntry entry : view.document().mediaEntries()) {
            if (row >= 4) {
               break;
            }

            int rowY = y + 30 + row * 16;
            this.fill(g, x + 8, rowY, w - 16, 15, -14996936);
            String name = this.mediaName(entry);
            this.text(g, x + 14, rowY + 4, this.trim(name, 10), -1379585);
            this.text(g, x + w - 32, rowY + 4, "#" + entry.mediaId(), -7743745);
            row++;
         }

         if (PadFocusState.draggingMedia()) {
            this.centeredText(g, x + w / 2, y + h - 15, "拖到地图创建点位", -11930);
         }
      }
   }

   private void drawPointEditor(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
      Optional<PadTriggerPoint> selected = view.document().triggerPoints().stream().filter(pointx -> PadFocusState.selectedPoint(pointx.pointId())).findFirst();
      if (!selected.isEmpty()) {
         PadTriggerPoint point = selected.get();
         this.fill(g, x, y, w, h, -301066204);
         this.outline(g, x, y, w, h, -11930);
         this.text(g, x + 8, y + 7, this.trim(point.name().isBlank() ? "点位" : point.name(), 12), -3132);
         this.text(g, x + 8, y + 18, "R" + point.radiusBlocks() + " V" + point.volumePerMille() / 10 + "% #" + point.mediaId(), -7743745);
         this.drawEditorButton(g, x + 8, y + 28, 52, 15, point.visible() ? "显示" : "隐藏", point.visible() ? -1553825 : -8483165);
         this.drawEditorButton(g, x + 66, y + 28, 58, 15, "删除", -38037);
         this.drawEditorButton(g, x + 8, y + 45, 28, 15, "R-", -4331521);
         this.drawEditorButton(g, x + 40, y + 45, 28, 15, "R+", -4331521);
         this.drawEditorButton(g, x + 74, y + 45, 50, 15, point.loop() ? "循环" : "单次", -9444726);
         this.drawEditorButton(g, x + 8, y + 62, 52, 15, point.triggerMode().name().equals("MANUAL") ? "手动" : "半径", -14249);
         this.drawEditorButton(g, x + 66, y + 62, 28, 15, "V-", -4331521);
         this.drawEditorButton(g, x + 96, y + 62, 28, 15, "V+", -4331521);
      }
   }

   private boolean hasSelectedPoint(PadGuiViewState view) {
      return view.document().triggerPoints().stream().anyMatch(point -> PadFocusState.selectedPoint(point.pointId()));
   }

   private void drawEditorButton(GuiGraphics g, int x, int y, int w, int h, String label, int color) {
      this.fill(g, x, y, w, h, -14996936);
      this.outline(g, x, y, w, h, color);
      this.centeredText(g, x + w / 2, y + 4, label, color);
   }

   private void drawBottomHint(GuiGraphics g, PadGuiViewState view) {
      this.fill(g, 300, 220, 132, 18, -15260104);
      this.outline(g, 300, 220, 132, 18, -9444726);
      this.centeredText(g, 366, 225, "发布副本", -9444726);
   }

   private void drawLockedAudioProgress(GuiGraphics g, PadGuiViewState view) {
      UUID deviceId = view.deviceId();
      boolean playing = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
      boolean paused = PadFocusState.pausedPlaybackAvailable() && !PadFocusState.pausedVideo();
      if (deviceId != null && (playing || paused)) {
         long elapsed = playing ? Math.max(0L, ClientMediaPlayback.elapsedMillis(deviceId)) : PadFocusState.pausedElapsedMillis();
         long duration = playing ? Math.max(0L, ClientMediaPlayback.durationMillis(deviceId)) : PadFocusState.pausedDurationMillis();
         String song = ClientMediaPlayback.songName(deviceId);
         String lyric = playing
            ? (view.subtitlePrimaryMode() ? ClientMediaPlayback.lyricLine(deviceId) : ClientMediaPlayback.translatedLyricLine(deviceId))
            : "";
         if (playing && (lyric == null || lyric.isBlank())) {
            lyric = view.subtitlePrimaryMode() ? ClientMediaPlayback.translatedLyricLine(deviceId) : ClientMediaPlayback.lyricLine(deviceId);
         }

         if (view.subtitlesEnabled() && lyric != null && !lyric.isBlank()) {
            this.drawVideoSubtitle(g, lyric, 158);
         }

         this.fill(g, 24, 178, 400, 64, -1157298420);
         this.outline(g, 24, 178, 400, 64, 1716478584);
         this.text(g, 36, 184, this.trim(song != null && !song.isBlank() ? song : "Pad 音乐播放", 24), -3132);
         String time = this.timeLabel(elapsed, duration);
         this.text(g, 412 - Minecraft.getInstance().font.width(time), 184, time, -7743745);
         this.drawProgressPercent(g, 36, 199, 376, 5, view.mediaProgress());
         this.drawStopButton(g, 146, 210, 30, 24, -1440996816, -29813);
         this.playPauseButton(g, 200, 206, 46, 32, playing ? -579806977 : -570437010, -16314344, playing);
         this.smallToggle(g, 270, 210, 54, 24, view.subtitleMenuOpen() ? -1437780632 : -1440733643, "字幕");
         if (view.subtitleMenuOpen()) {
            this.drawSubtitleMenu(g, view);
         }
      }
   }

   private void drawLockedVideoControls(GuiGraphics g, PadGuiViewState view) {
      UUID deviceId = view.deviceId();
      boolean playing = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
      long elapsed = deviceId != null ? Math.max(0L, ClientMediaPlayback.elapsedMillis(deviceId)) : 0L;
      long duration = deviceId != null ? Math.max(0L, ClientMediaPlayback.durationMillis(deviceId)) : 0L;
      String title = deviceId != null ? ClientMediaPlayback.songName(deviceId) : "";
      this.fill(g, 0, 0, 448, 10, -1727723764);
      this.fill(g, 0, 246, 448, 10, -1727723764);
      this.fill(g, 0, 10, 10, 236, -1727723764);
      this.fill(g, 438, 10, 10, 236, -1727723764);
      this.outline(g, 7, 7, 434, 242, 1713844288);
      this.fill(g, 14, 14, 420, 26, 1996488704);
      this.text(g, 25, 21, "Pad 视频播放", -1379585);
      this.text(g, 128, 21, playing ? "LIVE PLAYBACK" : "PAUSED", playing ? -9568336 : -15251);
      this.smallToggle(g, 278, 18, 44, 16, view.subtitleMenuOpen() ? -1437780632 : -1440733643, "字幕");
      this.text(g, 333, 22, view.qualityLabel(), -2298881);
      String right = this.timeLabel(elapsed, duration);
      this.text(g, 424 - Minecraft.getInstance().font.width(right), 21, right, -7743745);
      this.fill(g, 16, 171, 416, 66, -1442511092);
      this.drawProgressPercent(g, 32, 184, 384, 6, view.mediaProgress());
      this.text(g, 32, 196, this.formatTime(elapsed), -4667938);
      this.text(g, 388, 196, this.formatTime(duration), -4667938);
      this.drawStopButton(g, 146, 204, 30, 24, -1440996816, -29813);
      this.playPauseButton(g, 200, 198, 46, 34, playing ? -579806977 : -570437010, -16314344, playing);
      this.smallToggle(g, 270, 204, 54, 24, view.qualityMenuOpen() ? -1440528023 : -1440733643, "画质");
      this.centeredText(g, 224, 225, this.trim(title != null && !title.isBlank() ? title : "Pad 播放中", 28), -3132);
      this.drawLockedVideoSubtitle(g, view, 145);
      if (view.subtitleMenuOpen()) {
         this.drawSubtitleMenu(g, view);
      }

      if (view.qualityMenuOpen()) {
         this.drawQualityMenu(g, view);
      }
   }

   private void drawSubtitleMenu(GuiGraphics g, PadGuiViewState view) {
      this.fill(g, 232, 48, 108, 114, -300936162);
      this.text(g, 248, 55, "字幕设置", -7549953);
      this.subtitleOption(g, 244, 72, 52, 14, "关", !view.subtitlesEnabled());
      this.subtitleOption(g, 244, 94, 52, 14, "主", view.subtitlesEnabled() && view.subtitlePrimaryMode());
      this.subtitleOption(g, 244, 116, 52, 14, "副", view.subtitlesEnabled() && !view.subtitlePrimaryMode());
      this.subtitleOption(g, 244, 138, 82, 14, "AI字幕", view.subtitleAiEnabled());
   }

   private void drawQualityMenu(GuiGraphics g, PadGuiViewState view) {
      this.fill(g, 318, 42, 94, 132, -300936162);
      this.text(g, 350, 50, "画质", -7549953);

      for (int i = 0; i < PadFocusState.QUALITIES.length; i++) {
         String quality = PadFocusState.QUALITIES[i];
         boolean selected = view.qualityLabel().equals(quality);
         this.fill(g, 330, 65 + i * 13, 70, 11, selected ? -14271649 : -15262422);
         this.outline(g, 330, 65 + i * 13, 70, 11, selected ? -9123841 : -13024940);
         this.text(g, 336, 66 + i * 13, quality, selected ? -1379585 : -4667938);
      }
   }

   private void subtitleOption(GuiGraphics g, int x, int y, int w, int h, String label, boolean selected) {
      this.fill(g, x, y, w, h, selected ? -14271649 : -15262422);
      this.outline(g, x, y, h, h, selected ? -9123841 : -10853512);
      if (selected) {
         this.text(g, x + 3, y + 2, "✓", -1379585);
      }

      this.text(g, x + h + 5, y + 2, label, -1379585);
   }

   private void drawLockedVideoSubtitle(GuiGraphics g, PadGuiViewState view, int y) {
      if (view.subtitlesEnabled()) {
         UUID deviceId = view.deviceId();
         String subtitle = deviceId != null ? MP4HandheldVideoClient.currentSubtitle(deviceId) : "";
         if (subtitle != null && !subtitle.isBlank()) {
            this.drawVideoSubtitle(g, subtitle, y);
         }
      }
   }

   private void drawVideoSubtitle(GuiGraphics g, String subtitle, int y) {
      Font font = Minecraft.getInstance().font;
      int textWidth = Math.min(408, font.width(subtitle));
      int bgWidth = Math.min(420, Math.max(36, textWidth + 18));
      int bgX = 224 - bgWidth / 2;
      this.fill(g, bgX, y - 3, bgWidth, 15, 1996488704);
      this.fill(g, bgX + 1, y - 2, bgWidth - 2, 13, 1428172872);
      this.centeredText(g, 224, y, subtitle, -220402689);
   }

   private void drawProgressPercent(GuiGraphics g, int x, int y, int w, int h, float progress) {
      this.fill(g, x, y, w, h, -13486006);
      int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
      this.fill(g, x, y, Math.min(w, filled), h, -9123841);
      this.fill(g, x + Math.min(w, filled) - h, y - h / 2, h * 2, h * 2, -6511699);
      this.outline(g, x + Math.min(w, filled) - h, y - h / 2, h * 2, h * 2, -11841187);
   }

   private void drawStopButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg) {
      this.fill(g, x + 3, y + 4, w, h, 1426063360);
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      int side = Math.min(w, h) / 3;
      this.fill(g, x + w / 2 - side / 2, y + h / 2 - side / 2, side, side, fg);
   }

   private void playPauseButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg, boolean playing) {
      this.fill(g, x + 3, y + 4, w, h, 1426063360);
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      if (playing) {
         int barW = Math.max(2, w / 14);
         int barH = Math.min(w, h) / 3;
         int gap = Math.max(2, w / 18);
         this.fill(g, x + w / 2 - gap / 2 - barW, y + h / 2 - barH / 2, barW, barH, fg);
         this.fill(g, x + w / 2 + gap / 2, y + h / 2 - barH / 2, barW, barH, fg);
      } else {
         this.drawTriangleForward(g, x + w / 2, y + h / 2, fg);
      }
   }

   private void drawTriangleForward(GuiGraphics g, int cx, int cy, int color) {
      int left = cx - PLAY_TRIANGLE_ROWS[PLAY_TRIANGLE_ROWS.length / 2] / 2;
      int top = cy - PLAY_TRIANGLE_ROWS.length / 2;

      for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
         this.fill(g, left, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
      }
   }

   private void smallToggle(GuiGraphics g, int x, int y, int w, int h, int bg, String label) {
      this.fill(g, x, y, w, h, bg);
      this.outline(g, x, y, w, h, -13024940);
      this.centeredText(g, x + w / 2, y + h / 2 - 4, label, -2298881);
   }

   private String mediaName(PadMediaEntry entry) {
      SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
      return info != null && info.songName != null && !info.songName.isBlank() ? info.songName : "歌曲 #" + entry.mediaId();
   }

   private String trim(String value, int maxChars) {
      if (value != null && value.length() > maxChars) {
         return value.substring(0, Math.max(0, maxChars - 1)) + "…";
      } else {
         return value == null ? "" : value;
      }
   }

   private void drawPlaybackCard(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
      this.fill(g, x, y, w, h, -15656153);
      this.outline(g, x, y, w, h, -13677984);
      UUID deviceId = view.deviceId();
      if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
         String song = ClientMediaPlayback.songName(deviceId);
         long elapsed = ClientMediaPlayback.elapsedMillis(deviceId);
         long duration = ClientMediaPlayback.durationMillis(deviceId);
         this.centeredText(g, x + w / 2, y + 6, this.trim(song.isBlank() ? "Pad 播放中" : song, 14), -3132);
         this.drawProgressBar(g, x + 10, y + 22, w - 20, 5, elapsed, duration);
         this.centeredText(g, x + w / 2, y + 31, this.timeLabel(elapsed, duration) + " · 点按停止", -7743745);
      } else {
         this.centeredText(g, x + w / 2, y + 9, "Pad 播放目标", -1515320);
         this.centeredText(g, x + w / 2, y + 25, "点击点位播放", -7743745);
      }
   }

   private void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, long elapsed, long duration) {
      this.fill(g, x, y, w, h, -14405053);
      int filled = duration > 0L ? Math.round((float)(Math.max(0L, Math.min(duration, elapsed)) * w) / (float)duration) : 0;
      if (filled > 0) {
         this.fill(g, x, y, Math.min(w, filled), h, -9444726);
      }

      this.outline(g, x, y, w, h, -13677984);
   }

   private String timeLabel(long elapsed, long duration) {
      return duration <= 0L ? this.formatTime(elapsed) : this.formatTime(elapsed) + "/" + this.formatTime(duration);
   }

   private String formatTime(long millis) {
      long seconds = Math.max(0L, millis) / 1000L;
      return seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L);
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

   private void text(GuiGraphics g, int x, int y, String value, int color) {
      Font font = Minecraft.getInstance().font;
      if (value != null && !value.isEmpty()) {
         g.drawString(font, value, x, y, color);
      }
   }

   private void centeredText(GuiGraphics g, int centerX, int y, String value, int color) {
      Font font = Minecraft.getInstance().font;
      if (value != null && !value.isEmpty()) {
         g.drawString(font, Component.literal(value), centerX - font.width(value) / 2, y, color);
      }
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
      this.mapRenderContext.close();
   }
}
