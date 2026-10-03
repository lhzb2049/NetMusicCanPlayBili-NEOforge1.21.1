package com.zhongbai233.net_music_can_play_bili.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaDemandScheduler;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortDebugRenderTypes;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortWorldRenderEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.function.ToIntFunction;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
public final class PlaybackRangeDebugRenderer {
   private static final int NOMINAL_COLOR = -867631248;
   private static final int RESOLVE_COLOR = -654319534;
   private static final int NOTICE_COLOR = -1191210675;
   private static final int SYNC_COLOR = -1872705281;
   private static final int CONSOLE_ACTIVE_COLOR = -926193153;
   private static final int CONSOLE_IDLE_COLOR = -2005251426;
   private static final int DIRECT_ROUTE_COLOR = -654350632;
   private static final int MAX_WORLD_ENTRIES = 128;
   private static final int PANEL_MAX_WIDTH = 430;
   private static final int PANEL_MARGIN = 5;
   private static final int PANEL_PADDING = 7;
   private static final int CARD_HEIGHT = 72;
   private static final int FULL_HEADER_HEIGHT = 86;
   private static final int PANEL_BACKGROUND = -669968864;
   private static final int CARD_BACKGROUND = -870702544;
   private static final int BAR_BACKGROUND = -13616567;
   private static final int TEXT_PRIMARY = -854017;
   private static final int TEXT_SECONDARY = -4667431;
   private static final int STATE_METADATA = -10514945;
   private static final int STATE_STARTING = -14249;
   private static final int STATE_PLAYING = -11151240;
   private static final int STATE_INACTIVE = -13024172;
   private static volatile PlaybackDebugMode mode = PlaybackDebugMode.OFF;

   private PlaybackRangeDebugRenderer() {
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
      List<String> lines = new ArrayList<>();
      List<ModernTurntablePlaybackCoordinator.IndexedDemandDebug> indexed = ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots();
      List<ClientMediaDemandScheduler.DemandDebug> handheld = ClientMediaDemandScheduler.debugSnapshots();
      List<IndexedAudioEndpoint> endpoints = ClientAudioEndpointIndex.endpointSnapshot();
      List<ControlConsoleRenderer.RangeDebugSnapshot> consoles = ControlConsoleRenderer.rangeDebugSnapshots();
      List<ControlConsoleRenderer.ElementRangeDebugSnapshot> consoleElements = ControlConsoleRenderer.elementRangeDebugSnapshots();
      lines.add(
         "音频调试模式="
            + mode.name()
            + " 来源="
            + indexed.size()
            + " 端点="
            + endpoints.size()
            + " 中控音频元素="
            + consoleElements.size()
            + " 移动媒体="
            + handheld.size()
            + " 中控硬范围="
            + consoles.size()
      );

      for (ModernTurntablePlaybackCoordinator.IndexedDemandDebug source : indexed.stream().limit(12L).toList()) {
         ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(source.sourcePos());
         long bufferMillis = bufferLeadMillis(timeline.audibleMillis(), timeline.mainFedMillis());
         ClientAudioOutputRegistry.AudioDemandDebug demand = ClientAudioOutputRegistry.audioDemandDebug(
            source.sourcePos(), source.sourceId(), source.sessionId().value()
         );
         lines.add(
            "source="
               + shortId(source.sourceId().toString())
               + " session="
               + shortId(source.sessionId().value())
               + " state="
               + source.state()
               + " demand="
               + source.demandCount()
               + " resolving="
               + source.preparingUrl()
               + " route="
               + describeRoute(demand)
               + " volume="
               + percent(source.volume())
               + " audible="
               + timeline.audibleMillis()
               + "ms fed="
               + timeline.mainFedMillis()
               + "ms buffer="
               + bufferMillis
               + "ms("
               + bufferHealth(bufferMillis)
               + ") relays="
               + timeline.relayStartedCount()
               + "/"
               + timeline.relayRegisteredCount()
               + " outputSession="
               + shortId(timeline.audioSessionId())
         );
      }

      for (ClientMediaDemandScheduler.DemandDebug media : handheld.stream().limit(8L).toList()) {
         lines.add(
            "media="
               + shortId(media.sourceId().toString())
               + " session="
               + shortId(media.sessionId().value())
               + " state="
               + media.state()
               + " demand="
               + media.demandCount()
               + " route="
               + (media.headphoneRouted() ? "HEADPHONES" : "SPATIAL")
               + " volume="
               + percent(media.volume())
         );
      }

      return List.copyOf(lines);
   }

