package com.zhongbai233.net_music_can_play_bili.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoVisualSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortDebugRenderTypes;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortWorldRenderEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

@EventBusSubscriber(
   modid = "net_music_can_play_bili",
   value = {Dist.CLIENT}
)
public final class VideoPlaybackDebugRenderer {
   private static final int PANEL_MAX_WIDTH = 430;
   private static final int PANEL_MARGIN = 5;
   private static final int PANEL_PADDING = 7;
   private static final int CARD_HEIGHT = 84;
   private static final int FULL_HEADER_HEIGHT = 58;
   private static final int PANEL_BACKGROUND = -669968864;
   private static final int CARD_BACKGROUND = -870702544;
   private static final int BAR_BACKGROUND = -13616567;
   private static final int TEXT_PRIMARY = -854017;
   private static final int TEXT_SECONDARY = -4667431;
   private static final int VISIBLE_COLOR = -531244936;
   private static final int PREDICTED_COLOR = -520107945;
   private static final int DECODING_COLOR = -530528001;
   private static final int FAILED_COLOR = -521839772;
   private static final int SYNC_WARNING_COLOR = -520107945;
   private static final int INACTIVE_COLOR = -1338424236;
   private static final int RANGE_COLOR = 1348520191;
   private static final int MAX_WORLD_SCREENS = 32;
   private static volatile PlaybackDebugMode mode = PlaybackDebugMode.OFF;

   private VideoPlaybackDebugRenderer() {
   }

   public static boolean enabled() {
      return mode.enabled();
   }

   public static boolean hudEnabled() {
      return mode.hudEnabled();
   }

   public static boolean rangeEnabled() {
      return mode.rangeEnabled();
   }

   public static PlaybackDebugMode mode() {
      return mode;
   }

   public static PlaybackDebugMode setMode(PlaybackDebugMode value) {
      mode = value != null ? value : PlaybackDebugMode.OFF;
      return mode;
   }

   public static boolean setEnabled(boolean value) {
      return setMode(value ? PlaybackDebugMode.BOTH : PlaybackDebugMode.OFF).enabled();
   }

   public static boolean toggle() {
      return setEnabled(!enabled());
   }

   public static List<String> describe() {
      List<String> result = new ArrayList<>();
      VideoBillboardState.ResourceDiagnostics resources = VideoBillboardPreview.resourceDiagnostics();
      List<VideoBillboardPreview.VideoDebugSnapshot> snapshots = VideoBillboardPreview.videoDebugSnapshots();
      int screens = snapshots.stream().mapToInt(snapshotx -> snapshotx.projectors().size()).sum();
      result.add(
         "视频调试模式=" + mode.name() + " 会话=" + snapshots.size() + " 屏幕=" + screens + " 解码=" + resources.runningInstances() + " 待解析=" + resources.pendingLoading()
      );

      for (VideoBillboardPreview.VideoDebugSnapshot snapshot : snapshots.stream().limit(12L).toList()) {
         long visible = snapshot.projectors().stream().filter(projector -> projector.submittedByFrustum()).count();
         long predicted = snapshot.projectors().stream().filter(projector -> projector.predictedVisible()).count();
         result.add(
            "video="
               + shortId(snapshot.sessionId())
               + " state="
               + state(snapshot)
               + " visible="
               + visible
               + "/"
               + snapshot.projectors().size()
               + " predicted="
               + predicted
               + " admission="
               + snapshot.decodeAdmission()
               + " prewarm="
               + snapshot.prewarm()
               + " paused="
               + snapshot.offscreenPaused()
               + " sync="
               + VideoVisualSyncPolicy.debugStatus(snapshot.syncActive())
               + " frame="
               + snapshot.hasFrame()
               + " expected="
               + formatMillis(snapshot.expectedMediaMillis())
               + " video="
               + formatMillis(snapshot.mediaMillis())
               + " queued="
               + formatMillis(snapshot.queuedMediaMillis())
               + " drift="
               + syncDelta(snapshot)
         );
      }

      return List.copyOf(result);
   }

