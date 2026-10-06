package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAreaAudioZoneRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.media.ClientLocalImageProjection;
import com.zhongbai233.net_music_can_play_bili.client.media.LocalMediaAdmission;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoSurfacePrivacyPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientAiSubtitleRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleAudioElementKey;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementPosition;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.AiSubtitleText;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleConsumerGain;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleEntryFade;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitFade;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleMediaSettings;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleRangeGate;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.LiveSubtitleMetadata;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.TimedTextResolver;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeasePacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConsumerLeaseResultPacket;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;

public final class ControlConsoleRenderer implements BlockEntityRenderer<ControlConsoleBlockEntity> {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final float TEXT_SCALE = 0.025F;
   private static final int FULL_BRIGHT = 15728880;
   private static final Map<BlockPos, Set<BlockPos>> CONSOLE_AUDIO_KEYS = new ConcurrentHashMap<>();
   private static final Map<BlockPos, BlockPos> CONSOLE_AUDIO_SOURCES = new ConcurrentHashMap<>();
   private static final Map<BlockPos, ControlConsoleRenderer.ConsumerState> CONSUMERS = new ConcurrentHashMap<>();
   private static final long CONSUMER_LEASE_MILLIS = 3000L;
   private static final long CONSUMER_RENEW_MILLIS = 1000L;
   private static final long VIDEO_HEALTH_CHECK_MILLIS = ClientDisplayProperties.controlConsoleVideoHealthCheckMillis();
   private final Font font;

   public ControlConsoleRenderer(Context context) {
      this.font = context.getFont();
   }

   public void render(
      ControlConsoleBlockEntity console, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay
   ) {
      ControlConsoleRenderer.State state = this.extract(console);
      PortSubmitNodeCollector collector = new PortSubmitNodeCollector(bufferSource);

      try {
         this.submit(state, poseStack, collector);
      } finally {
         collector.end();
      }
   }

   private ControlConsoleRenderer.State extract(ControlConsoleBlockEntity console) {
      ControlConsoleRenderer.State state = new ControlConsoleRenderer.State();
      state.consolePos = console.getBlockPos().immutable();
      state.elements = console.document().elements();
      state.sourcePos = sourcePos(console.document(), console.getLevel());
      state.sourceKind = console.document().sourceKind();
      if (state.sourcePos != null) {
         if (state.sourceKind == ControlConsoleDocument.SourceKind.TURNTABLE) {
            ClientLinkRegistry.linkSubtitleProjector(state.consolePos, state.sourcePos);
         } else {
            ClientLinkRegistry.link(state.consolePos, state.sourcePos);
         }

         if (state.sourceKind == ControlConsoleDocument.SourceKind.TURNTABLE) {
            ClientAudioOutputRegistry.bindConsoleRoute(state.consolePos, state.sourcePos);
         } else {
            ClientAudioOutputRegistry.unbindConsoleRoute(state.consolePos);
         }
      } else {
         ClientLinkRegistry.unlink(state.consolePos);
         ClientAudioOutputRegistry.unbindConsoleRoute(state.consolePos);
      }

      registerConsumer(console);
      reconcileConsumer(state.consolePos);
      state.consumerActive = isConsumerActive(state.consolePos);
      // 注意顺序：syncLocalImageSource 读 state.consumerActive，必须在它被赋值**之后**调用，
      // 否则首次渲染时读到默认的 false，本地图片永远登记不上（中控台没画面就是这么来的）。
      syncLocalImageSource(console, state);
      state.hideVideoForPrivacy = VideoSurfacePrivacyPolicy.hideVideo(
         HolographicGlassesClient.shouldHideProjectorVideos(), VideoSurfacePrivacyPolicy.SurfaceKind.CONTROL_CONSOLE
      );
      state.irisCompatibilityMode = IrisShaderpackCompat.isShaderPackInUse();
      state.exitGain = state.irisCompatibilityMode ? 1.0F : consumerExitGain(state.consolePos);
      state.sourcePlaying = false;
      state.videoExpected = false;
      ControlConsoleRenderer.SourceSnapshot sourceSnapshot = sourceSnapshot(console.document(), console.getLevel(), state.sourcePos);
      state.sourcePlaying = sourceSnapshot.playing();
      state.videoExpected = sourceSnapshot.videoExpected();
      VideoBillboardState.ControlConsoleVideoSnapshot video = state.consumerActive
         ? VideoBillboardPreview.currentControlConsoleVideo(state.consolePos, state.sourcePlaying, state.videoExpected)
         : null;
      state.videoState = video != null ? video.state() : ControlConsoleVideoStatePolicy.State.IDLE;
      state.frame = video != null ? video.frame() : VideoBillboardState.ProjectorFrameSnapshot.empty();
      state.playbackSessionId = video != null ? PlaybackSessionId.parse(video.sessionId()) : Optional.empty();
      ControlConsoleRenderer.ConsumerState videoRuntime = CONSUMERS.get(state.consolePos);
      boolean realVideoReady = state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE && state.frame.hasFrame();
      float videoEntryGain = videoRuntime != null ? videoRuntime.videoEnvelope.gain(realVideoReady, System.nanoTime()) : 1.0F;
      state.videoGain = realVideoReady ? videoEntryGain : 1.0F;
      state.currentLyric = "";
      state.translatedLyric = "";
      state.lyrics = null;
      state.transLyrics = null;
      state.lyricTick = -1;
      state.lyricVisualTick = -1.0F;
      state.liveMetadata = sourceSnapshot.liveMetadata();
      state.aiLyric = sourceSnapshot.aiLyric();
      if (sourceSnapshot.lyric() != null) {
         LyricRecord lyric = sourceSnapshot.lyric();
         int lyricTick = sourceSnapshot.lyricTick();
         state.currentLyric = lineAt(lyric.getLyrics(), lyricTick);
         state.translatedLyric = lineAt(lyric.getTransLyrics(), lyricTick);
         state.lyrics = lyric.getLyrics();
         state.transLyrics = lyric.getTransLyrics();
         state.lyricTick = lyricTick;
         state.lyricVisualTick = sourceSnapshot.lyricVisualTick();
      }

      return state;
   }

   /**
    * 阶段 3：本地图片源 —— 按「消费端位置」登记静态画面。
    *
    * <p>中控台屏幕与投影仪共用 {@link ClientLocalImageProjection} 的「位置 → 纹理」映射：这里把该
    * 中控台位置登记/摘除，帧的实际注入点在 {@code VideoBillboardPreview.currentControlConsoleVideo}。
    * 与唱片机是否在播放无关（海报式静态画面），也不做任何文件 IO（判定带 5 秒 TTL 缓存）。
    */
   private static void syncLocalImageSource(ControlConsoleBlockEntity console, ControlConsoleRenderer.State state) {
      // 刻意**不看** state.consumerActive：那个标志的赋值时机与本方法的调用顺序耦合，
      // 一旦顺序变了（首次渲染时它还是默认的 false），本地图片就会永远登记不上 —— 中控台没画面就是这么来的。
      // 这里的判定本身很便宜（分类带 5 秒 TTL 缓存，纹理加载只做一次），多登记一个看不见的屏幕也无妨。
      if (state.sourceKind != ControlConsoleDocument.SourceKind.TURNTABLE
         || state.sourcePos == null
         || console.getLevel() == null
         || !(console.getLevel().getBlockEntity(state.sourcePos) instanceof ModernTurntableBlockEntity turntable)) {
         return;
      }

      ClientLocalImageProjection.syncFromTurntable(turntable, state.consolePos);
   }