   @SubscribeEvent
   public static void onSubmitGeometry(RenderLevelStageEvent event) {
      if (rangeEnabled() && PortWorldRenderEvents.isStage(event, Stage.AFTER_TRANSLUCENT_BLOCKS)) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.getPosition();
            List<PlaybackRangeDebugRenderer.DebugLine> lines = new ArrayList<>();
            submitWorld(lines, cameraPos);
            submitLinesGroupedByWidth(lines);
         }
      }
   }

   private static void submitLinesGroupedByWidth(List<PlaybackRangeDebugRenderer.DebugLine> lines) {
      if (!lines.isEmpty()) {
         Map<Float, List<PlaybackRangeDebugRenderer.DebugLine>> grouped = new TreeMap<>();

         for (PlaybackRangeDebugRenderer.DebugLine debugLine : lines) {
            grouped.computeIfAbsent(debugLine.width, ignored -> new ArrayList<>()).add(debugLine);
         }

         PortSubmitNodeCollector collector = PortWorldRenderEvents.begin();

         try {
            PoseStack poseStack = new PoseStack();

            for (Entry<Float, List<PlaybackRangeDebugRenderer.DebugLine>> entry : grouped.entrySet()) {
               List<PlaybackRangeDebugRenderer.DebugLine> group = entry.getValue();
               collector.submitCustomGeometry(
                  poseStack,
                  PortDebugRenderTypes.translucentLines(entry.getKey()),
                  (pose, buffer) -> {
                     for (PlaybackRangeDebugRenderer.DebugLine debugLine : group) {
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
            if (screenWidth > 90 && screenHeight > 50) {
               boolean dualPanels = VideoPlaybackDebugRenderer.hudEnabled();
               List<PlaybackRangeDebugRenderer.HudEntry> entries = hudEntries();
               int visibleCards = Math.min(4, entries.size());
               boolean hasOverflow = entries.size() > visibleCards;
               int panelHeight = 86 + visibleCards * 72 + (hasOverflow ? 10 : 0);
               DebugHudLayout.Plan layout = DebugHudLayout.plan(screenWidth, screenHeight, dualPanels, 430, panelHeight, 5);
               if (layout.visible()) {
                  GuiGraphics graphics = event.getGuiGraphics();
                  Font font = minecraft.font;
                  PoseStack pose = graphics.pose();
                  pose.pushPose();
                  pose.translate(5.0F, 5.0F, 0.0F);
                  pose.scale(layout.scale(), layout.scale(), 1.0F);
                  int left = 0;
                  int top = 0;
                  int innerWidth = 416;
                  graphics.fill(left, top, left + 430, top + panelHeight, -669968864);
                  graphics.fill(left, top, left + 3, top + panelHeight, -10434305);
                  graphics.drawString(font, "音频播放范围调试", left + 7, top + 6, -854017, false);
                  PlaybackRangeDebugRenderer.DebugCounts counts = debugCounts();
                  String summary = "来源 "
                     + counts.sources()
                     + "  端点 "
                     + counts.endpoints()
                     + "  中控元素 "
                     + counts.consoleElements()
                     + "  移动媒体 "
                     + counts.handheld()
                     + "  硬范围 "
                     + counts.consoles();
                  drawWrapped(graphics, font::width, font, summary, left + 7, top + 18, innerWidth, -4667431, 9, 2);
                  int legendY = top + 39;
                  legendY = drawLegend(graphics, font, left + 7, legendY, innerWidth);
                  drawLifecycleGuide(graphics, font, left + 7, legendY + 3, innerWidth);
                  int cardY = top + 86;

                  for (int index = 0; index < visibleCards; index++) {
                     drawEntryCard(graphics, font, entries.get(index), left + 7, cardY, innerWidth, index);
                     cardY += 72;
                  }

                  if (hasOverflow) {
                     String overflow = "还有 " + (entries.size() - visibleCards) + " 项，使用 /ncpbc debug playback audio dump 查看详情";
                     drawWrapped(graphics, font::width, font, overflow, left + 7, top + panelHeight - 9, innerWidth, -4667431, 9, 1);
                  }

                  pose.popPose();
               }
            }
         }
      }
   }

   private static PlaybackRangeDebugRenderer.DebugCounts debugCounts() {
      return new PlaybackRangeDebugRenderer.DebugCounts(
         ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots().size(),
         ClientAudioEndpointIndex.endpointSnapshot().size(),
         ControlConsoleRenderer.elementRangeDebugSnapshots().size(),
         ClientMediaDemandScheduler.debugSnapshots().size(),
         ControlConsoleRenderer.rangeDebugSnapshots().size()
      );
   }

   private static List<PlaybackRangeDebugRenderer.HudEntry> hudEntries() {
      List<PlaybackRangeDebugRenderer.HudEntry> entries = new ArrayList<>();

      for (ModernTurntablePlaybackCoordinator.IndexedDemandDebug source : ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots()
         .stream()
         .limit(12L)
         .toList()) {
         ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(source.sourcePos());
         long visualMillis = PlaybackClock.visualMillis(source.sourcePos());
         ClientAudioOutputRegistry.AudioDemandDebug demand = ClientAudioOutputRegistry.audioDemandDebug(
            source.sourcePos(), source.sourceId(), source.sessionId().value()
         );
         entries.add(
            new PlaybackRangeDebugRenderer.HudEntry(
               "音源",
               shortId(source.sourceId().toString()),
               shortId(source.sessionId().value()),
               source.state().name(),
               source.demandCount(),
               source.preparingUrl(),
               describeRoute(demand),
               source.volume(),
               timeline.audibleMillis(),
               timeline.mainFedMillis(),
               visualMillis,
               timeline.relayStartedCount(),
               timeline.relayRegisteredCount(),
               shortId(timeline.audioSessionId())
            )
         );
      }

      for (ClientMediaDemandScheduler.DemandDebug media : ClientMediaDemandScheduler.debugSnapshots().stream().limit(8L).toList()) {
         entries.add(
            new PlaybackRangeDebugRenderer.HudEntry(
               "移动",
               shortId(media.sourceId().toString()),
               shortId(media.sessionId().value()),
               media.state().name(),
               media.demandCount(),
               false,
               media.headphoneRouted() ? "耳机" : "空间声",
               media.volume(),
               -1L,
               -1L,
               -1L,
               0,
               0,
               "-"
            )
         );
      }

      return List.copyOf(entries);
   }

   private static int drawLegend(GuiGraphics graphics, Font font, int x, int y, int width) {
      List<PlaybackRangeDebugRenderer.LegendItem> items = List.of(
         new PlaybackRangeDebugRenderer.LegendItem("标称", -867631248),
         new PlaybackRangeDebugRenderer.LegendItem("淡出终点", -654319534),
         new PlaybackRangeDebugRenderer.LegendItem("提示", -1191210675),
         new PlaybackRangeDebugRenderer.LegendItem("同步", -1872705281),
         new PlaybackRangeDebugRenderer.LegendItem("中控 AABB", -926193153)
      );
      int cursorX = x;
      int cursorY = y;

      for (PlaybackRangeDebugRenderer.LegendItem item : items) {
         int itemWidth = 9 + font.width(item.label()) + 8;
         if (cursorX > x && cursorX + itemWidth > x + width) {
            cursorX = x;
            cursorY += 10;
         }

         graphics.fill(cursorX, cursorY + 2, cursorX + 6, cursorY + 8, item.color());
         graphics.drawString(font, item.label(), cursorX + 9, cursorY, -4667431, false);
         cursorX += itemWidth;
      }

      return cursorY + 9;
   }

   private static void drawLifecycleGuide(GuiGraphics graphics, Font font, int x, int y, int width) {
      graphics.drawString(font, "重新进入", x, y, -4667431, false);
      int barX = x + 48;
      int barWidth = Math.max(90, width - 48);
      drawLifecycleSegments(graphics, font, barX, y, barWidth, 3);
   }

   private static void drawEntryCard(GuiGraphics graphics, Font font, PlaybackRangeDebugRenderer.HudEntry entry, int x, int y, int width, int index) {
      int background = index % 2 == 0 ? -870702544 : -870834132;
      graphics.fill(x, y + 2, x + width, y + 72 - 2, background);
      graphics.fill(x, y + 2, x + 3, y + 72 - 2, stateColor(entry.state()));
      String title = entry.kind() + " " + entry.source() + "  会话 " + entry.session() + "  " + entry.route();
      drawWrapped(graphics, font::width, font, title, x + 7, y + 6, width - 14, -854017, 9, 2);
      int stateLabelWidth = 58;
      graphics.drawString(font, entry.state(), x + 7, y + 26, stateColor(entry.state()), false);
      drawLifecycleSegments(graphics, font, x + 7 + stateLabelWidth, y + 26, width - 14 - stateLabelWidth, lifecycleStage(entry.state()));
      int meterGap = 8;
      int meterWidth = (width - 14 - meterGap) / 2;
      drawVolumeMeter(graphics, font, x + 7, y + 38, meterWidth, entry.volume());
      drawSyncMeter(graphics, font, x + 7 + meterWidth + meterGap, y + 38, meterWidth, entry.audibleMillis(), entry.visualMillis());
      long syncDrift = validTimeline(entry.audibleMillis(), entry.visualMillis()) ? entry.audibleMillis() - entry.visualMillis() : 0L;
      long bufferMillis = bufferLeadMillis(entry.audibleMillis(), entry.fedMillis());
      String details = "需求 "
         + entry.demands()
         + (entry.resolving() ? " · 解析中" : "")
         + " · relay "
         + entry.relayStarted()
         + "/"
         + entry.relayRegistered()
         + (validTimeline(entry.audibleMillis(), entry.visualMillis()) ? " · 播放差 " + signedMillis(syncDrift) : "")
         + (bufferMillis >= 0L ? " · 缓冲 " + bufferMillis + "ms " + bufferHealth(bufferMillis) : "")
         + " · 输出会话 "
         + entry.outputSession();
      drawWrapped(graphics, font::width, font, details, x + 7, y + 51, width - 14, -4667431, 9, 2);
   }

   private static void drawLifecycleSegments(GuiGraphics graphics, Font font, int x, int y, int width, int activeStage) {
      String[] labels = new String[]{"META", "START", "PLAY"};
      int[] colors = new int[]{-10514945, -14249, -11151240};
      int segmentWidth = Math.max(1, (width - 4) / 3);

      for (int index = 0; index < 3; index++) {
         int segmentX = x + index * (segmentWidth + 2);
         int color = index < activeStage ? colors[index] : -13024172;
         graphics.fill(segmentX, y + 1, segmentX + segmentWidth, y + 9, color);
         int labelWidth = font.width(labels[index]);
         if (labelWidth + 4 <= segmentWidth) {
            graphics.drawString(font, labels[index], segmentX + (segmentWidth - labelWidth) / 2, y + 1, -15723235, false);
         }
      }
   }

   private static void drawVolumeMeter(GuiGraphics graphics, Font font, int x, int y, int width, float volume) {
      String label = "音量 " + percent(volume);
      graphics.drawString(font, label, x, y, -4667431, false);
      int barX = x + Math.min(58, font.width(label) + 5);
      int barWidth = Math.max(12, width - (barX - x));
      graphics.fill(barX, y + 2, barX + barWidth, y + 8, -13616567);
      int normalWidth = Math.round(barWidth * Math.min(0.5F, volumeBarProgress(volume)));
      int boostWidth = Math.round(barWidth * Math.max(0.0F, volumeBarProgress(volume) - 0.5F));
      graphics.fill(barX, y + 2, barX + normalWidth, y + 8, -11151240);
      if (boostWidth > 0) {
         graphics.fill(barX + normalWidth, y + 2, Math.min(barX + barWidth, barX + normalWidth + boostWidth), y + 8, -1191210675);
      }

      graphics.fill(barX + barWidth / 2, y + 1, barX + barWidth / 2 + 1, y + 9, -855638017);
   }

   private static void drawSyncMeter(GuiGraphics graphics, Font font, int x, int y, int width, long audibleMillis, long visualMillis) {
      String label = !validTimeline(audibleMillis, visualMillis) ? "播放 -" : "播放同步";
      graphics.drawString(font, label, x, y, -4667431, false);
      int barX = x + Math.min(58, font.width(label) + 5);
      int barWidth = Math.max(12, width - (barX - x));
      float progress = syncBarProgress(audibleMillis, visualMillis);
      long drift = validTimeline(audibleMillis, visualMillis) ? Math.abs(audibleMillis - visualMillis) : Long.MAX_VALUE;
      int color = drift <= 250L ? -11151240 : (drift <= 500L ? -1872705281 : (drift < 2000L ? -654319534 : -1746076));
      graphics.fill(barX, y + 2, barX + barWidth, y + 8, -13616567);
      graphics.fill(barX, y + 2, barX + Math.round(barWidth * progress), y + 8, color);
   }

   private static void drawWrapped(
      GuiGraphics graphics, ToIntFunction<String> widthFunction, Font font, String value, int x, int y, int maxWidth, int color, int lineHeight, int maxLines
   ) {
      List<String> wrapped = wrapText(value, maxWidth, widthFunction);

      for (int index = 0; index < Math.min(maxLines, wrapped.size()); index++) {
         String line = wrapped.get(index);
         if (index == maxLines - 1 && wrapped.size() > maxLines) {
            line = ellipsize(line, maxWidth, widthFunction);
         }

         graphics.drawString(font, line, x, y + index * lineHeight, color, false);
      }
   }

   static List<String> wrapText(String value, int maxWidth, ToIntFunction<String> widthFunction) {
      if (value != null && !value.isBlank() && maxWidth > 0) {
         List<String> result = new ArrayList<>();
         String remaining = value.strip();

         while (!remaining.isEmpty()) {
            if (widthFunction.applyAsInt(remaining) <= maxWidth) {
               result.add(remaining);
               break;
            }

            int fit = 1;

            while (fit < remaining.length() && widthFunction.applyAsInt(remaining.substring(0, fit + 1)) <= maxWidth) {
               fit++;
            }

            int preferred = preferredBreak(remaining, fit);
            int end = preferred > 0 ? preferred : fit;
            String line = remaining.substring(0, Math.max(1, end)).stripTrailing();
            result.add(line);
            remaining = remaining.substring(Math.max(1, end)).stripLeading();
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   private static int preferredBreak(String value, int fit) {
      for (int index = Math.min(fit, value.length() - 1); index > 0; index--) {
         char character = value.charAt(index);
         if (Character.isWhitespace(character) || character == '|' || character == 183 || character == ',' || character == ';') {
            return index + (Character.isWhitespace(character) ? 0 : 1);
         }
      }

      return -1;
   }

   private static String ellipsize(String value, int maxWidth, ToIntFunction<String> widthFunction) {
      String ellipsis = "…";
      String result = value;

      while (!result.isEmpty() && widthFunction.applyAsInt(result + ellipsis) > maxWidth) {
         result = result.substring(0, result.length() - 1);
      }

      return result.stripTrailing() + ellipsis;
   }

   static int lifecycleStage(String state) {
      if (state == null) {
         return 0;
      } else {
         return switch (state) {
            case "PLAYING" -> 3;
            case "STARTING", "PREPARING", "BUFFERING", "RECOVERING" -> 2;
            case "METADATA" -> 1;
            default -> 0;
         };
      }
   }

   static float volumeBarProgress(float volume) {
      return !Float.isFinite(volume) ? 0.0F : Math.max(0.0F, Math.min(1.0F, volume / 2.0F));
   }

   static float syncBarProgress(long audibleMillis, long visualMillis) {
      if (audibleMillis >= 0L && visualMillis >= 0L) {
         long drift = Math.abs(visualMillis - audibleMillis);
         return Math.max(0.0F, 1.0F - Math.min(1.0F, (float)drift / 2000.0F));
      } else {
         return 0.0F;
      }
   }

   static long bufferLeadMillis(long audibleMillis, long fedMillis) {
      return !validTimeline(audibleMillis, fedMillis) ? -1L : Math.max(0L, fedMillis - audibleMillis);
   }

   static String bufferHealth(long bufferMillis) {
      if (bufferMillis < 0L) {
         return "未知";
      } else if (bufferMillis < 200L) {
         return "余量低";
      } else if (bufferMillis <= 1500L) {
         return "正常";
      } else {
         return bufferMillis <= 2000L ? "偏高" : "过深";
      }
   }

   private static boolean validTimeline(long firstMillis, long secondMillis) {
      return firstMillis >= 0L && secondMillis >= 0L;
   }

   private static int stateColor(String state) {
      return switch (lifecycleStage(state)) {
         case 1 -> -10514945;
         case 2 -> -14249;
         case 3 -> -11151240;
         default -> -4667431;
      };
   }

   private static String signedMillis(long value) {
      return (value > 0L ? "+" : "") + value + "ms";
   }

   private static void submitWorld(List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos) {
      int count = 0;

      for (ModernTurntablePlaybackCoordinator.IndexedDemandDebug source : ModernTurntablePlaybackCoordinator.indexedDemandDebugSnapshots()) {
         if (count++ >= 128) {
            break;
         }

         Vec3 center = source.sourcePos().getCenter();
         drawProfile(lines, cameraPos, center, AudioPlaybackRange.profile(64.0F, source.volume(), source.volume()));
         ellipsoid(lines, cameraPos, center, 96.0, 96.0, 96.0, -1872705281, 1.0F);
      }

      for (IndexedAudioEndpoint endpoint : ClientAudioEndpointIndex.endpointSnapshot()) {
         if (count++ >= 128) {
            break;
         }

         drawProfile(
            lines,
            cameraPos,
            new Vec3(endpoint.x(), endpoint.y(), endpoint.z()),
            AudioPlaybackRange.profile(endpoint.configuredDistance(), endpoint.rangeScale(), endpoint.outputGain())
         );
      }

      for (ClientMediaDemandScheduler.DemandDebug media : ClientMediaDemandScheduler.debugSnapshots()) {
         if (count++ >= 128) {
            break;
         }

         if (media.headphoneRouted()) {
            cross(lines, cameraPos, media.position(), 1.0F, -654350632, 2.2F);
         } else {
            drawProfile(lines, cameraPos, media.position(), AudioPlaybackRange.profile(64.0F, media.volume(), media.volume()));
         }
      }

      for (ControlConsoleRenderer.ElementRangeDebugSnapshot element : ControlConsoleRenderer.elementRangeDebugSnapshots()) {
         if (count++ >= 128) {
            break;
         }

         drawProfile(lines, cameraPos, element.center(), AudioPlaybackRange.profile(element.configuredDistance(), element.volume(), element.volume()));
      }

      for (ControlConsoleRenderer.RangeDebugSnapshot console : ControlConsoleRenderer.rangeDebugSnapshots()) {
         if (count++ >= 128) {
            break;
         }

         box(
            lines,
            cameraPos,
            console.consolePos().getCenter(),
            console.radiusX(),
            console.radiusY(),
            console.radiusZ(),
            console.active() ? -926193153 : -2005251426,
            1.8F
         );
      }
   }

   private static void drawProfile(List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, AudioPlaybackRange.Profile profile) {
      ellipsoid(lines, cameraPos, center, profile.nominalDistance(), profile.nominalDistance(), profile.nominalDistance(), -867631248, 1.8F);
      ellipsoid(lines, cameraPos, center, profile.fadeEndDistance(), profile.fadeEndDistance(), profile.fadeEndDistance(), -654319534, 1.5F);
      ellipsoid(lines, cameraPos, center, profile.noticeDistance(), profile.noticeDistance(), profile.noticeDistance(), -1191210675, 1.0F);
      cross(lines, cameraPos, center, 0.65F, -867631248, 2.5F);
   }

   private static void ellipsoid(
      List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, double radiusX, double radiusY, double radiusZ, int color, float width
   ) {
      if (radiusX > 0.0 && radiusY > 0.0 && radiusZ > 0.0) {
         ring(lines, cameraPos, center, radiusX, radiusY, radiusZ, 0, color, width);
         ring(lines, cameraPos, center, radiusX, radiusY, radiusZ, 1, color, width);
         ring(lines, cameraPos, center, radiusX, radiusY, radiusZ, 2, color, width);
      }
   }

   private static void ring(
      List<PlaybackRangeDebugRenderer.DebugLine> lines,
      Vec3 cameraPos,
      Vec3 center,
      double radiusX,
      double radiusY,
      double radiusZ,
      int plane,
      int color,
      float width
   ) {
      int segments = 48;

      for (int index = 0; index < 48; index++) {
         double first = (Math.PI * 2) * index / 48.0;
         double second = (Math.PI * 2) * (index + 1) / 48.0;
         Vec3 a = ringPoint(center, radiusX, radiusY, radiusZ, plane, first);
         Vec3 b = ringPoint(center, radiusX, radiusY, radiusZ, plane, second);
         line(lines, cameraPos, a, b, color, width);
      }
   }

   private static void box(
      List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, double halfX, double halfY, double halfZ, int color, float width
   ) {
      if (halfX > 0.0 && halfY > 0.0 && halfZ > 0.0) {
         for (int ySign : new int[]{-1, 1}) {
            for (int zSign : new int[]{-1, 1}) {
               line(lines, cameraPos, center.add(-halfX, ySign * halfY, zSign * halfZ), center.add(halfX, ySign * halfY, zSign * halfZ), color, width);
            }
         }

         for (int xSign : new int[]{-1, 1}) {
            for (int zSign : new int[]{-1, 1}) {
               line(lines, cameraPos, center.add(xSign * halfX, -halfY, zSign * halfZ), center.add(xSign * halfX, halfY, zSign * halfZ), color, width);
            }

            for (int ySign : new int[]{-1, 1}) {
               line(lines, cameraPos, center.add(xSign * halfX, ySign * halfY, -halfZ), center.add(xSign * halfX, ySign * halfY, halfZ), color, width);
            }
         }
      }
   }

   private static Vec3 ringPoint(Vec3 center, double radiusX, double radiusY, double radiusZ, int plane, double angle) {
      double cosine = Math.cos(angle);
      double sine = Math.sin(angle);

      return switch (plane) {
         case 0 -> center.add(radiusX * cosine, radiusY * sine, 0.0);
         case 1 -> center.add(radiusX * cosine, 0.0, radiusZ * sine);
         default -> center.add(0.0, radiusY * cosine, radiusZ * sine);
      };
   }

   private static void cross(List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 center, float radius, int color, float width) {
      line(lines, cameraPos, center.add(-radius, 0.0, 0.0), center.add(radius, 0.0, 0.0), color, width);
      line(lines, cameraPos, center.add(0.0, -radius, 0.0), center.add(0.0, radius, 0.0), color, width);
      line(lines, cameraPos, center.add(0.0, 0.0, -radius), center.add(0.0, 0.0, radius), color, width);
   }

   private static void line(List<PlaybackRangeDebugRenderer.DebugLine> lines, Vec3 cameraPos, Vec3 from, Vec3 to, int color, float width) {
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
      lines.add(new PlaybackRangeDebugRenderer.DebugLine(x1, y1, z1, x2, y2, z2, dx * inverse, dy * inverse, dz * inverse, color, width));
   }

   private static String shortId(String value) {
      if (value == null) {
         return "-";
      } else {
         return value.length() <= 12 ? value : value.substring(0, 12);
      }
   }

   private static String describeRoute(ClientAudioOutputRegistry.AudioDemandDebug demand) {
      if (demand.headphone()) {
         return "耳机";
      } else {
         return !demand.mainSuppressed() ? "唱片机本体" : "外设独占(本体静音,中控=" + (demand.consoleRoute() ? "是" : "否") + ",输出=" + demand.matchingRelays() + ")";
      }
   }

   private static String percent(float value) {
      return Math.round(value * 100.0F) + "%";
   }

   private record DebugCounts(int sources, int endpoints, int consoleElements, int handheld, int consoles) {
   }

   private record DebugLine(float x1, float y1, float z1, float x2, float y2, float z2, float nx, float ny, float nz, int color, float width) {
   }

   private record HudEntry(
      String kind,
      String source,
      String session,
      String state,
      int demands,
      boolean resolving,
      String route,
      float volume,
      long audibleMillis,
      long fedMillis,
      long visualMillis,
      int relayStarted,
      int relayRegistered,
      String outputSession
   ) {
   }

   private record LegendItem(String label, int color) {
   }
}