   @SubscribeEvent
   public static void onSubmitCustomGeometry(RenderLevelStageEvent event) {
      if (rangeEnabled() && PortWorldRenderEvents.isStage(event, Stage.AFTER_TRANSLUCENT_BLOCKS)) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.getPosition();
            List<VideoPlaybackDebugRenderer.DebugLine> lines = new ArrayList<>();
            submitWorld(lines, cameraPos);
            submitLinesGroupedByWidth(lines);
         }
      }
   }

   private static void submitLinesGroupedByWidth(List<VideoPlaybackDebugRenderer.DebugLine> lines) {
      if (!lines.isEmpty()) {
         Map<Float, List<VideoPlaybackDebugRenderer.DebugLine>> grouped = new TreeMap<>();

         for (VideoPlaybackDebugRenderer.DebugLine debugLine : lines) {
            grouped.computeIfAbsent(debugLine.width, ignored -> new ArrayList<>()).add(debugLine);
         }

         PortSubmitNodeCollector collector = PortWorldRenderEvents.begin();

         try {
            PoseStack poseStack = new PoseStack();

            for (Entry<Float, List<VideoPlaybackDebugRenderer.DebugLine>> entry : grouped.entrySet()) {
               List<VideoPlaybackDebugRenderer.DebugLine> group = entry.getValue();
               collector.submitCustomGeometry(
                  poseStack,
                  PortDebugRenderTypes.translucentLines(entry.getKey()),
                  (pose, buffer) -> {
                     for (VideoPlaybackDebugRenderer.DebugLine debugLine : group) {
                        buffer.addVertex(pose, debugLine.x1, debugLine.y1, debugLine.z1)
                           .setColor(debugLine.color)
                           .setNormal(pose, debugLine.nx, debugLine.ny, debugLine.nz);
                        buffer.addVertex(pose, debugLine.x2, debugLine.y2, debugLine.z2)
                           .setColor(debugLine.color)
                           .setNormal(pose, debugLine.nx, debugLine.ny, debugLine.nz);
                     }
                  }
               );
            }
         } finally {
            PortWorldRenderEvents.end(collector);
         }
      }
   }

   @SubscribeEvent
   public static void onRenderGui(Post event) {
      if (hudEnabled()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.font != null) {
            int screenWidth = minecraft.getWindow().getGuiScaledWidth();
            int screenHeight = minecraft.getWindow().getGuiScaledHeight();
            boolean dualPanels = PlaybackRangeDebugRenderer.hudEnabled();
            if (screenHeight >= 60) {
               List<VideoBillboardPreview.VideoDebugSnapshot> snapshots = VideoBillboardPreview.videoDebugSnapshots();
               int visibleCards = Math.min(4, snapshots.size());
               boolean hasOverflow = snapshots.size() > visibleCards;
               int panelHeight = 58 + visibleCards * 84 + (hasOverflow ? 10 : 0);
               DebugHudLayout.Plan layout = DebugHudLayout.plan(screenWidth, screenHeight, dualPanels, 430, panelHeight, 5);
               if (layout.visible()) {
                  GuiGraphics graphics = event.getGuiGraphics();
                  Font font = minecraft.font;
                  PoseStack pose = graphics.pose();
                  pose.pushPose();
                  pose.translate(screenWidth - 5 - layout.renderedWidth(), 5.0F, 0.0F);
                  pose.scale(layout.scale(), layout.scale(), 1.0F);
                  int left = 0;
                  int top = 0;
                  int innerWidth = 416;
                  graphics.fill(left, top, left + 430, top + panelHeight, -669968864);
                  graphics.fill(left + 430 - 3, top, left + 430, top + panelHeight, -530528001);
                  graphics.drawString(font, "视频视锥 / 解码调试", left + 7, top + 6, -854017, false);
                  VideoBillboardState.ResourceDiagnostics resources = VideoBillboardPreview.resourceDiagnostics();
                  int screens = snapshots.stream().mapToInt(snapshot -> snapshot.projectors().size()).sum();
                  String summary = "会话 "
                     + snapshots.size()
                     + "  屏幕 "
                     + screens
                     + "  解码 "
                     + resources.runningInstances()
                     + "  待解析 "
                     + resources.pendingLoading();
                  drawWrapped(graphics, font, summary, left + 7, top + 18, innerWidth, -4667431, 1);
                  drawLegend(graphics, font, left + 7, top + 31, innerWidth);
                  int y = top + 58;

                  for (int index = 0; index < visibleCards; index++) {
                     drawCard(graphics, font, snapshots.get(index), left + 7, y, innerWidth, index);
                     y += 84;
                  }

                  if (hasOverflow) {
                     drawWrapped(
                        graphics,
                        font,
                        "还有 " + (snapshots.size() - visibleCards) + " 个会话，使用 video dump 查看",
                        left + 7,
                        top + panelHeight - 9,
                        innerWidth,
                        -4667431,
                        1
                     );
                  }

                  pose.popPose();
               }
            }
         }
      }
   }

   private static void drawLegend(GuiGraphics graphics, Font font, int x, int y, int width) {
      String[] labels = new String[]{"当前可见", "趋势预热", "解码", "异常"};
      int[] colors = new int[]{-531244936, -520107945, -530528001, -521839772};
      int cursor = x;

      for (int index = 0; index < labels.length; index++) {
         int itemWidth = 9 + font.width(labels[index]) + 7;
         if (cursor + itemWidth > x + width) {
            break;
         }

         graphics.fill(cursor, y + 2, cursor + 6, y + 8, colors[index]);
         graphics.drawString(font, labels[index], cursor + 9, y, -4667431, false);
         cursor += itemWidth;
      }
   }

   private static void drawCard(GuiGraphics graphics, Font font, VideoBillboardPreview.VideoDebugSnapshot snapshot, int x, int y, int width, int index) {
      graphics.fill(x, y + 2, x + width, y + 84 - 2, index % 2 == 0 ? -870702544 : -870834132);
      int stateColor = snapshot.failed()
         ? -521839772
         : (!snapshot.syncActive() ? -1338424236 : (snapshot.hasFrame() ? -531244936 : (snapshot.decodeAdmission() ? -530528001 : -1338424236)));
      graphics.fill(x, y + 2, x + 3, y + 84 - 2, stateColor);
      String projector = snapshot.projectors().isEmpty()
         ? "虚拟屏幕"
         : pos(snapshot.projectors().get(0).projectorPos()) + (snapshot.projectors().size() > 1 ? " +" + (snapshot.projectors().size() - 1) : "");
      String title = shortId(snapshot.sessionId()) + "  " + projector + "  " + snapshot.width() + "x" + snapshot.height();
      graphics.drawString(font, title, x + 7, y + 6, -854017, false);
      boolean visible = snapshot.projectors().stream().anyMatch(projectorSnapshot -> projectorSnapshot.submittedByFrustum());
      boolean predicted = snapshot.projectors().stream().anyMatch(projectorSnapshot -> projectorSnapshot.predictedVisible());
      boolean[] stages = new boolean[]{visible, predicted, snapshot.decodeAdmission(), snapshot.hasFrame()};
      String[] labels = new String[]{"视锥", "趋势", "解码", "帧"};
      int[] colors = new int[]{-531244936, -520107945, -530528001, -531244936};
      int segmentWidth = Math.max(1, (width - 14 - 6) / 4);

      for (int stage = 0; stage < 4; stage++) {
         int sx = x + 7 + stage * (segmentWidth + 2);
         graphics.fill(sx, y + 19, sx + segmentWidth, y + 29, stages[stage] ? colors[stage] : -1338424236);
         if (font.width(labels[stage]) + 4 <= segmentWidth) {
            graphics.drawString(font, labels[stage], sx + 2, y + 20, -15723235, false);
         }
      }

      drawSyncMeter(graphics, font, snapshot, x + 7, y + 34, width - 14);
      String detail = state(snapshot)
         + " · admission="
         + onOff(snapshot.decodeAdmission())
         + " · prewarm="
         + onOff(snapshot.prewarm())
         + " · pause="
         + onOff(snapshot.offscreenPaused());
      drawWrapped(graphics, font, detail, x + 7, y + 48, width - 14, -4667431, 1);
      String timing = "目标 "
         + formatMillis(snapshot.expectedMediaMillis())
         + " · 画面 "
         + formatMillis(snapshot.mediaMillis())
         + " · 队列 "
         + formatMillis(snapshot.queuedMediaMillis())
         + " · visual "
         + formatMillis(snapshot.visualMillis())
         + " · pacing "
         + formatMillis(snapshot.pacingMillis())
         + " · "
         + snapshot.fps()
         + "fps "
         + snapshot.backend()
         + " · gen "
         + snapshot.generation()
         + " "
         + snapshot.restartState();
      drawWrapped(graphics, font, timing, x + 7, y + 60, width - 14, -4667431, 2);
   }

   private static void drawSyncMeter(GuiGraphics graphics, Font font, VideoBillboardPreview.VideoDebugSnapshot snapshot, int x, int y, int width) {
      long expected = snapshot.expectedMediaMillis();
      if (!snapshot.syncActive()) {
         graphics.fill(x, y, x + width, y + 10, -13616567);
         graphics.drawString(font, "视频同步 离屏暂停", x + 3, y + 1, -4667431, false);
      } else {
         long video = snapshot.mediaMillis();
         float health = PlaybackRangeDebugRenderer.syncBarProgress(video, expected);
         long drift = validMillis(video, expected) ? video - expected : Long.MAX_VALUE;
         int color = drift == Long.MAX_VALUE ? -1338424236 : (Math.abs(drift) <= 250L ? -531244936 : (Math.abs(drift) <= 1000L ? -520107945 : -521839772));
         graphics.fill(x, y, x + width, y + 10, -13616567);
         graphics.fill(x, y, x + Math.round(width * health), y + 10, color);
         String label = "视频同步 " + signedDelta(video, expected) + " · 队列 " + signedDelta(snapshot.queuedMediaMillis(), expected);
         graphics.drawString(font, label, x + 3, y + 1, -854017, false);
      }
   }

   private static void drawWrapped(GuiGraphics graphics, Font font, String value, int x, int y, int width, int color, int lines) {
      List<String> wrapped = PlaybackRangeDebugRenderer.wrapText(value, width, font::width);

      for (int index = 0; index < Math.min(lines, wrapped.size()); index++) {
         graphics.drawString(font, wrapped.get(index), x, y + index * 9, color, false);
      }
   }

   private static void submitWorld(List<VideoPlaybackDebugRenderer.DebugLine> lines, Vec3 cameraPos) {
      int count = 0;

      for (VideoBillboardPreview.VideoDebugSnapshot snapshot : VideoBillboardPreview.videoDebugSnapshots()) {
         double aspect = snapshot.width() > 0 && snapshot.height() > 0 ? (double)snapshot.width() / snapshot.height() : 1.7777777777777777;

         for (VideoBillboardPreview.ProjectorVideoDebugSnapshot projectorSnapshot : snapshot.projectors()) {
            if (count++ >= 32) {
               return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
               int color = snapshot.failed()
                  ? -521839772
                  : (
                     projectorSnapshot.submittedByFrustum()
                        ? -531244936
                        : (projectorSnapshot.predictedVisible() ? -520107945 : (snapshot.decodeAdmission() ? -530528001 : -1338424236))
                  );
               if (minecraft.level.getBlockEntity(projectorSnapshot.projectorPos()) instanceof VideoProjectorBlockEntity projector) {
                  drawScreen(lines, cameraPos, List.of(screenCorners(projector, aspect)), color);
               } else {
                  for (ControlConsoleRenderer.VideoElementRangeDebugSnapshot element : ControlConsoleRenderer.videoElementRangeDebugSnapshots()) {
                     if (element.consolePos().equals(projectorSnapshot.projectorPos())) {
                        drawScreen(lines, cameraPos, element.corners(), color);
                     }
                  }
               }
            }
         }
      }
   }

   private static void drawScreen(List<VideoPlaybackDebugRenderer.DebugLine> lines, Vec3 cameraPos, List<Vec3> corners, int color) {
      if (corners != null && corners.size() == 4) {
         for (int edge = 0; edge < 4; edge++) {
            line(lines, cameraPos, corners.get(edge), corners.get((edge + 1) % 4), color, 2.6F);
         }

         Vec3 center = corners.get(0).add(corners.get(2)).scale(0.5);
         cross(lines, cameraPos, center, 0.35, color, 2.2F);
         horizontalRing(lines, cameraPos, center, VideoBillboardPreview.maxRenderDistance(), 1348520191, 1.0F);
      }
   }

   private static Vec3[] screenCorners(VideoProjectorBlockEntity projector, double aspect) {
      BlockPos pos = projector.getBlockPos();
      Vec3 center = new Vec3(
         pos.getX() + 0.5 + projector.getProjectionDistanceX(),
         pos.getY() + projector.getProjectionHeight(),
         pos.getZ() + 0.5 + projector.getProjectionDistanceZ()
      );
      double halfHeight = 1.35 * Math.abs(projector.getProjectionScale()) * 0.5;
      double halfWidth = halfHeight * Math.max(0.125, Math.min(8.0, aspect));
      double yaw = Math.toRadians(projector.getProjectionYaw());
      double pitch = Math.toRadians(projector.getProjectionPitch());
      Vec3 right = new Vec3(Math.cos(yaw) * halfWidth, 0.0, Math.sin(yaw) * halfWidth);
      Vec3 up = new Vec3(-Math.sin(yaw) * Math.sin(pitch) * halfHeight, Math.cos(pitch) * halfHeight, Math.cos(yaw) * Math.sin(pitch) * halfHeight);
      return new Vec3[]{center.subtract(right).add(up), center.subtract(right).subtract(up), center.add(right).subtract(up), center.add(right).add(up)};
   }

   private static void horizontalRing(List<VideoPlaybackDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, double radius, int color, float width) {
      if (radius > 0.0) {
         for (int index = 0; index < 24; index++) {
            double a = (Math.PI * 2) * index / 24.0;
            double b = (Math.PI * 2) * (index + 1) / 24.0;
            line(
               lines,
               cameraPos,
               center.add(Math.cos(a) * radius, 0.0, Math.sin(a) * radius),
               center.add(Math.cos(b) * radius, 0.0, Math.sin(b) * radius),
               color,
               width
            );
         }
      }
   }

   private static void cross(List<VideoPlaybackDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, double radius, int color, float width) {
      line(lines, cameraPos, center.add(-radius, 0.0, 0.0), center.add(radius, 0.0, 0.0), color, width);
      line(lines, cameraPos, center.add(0.0, -radius, 0.0), center.add(0.0, radius, 0.0), color, width);
      line(lines, cameraPos, center.add(0.0, 0.0, -radius), center.add(0.0, 0.0, radius), color, width);
   }

   private static void line(List<VideoPlaybackDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 from, Vec3 to, int color, float width) {
      float x1 = (float)(from.x - cameraPos.x);
      float y1 = (float)(from.y - cameraPos.y);
      float z1 = (float)(from.z - cameraPos.z);
      float x2 = (float)(to.x - cameraPos.x);
      float y2 = (float)(to.y - cameraPos.y);
      float z2 = (float)(to.z - cameraPos.z);
      float dx = x2 - x1;
      float dy = y2 - y1;
      float dz = z2 - z1;
      float inverse = 1.0F / Math.max(1.0E-6F, (float)Math.sqrt(dx * dx + dy * dy + dz * dz));
      lines.add(new VideoPlaybackDebugRenderer.DebugLine(x1, y1, z1, x2, y2, z2, dx * inverse, dy * inverse, dz * inverse, color, width));
   }

   private static String state(VideoBillboardPreview.VideoDebugSnapshot snapshot) {
      if (snapshot.failed()) {
         return "FAILED";
      } else if (!snapshot.running()) {
         return "STOPPED";
      } else if (snapshot.offscreenPaused()) {
         return "OFFSCREEN_PAUSED";
      } else if (!snapshot.decodeAdmission()) {
         return "WAIT_VISIBLE";
      } else if (!snapshot.syncActive()) {
         return "OFFSCREEN_IDLE";
      } else {
         return !snapshot.hasFrame() ? "DECODING" : "PLAYING";
      }
   }

   private static String pos(BlockPos pos) {
      return pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   private static String onOff(boolean value) {
      return value ? "ON" : "OFF";
   }

   private static boolean validMillis(long first, long second) {
      return first >= 0L && second >= 0L;
   }

   private static String syncDelta(VideoBillboardPreview.VideoDebugSnapshot snapshot) {
      return snapshot.syncActive() ? signedDelta(snapshot.mediaMillis(), snapshot.expectedMediaMillis()) : "SUSPENDED";
   }

   private static String formatMillis(long value) {
      return value >= 0L ? value + "ms" : "-";
   }

   static String signedDelta(long actual, long expected) {
      if (!validMillis(actual, expected)) {
         return "-";
      } else {
         long delta = actual - expected;
         return (delta >= 0L ? "+" : "") + delta + "ms";
      }
   }

   private static String shortId(String value) {
      if (value == null) {
         return "-";
      } else {
         return value.length() <= 12 ? value : value.substring(0, 12);
      }
   }

   private record DebugLine(float x1, float y1, float z1, float x2, float y2, float z2, float nx, float ny, float nz, int color, float width) {
   }
}