   private void submit(ControlConsoleRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector) {
      if (state.consumerActive && state.consolePos != null && state.elements != null && !state.elements.isEmpty() && state.frame != null) {
         for (ControlConsoleElement element : state.elements) {
            if (element.enabled()) {
               float halfHeight = element.height() * 0.5F;
               float halfWidth = halfHeight * element.aspect();
               poseStack.pushPose();
               poseStack.translate(0.5, 1.55, 0.5);
               poseStack.mulPose(element.editorTransform().matrix());
               Matrix4f pose = new Matrix4f(poseStack.last().pose());
               if (element.type() == ControlConsoleElement.Type.SCREEN) {
                  boolean screenPotentiallyVisible = VideoBillboardPreview.isControlConsoleScreenPotentiallyVisible(
                     state.consolePos, element.editorTransform().matrix(), halfWidth, halfHeight
                  );
                  if (!state.hideVideoForPrivacy
                     && screenPotentiallyVisible
                     && (state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE || state.videoState == ControlConsoleVideoStatePolicy.State.BUFFERING)
                     && state.playbackSessionId.isPresent()) {
                     VideoBillboardPreview.markProjectorSubmittedByBer(state.playbackSessionId.orElseThrow(), state.consolePos);
                  }

                  if (!state.hideVideoForPrivacy && state.videoState == ControlConsoleVideoStatePolicy.State.ACTIVE && state.playbackSessionId.isPresent()) {
                     VideoBillboardPreview.captureProjectorImmediatePose(
                        state.playbackSessionId.orElseThrow(), state.consolePos, pose, halfHeight, state.exitGain * state.videoGain, element.brightness()
                     );
                  }

                  if (state.hideVideoForPrivacy && state.frame.hasFrame() && state.frame.width() > 0 && state.frame.height() > 0) {
                     VideoBillboardPreview.submitProjectorPrivacyOverlayOnPose(collector, poseStack, halfWidth, halfHeight);
                  } else if (!state.hideVideoForPrivacy) {
                     VideoBillboardPreview.submitProjectorFrameOnPose(
                        collector,
                        poseStack,
                        state.frame,
                        halfWidth,
                        halfHeight,
                        VideoBillboardPreview.cameraRelativeBackOffset(pose, state.frame.rgbaDepthOffset()),
                        state.exitGain * state.videoGain,
                        element.brightness()
                     );
                  }
               } else if (element.type() == ControlConsoleElement.Type.SUBTITLE) {
                  this.submitSubtitle(state, element, poseStack, collector);
               }

               poseStack.popPose();
            }
         }
      }
   }

   private void submitSubtitle(ControlConsoleRenderer.State state, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector) {
      String mode = element.contentMode();
      if ("AI_SUBTITLE".equals(mode)) {
         this.submitAiSubtitle(state, element, poseStack, collector);
      } else if (LiveSubtitleMetadata.isLiveMode(mode)) {
         String metadataText = LiveSubtitleMetadata.text(mode, state.liveMetadata);
         if (!metadataText.isBlank()) {
            this.submitSubtitleFace(metadataText, "", element, poseStack, collector, false, state.exitGain);
            this.submitSubtitleFace(metadataText, "", element, poseStack, collector, true, state.exitGain);
         }
      } else {
         boolean scrolling = "SCROLL_MAIN".equals(mode) || "SCROLL_TRANSLATION".equals(mode);
         if (scrolling && state.lyricTick >= 0) {
            Int2ObjectSortedMap<String> track = "SCROLL_TRANSLATION".equals(mode) ? state.transLyrics : state.lyrics;
            if (track == null || track.isEmpty()) {
               track = state.lyrics;
            }

            TimedTextResolver.Window window = TimedTextResolver.window(
               track, state.lyricTick, 2, 2, state.lyricVisualTick >= 0.0F ? state.lyricVisualTick : state.lyricTick
            );
            this.submitScrollingSubtitle(window, element, poseStack, collector, state.exitGain);
         } else {
            String current = element.followLyrics() ? state.currentLyric : element.text();
            if ((current == null || current.isBlank()) && element.followLyrics()) {
               current = element.text();
            }

            String translated = element.followLyrics() && element.showTranslation() ? state.translatedLyric : "";
            if (current != null && !current.isBlank() || translated != null && !translated.isBlank()) {
               this.submitSubtitleFace(current, translated, element, poseStack, collector, false, state.exitGain);
               this.submitSubtitleFace(current, translated, element, poseStack, collector, true, state.exitGain);
            }
         }
      }
   }

   private void submitAiSubtitle(ControlConsoleRenderer.State state, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector) {
      AiSubtitleText.Lines lines = AiSubtitleText.resolve(
         state.aiLyric != null ? tick -> lineAt(state.aiLyric.getLyrics(), tick) : null,
         state.aiLyric != null ? tick -> lineAt(state.aiLyric.getTransLyrics(), tick) : null,
         state.currentLyric,
         state.translatedLyric,
         state.lyricTick,
         element.showTranslation(),
         element.text()
      );
      String current = lines.primary();
      String translated = lines.translation();
      if (!current.isBlank() || !translated.isBlank()) {
         this.submitSubtitleFace(current, translated, element, poseStack, collector, false, state.exitGain);
         this.submitSubtitleFace(current, translated, element, poseStack, collector, true, state.exitGain);
      }
   }

   private void submitScrollingSubtitle(
      TimedTextResolver.Window window, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector, float opacity
   ) {
      this.submitScrollingSubtitleFace(window, element, poseStack, collector, opacity, false);
      this.submitScrollingSubtitleFace(window, element, poseStack, collector, opacity, true);
   }

   private void submitScrollingSubtitleFace(
      TimedTextResolver.Window window, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector, float opacity, boolean backFace
   ) {
      poseStack.pushPose();
      if (backFace) {
         poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
      }

      poseStack.scale(-0.025F, -0.025F, -0.025F);
      this.submitScrollingSubtitleLines(window, element, poseStack, collector, opacity);
      poseStack.popPose();
   }

   private void submitScrollingSubtitleLines(
      TimedTextResolver.Window window, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector, float opacity
   ) {
      List<String> lines = new ArrayList<>(window.past());
      int center = lines.size();
      lines.add(window.current());
      lines.addAll(window.future());

      for (int i = 0; i < lines.size(); i++) {
         float distance = i - center + (1.0F - window.progress());
         float size = SubtitleLayout.scrollLineScale(distance);
         float eased = (1.0F - size) / 0.44F;
         int baseColor = "SCROLL_TRANSLATION".equals(element.contentMode()) ? element.translationColor() : element.color();
         int color = multiplyAlpha(lerpColor(baseColor, 1090519039, eased), opacity);
         this.submitSubtitleText(poseStack, collector, Component.literal(lines.get(i)), distance * 14.0F, color, element.textScale() * size, opacity, element);
      }
   }

   private void submitSubtitleText(
      PoseStack poseStack, PortSubmitNodeCollector collector, Component text, float y, int color, float scale, float opacity, ControlConsoleElement element
   ) {
      poseStack.pushPose();
      poseStack.scale(scale, scale, scale);
      this.submitTextLines(poseStack, collector, text, y / Math.max(0.01F, scale), color, element.backgroundColor(), opacity, element);
      poseStack.popPose();
   }

   private static int lerpColor(int first, int second, float amount) {
      int a = (int)((first >>> 24) + ((second >>> 24) - (first >>> 24)) * amount);
      int r = (int)((first >> 16 & 0xFF) + ((second >> 16 & 0xFF) - (first >> 16 & 0xFF)) * amount);
      int g = (int)((first >> 8 & 0xFF) + ((second >> 8 & 0xFF) - (first >> 8 & 0xFF)) * amount);
      int b = (int)((first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * amount);
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static int multiplyAlpha(int color, float opacity) {
      return SubtitleLayout.multiplyAlpha(color, opacity);
   }

   private void submitSubtitleFace(
      String current, String translated, ControlConsoleElement element, PoseStack poseStack, PortSubmitNodeCollector collector, boolean backFace, float opacity
   ) {
      poseStack.pushPose();
      if (backFace) {
         poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
      }

      poseStack.scale(-0.025F, -0.025F, -0.025F);
      if (current != null && !current.isBlank()) {
         this.submitStyledText(poseStack, collector, Component.literal(current), 0.0F, element.color(), element.textScale(), opacity, element);
      }

      if (translated != null && !translated.isBlank()) {
         this.submitStyledText(poseStack, collector, Component.literal(translated), -12.0F, element.translationColor(), element.textScale(), opacity, element);
      }

      poseStack.popPose();
   }

   private void submitStyledText(
      PoseStack poseStack, PortSubmitNodeCollector collector, Component text, float y, int color, float textScale, float opacity, ControlConsoleElement element
   ) {
      poseStack.pushPose();
      poseStack.scale(textScale, textScale, textScale);
      this.submitTextLines(poseStack, collector, text, y, color, element.backgroundColor(), opacity, element);
      poseStack.popPose();
   }

   private void submitTextLines(
      PoseStack poseStack,
      PortSubmitNodeCollector collector,
      Component text,
      float y,
      int color,
      int backgroundColor,
      float opacity,
      ControlConsoleElement element
   ) {
      int splitWidth = SubtitleLayout.splitWidth(element.maxWidth(), element.wrap());
      List<FormattedCharSequence> lines = splitWidth == Integer.MAX_VALUE ? List.of(text.getVisualOrderText()) : this.font.split(text, splitWidth);
      int lineColor = multiplyAlpha(color, opacity);
      int background = multiplyAlpha(backgroundColor, opacity);
      float lineY = y;

      for (FormattedCharSequence visual : lines) {
         int width = this.font.width(visual);
         float x = SubtitleLayout.x(element.alignment(), width);
         collector.submitText(poseStack, x, lineY, visual, false, DisplayMode.NORMAL, 15728880, lineColor, background, 0);
         lineY += 9.0F;
      }
   }

   public AABB getRenderBoundingBox(ControlConsoleBlockEntity console) {
      ControlConsoleDocument document = console.document();
      BlockPos pos = console.getBlockPos();
      double centerX = pos.getX() + 0.5;
      double centerZ = pos.getZ() + 0.5;
      double minX = pos.getX();
      double minY = pos.getY();
      double minZ = pos.getZ();
      double maxX = pos.getX() + 1.0;
      double maxY = pos.getY() + 1.0;
      double maxZ = pos.getZ() + 1.0;

      for (ControlConsoleElement element : document.elements()) {
         if (element.enabled()) {
            Matrix4f transform = element.editorTransform().matrix();
            if (element.type() == ControlConsoleElement.Type.AUDIO) {
               Vector3d worldPosition = ControlConsoleElementPosition.worldPosition(pos.getX(), pos.getY(), pos.getZ(), element);
               double worldX = worldPosition.x;
               double worldY = worldPosition.y;
               double worldZ = worldPosition.z;
               minX = Math.min(minX, worldX);
               minY = Math.min(minY, worldY);
               minZ = Math.min(minZ, worldZ);
               maxX = Math.max(maxX, worldX);
               maxY = Math.max(maxY, worldY);
               maxZ = Math.max(maxZ, worldZ);
            } else {
               float halfHeight = element.height() * 0.5F;
               float halfWidth = halfHeight * element.aspect();

               for (int ySign : new int[]{-1, 1}) {
                  for (int xSign : new int[]{-1, 1}) {
                     Vector3f corner = transform.transformPosition(new Vector3f(xSign * halfWidth, ySign * halfHeight, 0.0F));
                     double worldX = centerX + corner.x;
                     double worldY = pos.getY() + 1.55 + corner.y;
                     double worldZ = centerZ + corner.z;
                     minX = Math.min(minX, worldX);
                     minY = Math.min(minY, worldY);
                     minZ = Math.min(minZ, worldZ);
                     maxX = Math.max(maxX, worldX);
                     maxY = Math.max(maxY, worldY);
                     maxZ = Math.max(maxZ, worldZ);
                  }
               }
            }
         }
      }

      return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
   }

   public boolean shouldRender(ControlConsoleBlockEntity console, Vec3 cameraPos) {
      return true;
   }

   private static BlockPos sourcePos(ControlConsoleDocument document, Level level) {
      return document.hasSourceBinding() && level != null && document.sourceDimension().equals(level.dimension().location().toString())
         ? new BlockPos(document.sourceX(), document.sourceY(), document.sourceZ())
         : null;
   }

   private static ControlConsoleRenderer.SourceSnapshot sourceSnapshot(ControlConsoleDocument document, Level level, BlockPos sourcePos) {
      if (level != null && sourcePos != null && document.sourceKind() != null) {
         BlockEntity source = level.getBlockEntity(sourcePos);
         if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER && source instanceof LiveStreamerBlockEntity live) {
            LiveRoomMetadataRegistry.Snapshot cached = LiveRoomMetadataRegistry.snapshot(
                  new LiveRoomMetadataRegistry.SourceKey(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ()), live.getRoomId()
               )
               .orElse(null);
            LiveSubtitleMetadata.Metadata metadata = LiveSubtitleMetadata.resolve(
               live.getRoomId(),
               cached != null ? cached.title() : "",
               cached != null ? cached.parentAreaName() : "",
               cached != null ? cached.areaName() : "",
               cached != null ? cached.liveStatus() : -1,
               live.isPlaying(),
               live.isWaitingForLive()
            );
            return new ControlConsoleRenderer.SourceSnapshot(live.isPlaying(), live.isPlaying(), null, null, -1, -1.0F, metadata);
         } else if (document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE && source instanceof ModernTurntableBlockEntity turntable) {
            int lyricTick = PlaybackClock.mediaTick(sourcePos);
            if (lyricTick < 0) {
               lyricTick = turntable.getClientLyricTick();
            }

            long visualMillis = PlaybackClock.visualMillis(sourcePos);
            float lyricVisualTick = visualMillis >= 0L ? (float)visualMillis / 50.0F : lyricTick;
            PlaybackSessionId sessionId = turntable.getPlaybackSyncMetadata().playbackSessionId().orElse(null);
            ClientAiSubtitleRegistry.Snapshot aiSnapshot = ClientAiSubtitleRegistry.snapshot(sourcePos, sessionId);
            return new ControlConsoleRenderer.SourceSnapshot(
               turntable.isPlaying(),
               LocalMediaAdmission.videoExpected(BiliVideoStreamResolver.selectionOrNull(turntable.getRawUrl()) != null, turntable.getRawUrl()),
               turntable.getClientLyricRecord(),
               aiSnapshot.ready() ? aiSnapshot.lyricRecord() : null,
               lyricTick,
               lyricVisualTick,
               LiveSubtitleMetadata.EMPTY
            );
         } else {
            return ControlConsoleRenderer.SourceSnapshot.EMPTY;
         }
      } else {
         return ControlConsoleRenderer.SourceSnapshot.EMPTY;
      }
   }

   public static void unregisterAudioForConsole(BlockPos consolePos) {
      Set<BlockPos> keys = CONSOLE_AUDIO_KEYS.remove(consolePos);
      CONSOLE_AUDIO_SOURCES.remove(consolePos);
      if (keys != null) {
         keys.forEach(ClientAudioOutputRegistry::unregisterConsoleRelay);
      }

      ClientAreaAudioZoneRegistry.clearConsoleOutputs(consolePos);
   }

   public static void unregisterConsumer(BlockPos consolePos) {
      if (consolePos != null) {
         ClientLinkRegistry.unlink(consolePos);
         releaseConsumer(consolePos);
         CONSUMERS.remove(consolePos);
      }
   }

   public static void notifyConsoleRemoved(BlockPos consolePos, boolean bindingDestroyed) {
      if (consolePos != null && bindingDestroyed) {
         ClientLinkRegistry.unlink(consolePos);
         ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
      }

      ControlConsoleRenderer.ConsumerState runtime = consolePos != null ? CONSUMERS.get(consolePos) : null;
      Minecraft minecraft = Minecraft.getInstance();
      if (runtime != null && runtime.active && minecraft.level != null && minecraft.player != null && runtime.level == minecraft.level) {
         beginBurstExit(consolePos, runtime);
      } else {
         if (bindingDestroyed) {
            unregisterConsumer(consolePos);
         } else {
            if (runtime != null) {
               releaseConsumerLease(consolePos, runtime);
            }

            deactivateConsumer(consolePos);
         }
      }
   }

   public static void tickConsumers() {
      for (BlockPos consolePos : List.copyOf(CONSUMERS.keySet())) {
         reconcileConsumer(consolePos);
      }
   }

   public static void updateConsumerFades() {
      for (Entry<BlockPos, ControlConsoleRenderer.ConsumerState> entry : List.copyOf(CONSUMERS.entrySet())) {
         if (entry.getValue().fadingOut) {
            tickBurstExit(entry.getKey(), entry.getValue());
         }
      }
   }

   public static void clearConsumers() {
      List.copyOf(CONSUMERS.keySet()).forEach(ControlConsoleRenderer::deactivateConsumer);
      CONSUMERS.clear();
   }

   public static void acceptConsumerLeaseResult(ControlConsoleConsumerLeaseResultPacket result) {
      ControlConsoleRenderer.ConsumerState runtime = CONSUMERS.get(result.pos());
      if (runtime != null) {
         if (result.consumerGeneration() != runtime.consumerGeneration) {
            if (result.status() == ControlConsoleConsumerLeaseResultPacket.Status.GRANTED && result.leaseId() != null) {
               PacketDistributor.sendToServer(
                  new ControlConsoleConsumerLeasePacket(
                     result.pos(), ControlConsoleConsumerLeasePacket.Action.RELEASE, result.leaseId(), result.consumerGeneration()
                  ),
                  new CustomPacketPayload[0]
               );
            }
         } else {
            LOGGER.trace(
               "中控台消费租约响应: console={}, status={}, leasePresent={}, active={}, fadingOut={}",
               new Object[]{result.pos(), result.status(), result.leaseId() != null, runtime.active, runtime.fadingOut}
            );
            if (result.status() == ControlConsoleConsumerLeaseResultPacket.Status.GRANTED) {
               if (runtime.fadingOut) {
                  runtime.leaseId = result.leaseId();
                  releaseConsumerLease(result.pos(), runtime);
               } else {
                  runtime.leaseId = result.leaseId();
                  runtime.leaseExpiresAtMillis = System.currentTimeMillis() + 3000L;
                  Map<BlockPos, AreaAudioZone> zones = new HashMap<>();
                  result.audioOutputZones().forEach(zone -> zones.put(zone.outputKey(), zone.zone()));
                  ClientAreaAudioZoneRegistry.replaceConsoleOutputs(result.pos(), zones);
               }
            } else {
               runtime.leaseId = null;
               runtime.leaseExpiresAtMillis = 0L;
               if (runtime.active) {
                  beginBurstExit(result.pos(), runtime);
               } else if (!runtime.fadingOut) {
                  deactivateConsumer(result.pos());
               }
            }
         }
      }
   }

   public static void registerConsumer(ControlConsoleBlockEntity console) {
      if (console.getLevel() != null) {
         BlockPos consolePos = console.getBlockPos().immutable();
         CONSUMERS.compute(
            consolePos,
            (pos, existing) -> (ControlConsoleRenderer.ConsumerState)(existing != null && existing.level == console.getLevel()
               ? existing
               : new ControlConsoleRenderer.ConsumerState(console.getLevel()))
         );
         reconcileRouteBinding(consolePos, console.document(), console.getLevel());
      }
   }

   private static void reconcileRouteBinding(BlockPos consolePos, ControlConsoleDocument document, Level level) {
      BlockPos source = sourcePos(document, level);
      if (source != null) {
         if (document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE) {
            ClientLinkRegistry.linkSubtitleProjector(consolePos, source);
         } else {
            ClientLinkRegistry.link(consolePos, source);
         }
      } else {
         ClientLinkRegistry.unlink(consolePos);
      }

      if (source != null && ControlConsoleAudioRoutePolicy.takesOverMainOutput(document)) {
         ClientAudioOutputRegistry.bindConsoleRoute(consolePos, source);
      } else {
         ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
      }
   }

   private static boolean isConsumerActive(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState state = CONSUMERS.get(consolePos);
      return state != null && (state.active || state.fadingOut);
   }

   public static ControlConsoleRenderer.ConsumerLeaseDiagnostic consumerLeaseDiagnostic(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState state = CONSUMERS.get(consolePos);
      return state == null
         ? ControlConsoleRenderer.ConsumerLeaseDiagnostic.ABSENT
         : new ControlConsoleRenderer.ConsumerLeaseDiagnostic(
            true, state.active, state.fadingOut, state.leaseId != null && state.leaseExpiresAtMillis > System.currentTimeMillis(), state.consumerGeneration
         );
   }

   private static float consumerExitGain(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState state = CONSUMERS.get(consolePos);
      if (state == null) {
         return 1.0F;
      } else {
         long now = System.nanoTime();
         if (state.fadingOut) {
            return state.fadeBaseGain * ControlConsoleExitFade.gain(state.fadeStartedNanos, now);
         } else {
            float envelope = state.entering ? ControlConsoleEntryFade.gain(state.entryStartedNanos, now) : 1.0F;
            return ControlConsoleConsumerGain.combine(state.lastRangeGain, envelope);
         }
      }
   }

   private static void reconcileConsumer(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState runtime = CONSUMERS.get(consolePos);
      Minecraft minecraft = Minecraft.getInstance();
      if (runtime != null && minecraft.level != null && minecraft.player != null) {
         if (runtime.fadingOut) {
            tickBurstExit(consolePos, runtime);
         } else if (runtime.level != minecraft.level) {
            CONSUMERS.remove(consolePos, runtime);
            releaseConsumerLease(consolePos, runtime);
            deactivateConsumer(consolePos);
         } else {
            boolean consoleChunkLoaded = minecraft.level.hasChunk(Math.floorDiv(consolePos.getX(), 16), Math.floorDiv(consolePos.getZ(), 16));
            if (!consoleChunkLoaded) {
               if (runtime.active) {
                  beginBurstExit(consolePos, runtime);
                  tickBurstExit(consolePos, runtime);
               } else {
                  releaseConsumerLease(consolePos, runtime);
                  deactivateConsumer(consolePos);
               }
            } else if (!(minecraft.level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console)) {
               ClientLinkRegistry.unlink(consolePos);
               ClientAudioOutputRegistry.unbindConsoleRoute(consolePos);
               CONSUMERS.remove(consolePos, runtime);
               releaseConsumerLease(consolePos, runtime);
               deactivateConsumer(consolePos);
            } else {
               ControlConsoleDocument var33 = console.document();
               reconcileRouteBinding(consolePos, var33, minecraft.level);
               boolean rangeChanged = runtime.active
                  && (runtime.halfX != var33.hardRangeX() || runtime.halfY != var33.hardRangeY() || runtime.halfZ != var33.hardRangeZ());
               runtime.halfX = var33.hardRangeX();
               runtime.halfY = var33.hardRangeY();
               runtime.halfZ = var33.hardRangeZ();
               BlockPos source = sourcePos(var33, minecraft.level);
               double centerX = consolePos.getX() + 0.5;
               double centerY = consolePos.getY() + 0.5;
               double centerZ = consolePos.getZ() + 0.5;
               double playerX = minecraft.player.getX();
               double playerY = minecraft.player.getY();
               double playerZ = minecraft.player.getZ();
               boolean positionDiscontinuous = ControlConsoleExitPolicy.positionDiscontinuous(
                  runtime.playerX, runtime.playerY, runtime.playerZ, playerX, playerY, playerZ
               );
               ControlConsoleRangeGate.Result result = ControlConsoleRangeGate.evaluate(
                  runtime.active, playerX - centerX, playerY - centerY, playerZ - centerZ, var33.hardRangeX(), var33.hardRangeY(), var33.hardRangeZ()
               );
               Vec3 movement = minecraft.player.getDeltaMovement();
               boolean approachingHardRange = !result.active()
                  && source != null
                  && PlaybackApproachPredictor.willEnterAabb(
                     playerX,
                     playerY,
                     playerZ,
                     movement.x,
                     movement.y,
                     movement.z,
                     centerX,
                     centerY,
                     centerZ,
                     var33.hardRangeX(),
                     var33.hardRangeY(),
                     var33.hardRangeZ()
                  );
               if (approachingHardRange) {
                  enterPredictivePrewarm(consolePos, runtime, source, var33, minecraft);
                  logRangeTransition(consolePos, runtime, "PREWARM", playerX - centerX, playerY - centerY, playerZ - centerZ, var33, 0.0F);
               } else if (result.active() && source != null) {
                  long now = System.currentTimeMillis();
                  if (now >= runtime.nextLeaseRequestMillis) {
                     PacketDistributor.sendToServer(
                        new ControlConsoleConsumerLeasePacket(
                           consolePos, ControlConsoleConsumerLeasePacket.Action.ACQUIRE_OR_RENEW, runtime.leaseId, runtime.consumerGeneration
                        ),
                        new CustomPacketPayload[0]
                     );
                     runtime.nextLeaseRequestMillis = now + 1000L;
                  }

                  if (runtime.leaseId != null && runtime.leaseExpiresAtMillis > now) {
                     if (runtime.sourcePos == null || runtime.sourcePos.equals(source) && runtime.sourceKind == var33.sourceKind()) {
                        boolean activating = !runtime.active;
                        if (activating) {
                           runtime.entering = true;
                           runtime.entryStartedNanos = System.nanoTime();
                        }

                        runtime.active = true;
                        runtime.prewarming = false;
                        logRangeTransition(consolePos, runtime, "ACTIVE", playerX - centerX, playerY - centerY, playerZ - centerZ, var33, result.gain());
                        float entryGain = runtime.entering ? ControlConsoleEntryFade.gain(runtime.entryStartedNanos, System.nanoTime()) : 1.0F;
                        if (entryGain >= 1.0F) {
                           runtime.entering = false;
                           runtime.entryStartedNanos = 0L;
                        }

                        runtime.lastRangeGain = result.gain();
                        runtime.playerX = playerX;
                        runtime.playerY = playerY;
                        runtime.playerZ = playerZ;
                        runtime.sourcePos = source;
                        runtime.sourceKind = var33.sourceKind();
                        boolean hasVideoSurface = ControlConsoleVideoSurfacePolicy.hasEnabledScreen(var33.elements());
                        int videoQualityCeiling = var33.elements()
                           .stream()
                           .filter(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN)
                           .mapToInt(element -> ControlConsoleMediaSettings.videoQualityCeiling(element.channelIndex()))
                           .max()
                           .orElse(ControlConsoleMediaSettings.videoQualityCeiling(0));
                        if (var33.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER) {
                           ClientAiSubtitleRegistry.release(consolePos);
                           ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
                           if (hasVideoSurface) {
                              LiveStreamerVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
                           } else {
                              LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
                              VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
                           }
                        } else {
                           LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
                           if (hasVideoSurface) {
                              ModernTurntableVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
                              boolean healthCheckDue = now >= runtime.nextVideoHealthCheckMillis;
                              if ((activating || healthCheckDue) && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable) {
                                 ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
                                 runtime.nextVideoHealthCheckMillis = now + VIDEO_HEALTH_CHECK_MILLIS;
                              }
                           } else {
                              ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
                              VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
                           }

                           reconcileAiSubtitleConsumer(consolePos, source, var33, minecraft);
                        }

                        registerAudioForConsole(consolePos, source, var33.elements(), result.gain() * entryGain, false);
                     } else {
                        beginBurstExit(consolePos, runtime);
                        tickBurstExit(consolePos, runtime);
                     }
                  } else {
                     logRangeTransition(consolePos, runtime, "WAITING_LEASE", playerX - centerX, playerY - centerY, playerZ - centerZ, var33, result.gain());
                     runtime.leaseId = null;
                     runtime.leaseExpiresAtMillis = 0L;
                     if (runtime.active) {
                        beginBurstExit(consolePos, runtime);
                        tickBurstExit(consolePos, runtime);
                     }
                  }
               } else {
                  logRangeTransition(
                     consolePos,
                     runtime,
                     !result.active() ? "OUTSIDE" : "NO_SOURCE",
                     playerX - centerX,
                     playerY - centerY,
                     playerZ - centerZ,
                     var33,
                     result.gain()
                  );
                  releaseConsumerLease(consolePos, runtime);
                  boolean abrupt = ControlConsoleExitPolicy.shouldFade(
                     runtime.active, source != null, rangeChanged, positionDiscontinuous, runtime.lastRangeGain
                  );
                  if (abrupt) {
                     beginBurstExit(consolePos, runtime);
                     tickBurstExit(consolePos, runtime);
                  } else {
                     deactivateConsumer(consolePos);
                  }

                  runtime.sourcePos = source;
               }
            }
         }
      } else {
         if (runtime != null) {
            CONSUMERS.remove(consolePos, runtime);
            releaseConsumerLease(consolePos, runtime);
            deactivateConsumer(consolePos);
         }
      }
   }

   private static void enterPredictivePrewarm(
      BlockPos consolePos, ControlConsoleRenderer.ConsumerState runtime, BlockPos source, ControlConsoleDocument document, Minecraft minecraft
   ) {
      if (runtime.active
         || runtime.fadingOut
         || runtime.prewarming && (runtime.sourcePos == null || !runtime.sourcePos.equals(source) || runtime.sourceKind != document.sourceKind())) {
         deactivateConsumer(consolePos);
      }

      if (runtime.leaseId != null) {
         releaseConsumerLease(consolePos, runtime);
      }

      runtime.active = false;
      runtime.fadingOut = false;
      runtime.entering = false;
      runtime.entryStartedNanos = 0L;
      runtime.lastRangeGain = 0.0F;
      runtime.playerX = minecraft.player.getX();
      runtime.playerY = minecraft.player.getY();
      runtime.playerZ = minecraft.player.getZ();
      runtime.sourcePos = source;
      runtime.sourceKind = document.sourceKind();
      runtime.prewarming = true;
      long now = System.currentTimeMillis();
      boolean hasVideoSurface = ControlConsoleVideoSurfacePolicy.hasEnabledScreen(document.elements());
      boolean videoPrewarm = hasVideoSurface && isVideoPrewarmPredicted(consolePos, document, minecraft);
      int videoQualityCeiling = document.elements()
         .stream()
         .filter(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN)
         .mapToInt(element -> ControlConsoleMediaSettings.videoQualityCeiling(element.channelIndex()))
         .max()
         .orElse(ControlConsoleMediaSettings.videoQualityCeiling(0));
      if (document.sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER) {
         ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
         if (videoPrewarm) {
            LiveStreamerVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
         } else {
            LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
         }
      } else {
         LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
         if (videoPrewarm) {
            ModernTurntableVideoClient.registerControlConsoleConsumer(source, consolePos, videoQualityCeiling);
            if (now >= runtime.nextVideoHealthCheckMillis && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable) {
               ModernTurntableVideoClient.syncFromTurntableIfPossible(turntable);
               runtime.nextVideoHealthCheckMillis = now + VIDEO_HEALTH_CHECK_MILLIS;
            }
         } else {
            ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
         }
      }

      registerAudioForConsole(consolePos, source, document.elements(), 0.0F, true);
   }

   private static void reconcileAiSubtitleConsumer(BlockPos consolePos, BlockPos source, ControlConsoleDocument document, Minecraft minecraft) {
      boolean requested = document.elements()
         .stream()
         .anyMatch(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SUBTITLE && "AI_SUBTITLE".equals(element.contentMode()));
      if (requested
         && minecraft.level != null
         && minecraft.level.getBlockEntity(source) instanceof ModernTurntableBlockEntity turntable
         && turntable.isPlaying()
         && BiliVideoStreamResolver.selectionOrNull(turntable.getRawUrl()) != null) {
         PlaybackSessionId sessionId = turntable.getPlaybackSyncMetadata().playbackSessionId().orElse(null);
         ClientAiSubtitleRegistry.acquire(consolePos, source, sessionId, turntable.getRawUrl(), turntable.getSongName());
      } else {
         ClientAiSubtitleRegistry.release(consolePos);
      }
   }

   private static void logRangeTransition(
      BlockPos consolePos,
      ControlConsoleRenderer.ConsumerState runtime,
      String status,
      double relativeX,
      double relativeY,
      double relativeZ,
      ControlConsoleDocument document,
      float gain
   ) {
      if (!status.equals(runtime.rangeDiagnosticStatus)) {
         runtime.rangeDiagnosticStatus = status;
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
               "中控台 hardRange 识别: status={}, console={}, relative=({},{},{}), half=({},{},{}), gain={}",
               new Object[]{
                  status,
                  consolePos,
                  formatRange(relativeX),
                  formatRange(relativeY),
                  formatRange(relativeZ),
                  formatRange(document.hardRangeX()),
                  formatRange(document.hardRangeY()),
                  formatRange(document.hardRangeZ()),
                  formatRange(gain)
               }
            );
         }
      }
   }

   private static String formatRange(double value) {
      return String.format(Locale.ROOT, "%.3f", value);
   }

   private static void deactivateConsumer(BlockPos consolePos) {
      ClientAiSubtitleRegistry.release(consolePos);
      ModernTurntableVideoClient.unregisterControlConsoleConsumer(consolePos);
      LiveStreamerVideoClient.unregisterControlConsoleConsumer(consolePos);
      VideoBillboardPreview.detachControlConsoleConsumer(consolePos);
      unregisterAudioForConsole(consolePos);
      ControlConsoleRenderer.ConsumerState state = CONSUMERS.get(consolePos);
      if (state != null) {
         state.active = false;
         state.fadingOut = false;
         state.entering = false;
         state.prewarming = false;
         state.entryStartedNanos = 0L;
         state.sourcePos = null;
         state.sourceKind = null;
         state.fadeStartedNanos = 0L;
         state.nextVideoHealthCheckMillis = 0L;
         state.videoEnvelope.reset();
      }
   }

   private static void beginBurstExit(BlockPos consolePos, ControlConsoleRenderer.ConsumerState runtime) {
      if (!runtime.fadingOut) {
         releaseConsumerLease(consolePos, runtime);
         ClientAiSubtitleRegistry.release(consolePos);
         runtime.active = false;
         runtime.prewarming = false;
         runtime.entering = false;
         runtime.entryStartedNanos = 0L;
         runtime.fadingOut = true;
         runtime.fadeStartedNanos = System.nanoTime();
         runtime.fadeBaseGain = Math.clamp(runtime.lastRangeGain, 0.0F, 1.0F);
      }
   }

   private static void tickBurstExit(BlockPos consolePos, ControlConsoleRenderer.ConsumerState runtime) {
      float gain = runtime.fadeBaseGain * ControlConsoleExitFade.gain(runtime.fadeStartedNanos, System.nanoTime());
      updateAudioRangeGain(consolePos, gain);
      if (gain <= 0.0F) {
         deactivateConsumer(consolePos);
      }
   }

   private static void updateAudioRangeGain(BlockPos consolePos, float gain) {
      Set<BlockPos> keys = CONSOLE_AUDIO_KEYS.get(consolePos);
      if (keys != null) {
         keys.forEach(key -> ClientAudioOutputRegistry.updateRelayRangeGain(key, gain));
      }
   }

   private static void releaseConsumer(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState runtime = CONSUMERS.get(consolePos);
      if (runtime != null) {
         releaseConsumerLease(consolePos, runtime);
      }

      deactivateConsumer(consolePos);
   }

   private static void releaseConsumerLease(BlockPos consolePos, ControlConsoleRenderer.ConsumerState runtime) {
      if (runtime.leaseId != null) {
         PacketDistributor.sendToServer(
            new ControlConsoleConsumerLeasePacket(consolePos, ControlConsoleConsumerLeasePacket.Action.RELEASE, runtime.leaseId, runtime.consumerGeneration),
            new CustomPacketPayload[0]
         );
      }

      runtime.leaseId = null;
      runtime.leaseExpiresAtMillis = 0L;
      runtime.nextLeaseRequestMillis = 0L;
      runtime.consumerGeneration++;
   }

   private static void registerAudioForConsole(
      BlockPos consolePos, BlockPos sourcePos, List<ControlConsoleElement> elements, float rangeGain, boolean preparationDemand
   ) {
      Set<BlockPos> keys = ConcurrentHashMap.newKeySet();
      BlockPos previousSource = CONSOLE_AUDIO_SOURCES.put(consolePos, sourcePos);
      if (previousSource != null && !previousSource.equals(sourcePos)) {
         unregisterAudioForConsole(consolePos);
         CONSOLE_AUDIO_SOURCES.put(consolePos, sourcePos);
      }

      Set<BlockPos> previousKeys = CONSOLE_AUDIO_KEYS.get(consolePos);

      for (ControlConsoleElement element : elements) {
         if (element.type() == ControlConsoleElement.Type.AUDIO) {
            BlockPos key = ControlConsoleAudioElementKey.of(consolePos, element);
            if (element.enabled()) {
               Vector3d position = ControlConsoleElementPosition.worldPosition(consolePos.getX(), consolePos.getY(), consolePos.getZ(), element);
               float[] worldPos = new float[]{(float)position.x, (float)position.y, (float)position.z};
               ClientAudioOutputRegistry.registerConsoleRelay(
                  key, sourcePos, worldPos, element.channelIndex(), element.volume(), element.autoMixJoc(), element.maxDistance()
               );
               ClientAudioOutputRegistry.updateRelayRangeGain(key, rangeGain);
               ClientAudioOutputRegistry.updateRelayPreparationDemand(key, preparationDemand);
            } else {
               ClientAudioOutputRegistry.unregisterConsoleRelay(key);
            }

            keys.add(key);
         }
      }

      if (previousKeys != null) {
         previousKeys.stream().filter(keyx -> !keys.contains(keyx)).forEach(ClientAudioOutputRegistry::unregisterConsoleRelay);
      }

      if (!keys.isEmpty()) {
         CONSOLE_AUDIO_KEYS.put(consolePos, keys);
      }
   }

   public static boolean isPredictivePrewarmActive(BlockPos consolePos) {
      ControlConsoleRenderer.ConsumerState state = consolePos != null ? CONSUMERS.get(consolePos) : null;
      return state != null && state.prewarming && state.level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console
         ? isVideoPrewarmPredicted(consolePos, console.document(), Minecraft.getInstance())
         : false;
   }

   private static boolean isVideoPrewarmPredicted(BlockPos consolePos, ControlConsoleDocument document, Minecraft minecraft) {
      if (consolePos != null && document != null && minecraft != null && minecraft.player != null) {
         for (ControlConsoleElement element : document.elements()) {
            if (element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN) {
               float halfHeight = element.height() * 0.5F;
               float halfWidth = halfHeight * element.aspect();
               Matrix4f transform = element.editorTransform().matrix();
               double minX = Double.POSITIVE_INFINITY;
               double minY = Double.POSITIVE_INFINITY;
               double minZ = Double.POSITIVE_INFINITY;
               double maxX = Double.NEGATIVE_INFINITY;
               double maxY = Double.NEGATIVE_INFINITY;
               double maxZ = Double.NEGATIVE_INFINITY;

               for (int ySign : new int[]{-1, 1}) {
                  for (int xSign : new int[]{-1, 1}) {
                     Vector3f corner = transform.transformPosition(new Vector3f(xSign * halfWidth, ySign * halfHeight, 0.0F));
                     double x = consolePos.getX() + 0.5 + corner.x;
                     double y = consolePos.getY() + 1.55 + corner.y;
                     double z = consolePos.getZ() + 0.5 + corner.z;
                     minX = Math.min(minX, x);
                     minY = Math.min(minY, y);
                     minZ = Math.min(minZ, z);
                     maxX = Math.max(maxX, x);
                     maxY = Math.max(maxY, y);
                     maxZ = Math.max(maxZ, z);
                  }
               }

               if (VideoBillboardPreview.isScreenAabbPredictedVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.05))) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static List<ControlConsoleRenderer.RangeDebugSnapshot> rangeDebugSnapshots() {
      return CONSUMERS.entrySet()
         .stream()
         .map(
            entry -> {
               ControlConsoleRenderer.ConsumerState state = entry.getValue();
               return new ControlConsoleRenderer.RangeDebugSnapshot(
                  entry.getKey(), state.sourcePos, state.active, state.fadingOut, state.halfX, state.halfY, state.halfZ, state.lastRangeGain
               );
            }
         )
         .filter(snapshot -> snapshot.radiusX() > 0.0 && snapshot.radiusY() > 0.0 && snapshot.radiusZ() > 0.0)
         .toList();
   }

   public static List<ControlConsoleRenderer.ElementRangeDebugSnapshot> elementRangeDebugSnapshots() {
      List<ControlConsoleRenderer.ElementRangeDebugSnapshot> snapshots = new ArrayList<>();

      for (Entry<BlockPos, ControlConsoleRenderer.ConsumerState> entry : CONSUMERS.entrySet()) {
         ControlConsoleRenderer.ConsumerState state = entry.getValue();
         if (state.level.getBlockEntity(entry.getKey()) instanceof ControlConsoleBlockEntity console) {
            for (ControlConsoleElement element : console.document().elements()) {
               if (element.enabled() && element.type() == ControlConsoleElement.Type.AUDIO) {
                  Vector3d position = ControlConsoleElementPosition.worldPosition(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ(), element);
                  snapshots.add(
                     new ControlConsoleRenderer.ElementRangeDebugSnapshot(
                        entry.getKey(),
                        element.elementId(),
                        new Vec3(position.x, position.y, position.z),
                        element.maxDistance(),
                        element.volume(),
                        state.active,
                        state.fadingOut,
                        state.lastRangeGain
                     )
                  );
               }
            }
         }
      }

      return List.copyOf(snapshots);
   }

   public static List<ControlConsoleRenderer.VideoElementRangeDebugSnapshot> videoElementRangeDebugSnapshots() {
      List<ControlConsoleRenderer.VideoElementRangeDebugSnapshot> snapshots = new ArrayList<>();

      for (Entry<BlockPos, ControlConsoleRenderer.ConsumerState> entry : CONSUMERS.entrySet()) {
         ControlConsoleRenderer.ConsumerState state = entry.getValue();
         if (state.level.getBlockEntity(entry.getKey()) instanceof ControlConsoleBlockEntity console) {
            for (ControlConsoleElement element : console.document().elements()) {
               if (element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN) {
                  float halfHeight = element.height() * 0.5F;
                  float halfWidth = halfHeight * element.aspect();
                  Matrix4f transform = element.editorTransform().matrix();
                  List<Vec3> corners = new ArrayList<>(4);
                  int[][] signs = new int[][]{{-1, 1}, {-1, -1}, {1, -1}, {1, 1}};

                  for (int[] sign : signs) {
                     Vector3f corner = transform.transformPosition(new Vector3f(sign[0] * halfWidth, sign[1] * halfHeight, 0.0F));
                     corners.add(
                        new Vec3(entry.getKey().getX() + 0.5 + corner.x, entry.getKey().getY() + 1.55 + corner.y, entry.getKey().getZ() + 0.5 + corner.z)
                     );
                  }

                  snapshots.add(
                     new ControlConsoleRenderer.VideoElementRangeDebugSnapshot(
                        entry.getKey(),
                        element.elementId(),
                        corners,
                        state.active,
                        state.prewarming && isVideoPrewarmPredicted(entry.getKey(), console.document(), Minecraft.getInstance())
                     )
                  );
               }
            }
         }
      }

      return List.copyOf(snapshots);
   }

   private static String lineAt(Int2ObjectSortedMap<String> lines, int tick) {
      if (lines != null && !lines.isEmpty()) {
         int key = TimedTextResolver.keyAt(lines.keySet().toIntArray(), tick);
         return key == Integer.MIN_VALUE ? "" : (String)lines.getOrDefault(key, "");
      } else {
         return "";
      }
   }

   public record ConsumerLeaseDiagnostic(boolean registered, boolean active, boolean fadingOut, boolean leasePresent, long generation) {
      private static final ControlConsoleRenderer.ConsumerLeaseDiagnostic ABSENT = new ControlConsoleRenderer.ConsumerLeaseDiagnostic(
         false, false, false, false, -1L
      );
   }

   private static final class ConsumerState {
      private final Level level;
      private BlockPos sourcePos;
      private ControlConsoleDocument.SourceKind sourceKind;
      private boolean active;
      private boolean fadingOut;
      private boolean prewarming;
      private boolean entering;
      private long entryStartedNanos;
      private long fadeStartedNanos;
      private float fadeBaseGain = 1.0F;
      private float lastRangeGain = 1.0F;
      private double halfX;
      private final PlaybackPresentationEnvelope videoEnvelope = new PlaybackPresentationEnvelope();
      private double halfY;
      private double halfZ;
      private double playerX = Double.NaN;
      private double playerY = Double.NaN;
      private double playerZ = Double.NaN;
      private UUID leaseId;
      private long leaseExpiresAtMillis;
      private long nextLeaseRequestMillis;
      private long nextVideoHealthCheckMillis;
      private long consumerGeneration;
      private String rangeDiagnosticStatus = "";

      private ConsumerState(Level level) {
         this.level = level;
      }
   }

   public record ElementRangeDebugSnapshot(
      BlockPos consolePos,
      UUID elementId,
      Vec3 center,
      float configuredDistance,
      float volume,
      boolean consoleActive,
      boolean consoleFadingOut,
      float hardRangeGain
   ) {
   }

   public record RangeDebugSnapshot(
      BlockPos consolePos, BlockPos sourcePos, boolean active, boolean fadingOut, double radiusX, double radiusY, double radiusZ, float gain
   ) {
   }

   private record SourceSnapshot(
      boolean playing,
      boolean videoExpected,
      LyricRecord lyric,
      LyricRecord aiLyric,
      int lyricTick,
      float lyricVisualTick,
      LiveSubtitleMetadata.Metadata liveMetadata
   ) {
      private static final ControlConsoleRenderer.SourceSnapshot EMPTY = new ControlConsoleRenderer.SourceSnapshot(
         false, false, null, null, -1, -1.0F, LiveSubtitleMetadata.EMPTY
      );
   }

   public static final class State {
      private BlockPos consolePos;
      private BlockPos sourcePos;
      private ControlConsoleDocument.SourceKind sourceKind;
      private boolean sourcePlaying;
      private boolean videoExpected;
      private ControlConsoleVideoStatePolicy.State videoState = ControlConsoleVideoStatePolicy.State.IDLE;
      private Optional<PlaybackSessionId> playbackSessionId = Optional.empty();
      private List<ControlConsoleElement> elements = List.of();
      private VideoBillboardState.ProjectorFrameSnapshot frame = VideoBillboardState.ProjectorFrameSnapshot.empty();
      private String currentLyric = "";
      private String translatedLyric = "";
      private Int2ObjectSortedMap<String> lyrics;
      private Int2ObjectSortedMap<String> transLyrics;
      private LyricRecord aiLyric;
      private int lyricTick = -1;
      private float lyricVisualTick = -1.0F;
      private LiveSubtitleMetadata.Metadata liveMetadata = LiveSubtitleMetadata.EMPTY;
      private boolean consumerActive;
      private boolean hideVideoForPrivacy;
      private boolean irisCompatibilityMode;
      private float exitGain = 1.0F;
      private float videoGain = 1.0F;
   }

   public record VideoElementRangeDebugSnapshot(BlockPos consolePos, UUID elementId, List<Vec3> corners, boolean consoleActive, boolean predictedPrewarm) {
      public VideoElementRangeDebugSnapshot(BlockPos consolePos, UUID elementId, List<Vec3> corners, boolean consoleActive, boolean predictedPrewarm) {
         corners = List.copyOf(corners);
         this.consolePos = consolePos;
         this.elementId = elementId;
         this.corners = corners;
         this.consoleActive = consoleActive;
         this.predictedPrewarm = predictedPrewarm;
      }
   }
}
