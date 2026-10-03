package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.block.LyricProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientAiSubtitleRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class LyricProjectorRenderer implements BlockEntityRenderer<LyricProjectorBlockEntity> {
   private static final float TEXT_SCALE = 0.025F;
   private static final float TRANSLATED_LINE_OFFSET = 12.0F;
   private static final long SCROLL_DURATION_MS = 500L;
   private static final ProjectorRenderProperties.LyricScroll SCROLL_PROPERTIES = ProjectorRenderProperties.lyricScroll();
   private static final float SCROLL_MAX_INTERPOLATION_LAG = 0.18F;
   private static final float LINE_STEP = 14.0F;
   private static final int VISIBLE_LINES_ABOVE = 2;
   private static final int VISIBLE_LINES_BELOW = 2;
   private static final ProjectorRenderProperties.LyricBounds RENDER_BOUNDS = ProjectorRenderProperties.lyricBounds();
   private static final Map<BlockPos, LyricProjectorRenderer.ScrollProgressState> scrollProgressStates = new ConcurrentHashMap<>();
   private final Font font;

   public LyricProjectorRenderer(Context context) {
      this.font = context.getFont();
   }

   public void render(
      LyricProjectorBlockEntity projector, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay
   ) {
      LyricProjectorRenderer.State state = this.extract(projector, packedLight);
      if (state.visible && state.linkedPos != null) {
         boolean hasCurrent = !state.currentLine.getString().isBlank();
         boolean hasTranslated = state.translatedLine != null && !state.translatedLine.getString().isBlank();
         PortSubmitNodeCollector collector = new PortSubmitNodeCollector(bufferSource);

         try {
            this.renderFace(state, poseStack, collector, false, hasCurrent, hasTranslated);
            this.renderFace(state, poseStack, collector, true, hasCurrent, hasTranslated);
         } finally {
            collector.end();
         }
      }
   }

   private LyricProjectorRenderer.State extract(LyricProjectorBlockEntity projector, int packedLight) {
      LyricProjectorRenderer.State state = new LyricProjectorRenderer.State();
      state.lightCoords = packedLight;
      state.currentLine = Component.empty();
      state.translatedLine = null;
      state.currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
      state.transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
      state.visible = false;
      state.linkedPos = immutable(projector.getLinkedTurntablePos());
      state.projectorPos = immutable(projector.getBlockPos());
      state.projectionYaw = projector.getProjectionYaw();
      state.projectionPitch = projector.getProjectionPitch();
      state.projectionScale = projector.getProjectionScale();
      state.projectionHeight = projector.getProjectionHeight();
      state.projectionDistanceX = projector.getProjectionDistanceX();
      state.projectionDistanceZ = projector.getProjectionDistanceZ();
      state.projectionMode = projector.getProjectionMode();
      state.allowAi = projector.getAllowAi();
      if (state.linkedPos != null && projector.getLevel() != null) {
         Level level = projector.getLevel();
         if (level.getBlockEntity(state.linkedPos) instanceof ModernTurntableBlockEntity turntable) {
            ClientLinkRegistry.linkSubtitleProjector(projector.getBlockPos(), state.linkedPos);
            if (!turntable.isPlaying()) {
               ClientAiSubtitleRegistry.release(state.projectorPos);
               syncActivatedState(projector, false);
               return state;
            } else {
               LyricRecord lyricRecord = turntable.getClientLyricRecord();
               float lyricTickOverride = resolveProjectorLyricTick(state.linkedPos, turntable);
               if (state.allowAi) {
                  String rawUrl = turntable.getRawUrl();
                  PlaybackSessionId sessionId = turntable.getPlaybackSyncMetadata().playbackSessionId().orElse(null);
                  if (rawUrl != null && !rawUrl.isBlank() && BiliVideoStreamResolver.selectionOrNull(rawUrl) != null && sessionId != null) {
                     ClientAiSubtitleRegistry.acquire(state.projectorPos, state.linkedPos, sessionId, rawUrl, turntable.getSongName());
                     ClientAiSubtitleRegistry.Snapshot snapshot = ClientAiSubtitleRegistry.snapshot(state.linkedPos, sessionId);
                     if (snapshot.ready()) {
                        lyricRecord = snapshot.lyricRecord();
                     }
                  } else {
                     ClientAiSubtitleRegistry.release(state.projectorPos);
                  }
               } else {
                  ClientAiSubtitleRegistry.release(state.projectorPos);
               }

               if (lyricRecord == null) {
                  syncActivatedState(projector, false);
                  return state;
               } else {
                  int lyricLookupTick = lyricTickOverride >= 0.0F ? (int)Math.floor(lyricTickOverride) : turntable.getClientLyricTick();
                  String current = lyricTickOverride >= 0.0F ? currentLineAt(lyricRecord.getLyrics(), lyricLookupTick) : currentLine(lyricRecord.getLyrics());
                  String translated = lyricTickOverride >= 0.0F
                     ? currentLineAt(lyricRecord.getTransLyrics(), lyricLookupTick)
                     : currentLine(lyricRecord.getTransLyrics());
                  boolean hasCurrent = current != null && !current.isBlank();
                  boolean hasTranslated = translated != null && !translated.isBlank();
                  if (!hasCurrent && !hasTranslated) {
                     syncActivatedState(projector, false);
                     return state;
                  } else {
                     state.visible = true;
                     if (hasCurrent) {
                        state.currentLine = Component.literal(current);
                     }

                     if (hasTranslated) {
                        state.translatedLine = Component.literal(translated);
                     } else {
                        state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
                     }

                     state.lyrics = lyricRecord.getLyrics();
                     state.transLyrics = lyricRecord.getTransLyrics();
                     state.scrollPastLines = null;
                     state.scrollFutureLines = null;
                     state.scrollProgress = 1.0F;
                     if (state.projectionMode >= 1) {
                        Int2ObjectSortedMap<String> active = state.projectionMode == 2 ? state.transLyrics : state.lyrics;
                        if ((active == null || active.isEmpty()) && state.projectionMode == 2) {
                           active = state.lyrics;
                        }

                        if (active != null && !active.isEmpty()) {
                           int currentKey = lyricTickOverride >= 0.0F ? currentKeyAt(active, lyricLookupTick) : active.firstIntKey();
                           String activeLine = (String)active.get(currentKey);
                           if (activeLine != null && !activeLine.isBlank()) {
                              state.currentLine = Component.literal(activeLine);
                              state.translatedLine = null;
                              state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
                              state.scrollPastLines = collectPastLines(active, currentKey);
                              List<String> future = new ArrayList<>();
                              int[] keys = active.keySet().toIntArray();

                              for (int tick : keys) {
                                 if (tick > currentKey) {
                                    String line = (String)active.get(tick);
                                    if (line != null && !line.isBlank()) {
                                       future.add(line);
                                       if (future.size() >= 2) {
                                          break;
                                       }
                                    }
                                 }
                              }

                              state.scrollFutureLines = future;
                              float nowTick = lyricTickOverride >= 0.0F ? lyricTickOverride : currentKey;
                              int nextTick = nextLyricTick(active, currentKey);
                              float targetProgress = timelineScrollProgress(currentKey, nextTick, nowTick);
                              state.scrollProgress = interpolateScrollProgress(state.projectorPos, currentKey, targetProgress);
                           }
                        }
                     }

                     syncActivatedState(projector, state.visible);
                     return state;
                  }
               }
            }
         } else {
            ClientAiSubtitleRegistry.release(state.projectorPos);
            ClientLinkRegistry.unlink(projector.getBlockPos());
            projector.unlink();
            syncActivatedState(projector, false);
            return state;
         }
      } else {
         ClientAiSubtitleRegistry.release(state.projectorPos);
         ClientLinkRegistry.unlink(projector.getBlockPos());
         syncActivatedState(projector, false);
         return state;
      }
   }

   private static void syncActivatedState(LyricProjectorBlockEntity projector, boolean visible) {
      Level level = projector.getLevel();
      if (level != null) {
         BlockPos pos = projector.getBlockPos();
         BlockState currentState = level.getBlockState(pos);
         if (currentState.hasProperty(LyricProjectorBlock.ACTIVATED)) {
            boolean currentlyActivated = (Boolean)currentState.getValue(LyricProjectorBlock.ACTIVATED);
            if (visible != currentlyActivated) {
               Minecraft.getInstance().execute(() -> {
                  Level lvl = projector.getLevel();
                  if (lvl != null) {
                     BlockState bs = lvl.getBlockState(pos);
                     if (bs.hasProperty(LyricProjectorBlock.ACTIVATED)) {
                        lvl.setBlock(pos, (BlockState)bs.setValue(LyricProjectorBlock.ACTIVATED, visible), 3);
                     }
                  }
               });
            }
         }
      }
   }

   private void renderFace(
      LyricProjectorRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector, boolean backFace, boolean hasCurrent, boolean hasTranslated
   ) {
      poseStack.pushPose();
      float yaw = state.projectionYaw;
      float pitch = state.projectionPitch;
      float scale = state.projectionScale;
      int mode = state.projectionMode;
      poseStack.translate(0.5 + state.projectionDistanceX, state.projectionHeight, 0.5 + state.projectionDistanceZ);
      poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
      poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
      if (backFace) {
         poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
      }

      poseStack.scale(-0.025F * Math.abs(scale), -0.025F * Math.abs(scale), -0.025F * Math.abs(scale));
      if (mode >= 1 && state.projectorPos != null) {
         this.renderScrollMode(state, poseStack, collector);
      } else {
         if (hasCurrent) {
            this.submitCenteredText(state.currentLine, 0.0F, state.currentLyricColor, state, poseStack, collector);
         }

         if (hasTranslated) {
            this.submitCenteredText(state.translatedLine, -12.0F, state.transLyricColor, state, poseStack, collector);
         }
      }

      poseStack.popPose();
   }

   private void renderScrollMode(LyricProjectorRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector) {
      float progress = state.scrollProgress;
      List<String> lines = new ArrayList<>();
      int pastCount = 0;
      if (state.scrollPastLines != null) {
         lines.addAll(state.scrollPastLines);
         pastCount = state.scrollPastLines.size();
      }

      lines.add(state.currentLine.getString());
      if (state.scrollFutureLines != null) {
         lines.addAll(state.scrollFutureLines);
      }

      if (!lines.isEmpty()) {
         int centerIdx = Math.min(pastCount, 2);
         centerIdx = Math.min(centerIdx, lines.size() - 1);
         if (centerIdx < 0) {
            centerIdx = 0;
         }

         for (int i = 0; i < lines.size(); i++) {
            float effectiveDist = i - centerIdx + (1.0F - progress);
            float absDist = Math.abs(effectiveDist);
            float t = Math.clamp(absDist / 2.0F, 0.0F, 1.0F);
            float eased = t * t * (3.0F - 2.0F * t);
            float sizeScale = 1.0F - eased * 0.44F;
            int userC = state.currentLyricColor;
            int dimWhite = 1090519039;
            int color = lerpColor(userC, dimWhite, eased);
            float y = effectiveDist * 14.0F;
            this.submitCenteredText(Component.literal(lines.get(i)), y, color, sizeScale, state, poseStack, collector);
         }
      }
   }

   private static int lerpColor(int a, int b, float t) {
      int aa = (int)((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t);
      int ar = (int)((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
      int ag = (int)((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
      int ab = (int)((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
      return aa << 24 | ar << 16 | ag << 8 | ab;
   }

   public AABB getRenderBoundingBox(LyricProjectorBlockEntity blockEntity) {
      double scale = Math.max(0.25, (double)Math.abs(blockEntity.getProjectionScale()));
      double textHalfWidth = RENDER_BOUNDS.maxTextWidth() * scale * 0.5;
      double lineHeight = 0.35F * scale;
      double textHalfHeight = Math.max(lineHeight, lineHeight * 5.0 * 0.5);
      double textRadius = Math.sqrt(textHalfWidth * textHalfWidth + textHalfHeight * textHalfHeight);
      double offsetRadius = Math.sqrt(
         blockEntity.getProjectionDistanceX() * blockEntity.getProjectionDistanceX()
            + blockEntity.getProjectionHeight() * blockEntity.getProjectionHeight()
            + blockEntity.getProjectionDistanceZ() * blockEntity.getProjectionDistanceZ()
      );
      double inflate = Math.max(RENDER_BOUNDS.minInflate(), offsetRadius + textRadius + RENDER_BOUNDS.margin());
      return new AABB(blockEntity.getBlockPos()).inflate(inflate, inflate, inflate);
   }

   private void submitCenteredText(
      MutableComponent text, float y, int color, LyricProjectorRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector
   ) {
      this.submitCenteredText(text, y, color, 1.0F, state, poseStack, collector);
   }

   private void submitCenteredText(
      MutableComponent text, float y, int color, float textScale, LyricProjectorRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector
   ) {
      poseStack.pushPose();
      poseStack.scale(textScale, textScale, 1.0F);
      FormattedCharSequence visual = text.getVisualOrderText();
      float x = -this.font.width(text) / 2.0F;
      int bgColor = (int)(Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
      collector.submitText(poseStack, x, y / textScale, visual, false, DisplayMode.NORMAL, state.lightCoords, color, bgColor, 0);
      poseStack.popPose();
   }

   private static String currentLine(Int2ObjectSortedMap<String> lyrics) {
      return lyrics != null && !lyrics.isEmpty() ? (String)lyrics.get(lyrics.firstIntKey()) : null;
   }

   private static String currentLineAt(Int2ObjectSortedMap<String> lyrics, int tick) {
      return lyrics != null && !lyrics.isEmpty() ? (String)lyrics.get(currentKeyAt(lyrics, tick)) : null;
   }

   private static int currentKeyAt(Int2ObjectSortedMap<String> lyrics, int tick) {
      if (lyrics != null && !lyrics.isEmpty()) {
         Int2ObjectSortedMap<String> elapsed = tick >= Integer.MAX_VALUE ? lyrics : lyrics.headMap(tick + 1);
         return elapsed.isEmpty() ? lyrics.firstIntKey() : elapsed.lastIntKey();
      } else {
         return 0;
      }
   }

   private static int nextLyricTick(Int2ObjectSortedMap<String> lyrics, int currentTick) {
      if (lyrics != null && !lyrics.isEmpty()) {
         Int2ObjectSortedMap<String> tail = lyrics.tailMap(currentTick + 1);
         ObjectBidirectionalIterator var3 = tail.int2ObjectEntrySet().iterator();

         while (var3.hasNext()) {
            Entry<String> entry = (Entry<String>)var3.next();
            String line = (String)entry.getValue();
            if (line != null && !line.isBlank()) {
               return entry.getIntKey();
            }
         }

         return -1;
      } else {
         return -1;
      }
   }

   private static List<String> collectPastLines(Int2ObjectSortedMap<String> lyrics, int currentTick) {
      List<String> past = new ArrayList<>();
      if (lyrics != null && !lyrics.isEmpty()) {
         int[] keys = lyrics.keySet().toIntArray();

         for (int i = keys.length - 1; i >= 0 && past.size() < 2; i--) {
            int tick = keys[i];
            if (tick < currentTick) {
               String line = (String)lyrics.get(tick);
               if (line != null && !line.isBlank()) {
                  past.add(0, line);
               }
            }
         }

         return past;
      } else {
         return past;
      }
   }

   private static float timelineScrollProgress(int currentTick, int nextTick, float nowTick) {
      if (nextTick > currentTick && !(nowTick < currentTick)) {
         float elapsedMillis = Math.max(0.0F, (nowTick - currentTick) * 50.0F);
         long durationMillis = adaptiveScrollDurationMillis(currentTick, nextTick);
         if (durationMillis > 0L && !(elapsedMillis >= (float)durationMillis)) {
            float raw = Math.clamp(elapsedMillis / (float)durationMillis, 0.0F, 1.0F);
            return 1.0F - (float)Math.pow(1.0F - raw, 3.0);
         } else {
            return 1.0F;
         }
      } else {
         return 1.0F;
      }
   }

   private static float interpolateScrollProgress(BlockPos projectorPos, int currentKey, float targetProgress) {
      if (projectorPos == null) {
         return targetProgress;
      } else {
         LyricProjectorRenderer.ScrollProgressState state = scrollProgressStates.computeIfAbsent(
            projectorPos.immutable(), ignored -> new LyricProjectorRenderer.ScrollProgressState()
         );
         long nowNanos = System.nanoTime();
         if (state.currentKey != currentKey) {
            state.currentKey = currentKey;
            state.targetProgress = targetProgress;
            state.displayProgress = targetProgress >= 0.98F ? 1.0F : Math.max(0.0F, targetProgress - 0.06F);
            state.lastUpdateNanos = nowNanos;
            return state.displayProgress;
         } else {
            if (targetProgress + 0.001F < state.targetProgress) {
               targetProgress = state.targetProgress;
            }

            state.targetProgress = targetProgress;
            long elapsedNanos = state.lastUpdateNanos > 0L ? Math.max(0L, nowNanos - state.lastUpdateNanos) : 0L;
            state.lastUpdateNanos = nowNanos;
            if (targetProgress >= 0.999F) {
               state.displayProgress = 1.0F;
               return 1.0F;
            } else {
               float delta = targetProgress - state.displayProgress;
               if (delta <= 0.001F) {
                  state.displayProgress = Math.max(state.displayProgress, targetProgress);
                  return state.displayProgress;
               } else {
                  float alpha = interpolationAlpha(elapsedNanos);
                  float interpolated = state.displayProgress + delta * alpha;
                  float minProgress = Math.max(state.displayProgress, targetProgress - 0.18F);
                  state.displayProgress = Math.clamp(Math.max(interpolated, minProgress), 0.0F, targetProgress);
                  return state.displayProgress;
               }
            }
         }
      }
   }

   private static float interpolationAlpha(long elapsedNanos) {
      if (elapsedNanos <= 0L) {
         return 0.0F;
      } else {
         double halfLifeMillis = SCROLL_PROPERTIES.interpolationHalfLifeMillis();
         double elapsedMillis = elapsedNanos / 1000000.0;
         return (float)Math.clamp(1.0 - Math.pow(0.5, elapsedMillis / halfLifeMillis), 0.0, 1.0);
      }
   }

   private static long adaptiveScrollDurationMillis(int currentTick, int nextTick) {
      if (nextTick <= currentTick) {
         return 500L;
      } else {
         long gapMillis = Math.max(0L, (nextTick - currentTick) * 50L);
         if (gapMillis <= 0L) {
            return SCROLL_PROPERTIES.minDurationMillis();
         } else {
            long target = Math.min(500L, Math.max(SCROLL_PROPERTIES.minDurationMillis(), gapMillis * 2L / 3L));
            if (gapMillis <= SCROLL_PROPERTIES.fastGapMillis()) {
               target = Math.min(target, Math.max(SCROLL_PROPERTIES.minDurationMillis(), gapMillis / 2L));
            }

            return target;
         }
      }
   }

   private static float resolveProjectorLyricTick(BlockPos turntablePos, ModernTurntableBlockEntity turntable) {
      long mediaMillis = PlaybackClock.mediaMillis(turntablePos);
      return mediaMillis >= 0L ? (float)Math.max(0L, mediaMillis - SCROLL_PROPERTIES.audioDelayMillis()) / 50.0F : turntable.getClientLyricTick();
   }

   private static BlockPos immutable(BlockPos pos) {
      return pos != null ? pos.immutable() : null;
   }

   private static class ScrollProgressState {
      int currentKey = Integer.MIN_VALUE;
      float targetProgress = 1.0F;
      float displayProgress = 1.0F;
      long lastUpdateNanos;
   }

   public static class State {
      public int lightCoords;
      public MutableComponent currentLine = Component.empty();
      public MutableComponent translatedLine;
      public int currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
      public int transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
      public boolean visible;
      public BlockPos linkedPos;
      public BlockPos projectorPos;
      public float projectionYaw = 180.0F;
      public float projectionPitch = 0.0F;
      public float projectionScale = 1.0F;
      public float projectionHeight = 1.2F;
      public float projectionDistanceX = 0.0F;
      public float projectionDistanceZ = 0.0F;
      public int projectionMode;
      public boolean allowAi;
      public Int2ObjectSortedMap<String> lyrics;
      public Int2ObjectSortedMap<String> transLyrics;
      public List<String> scrollPastLines;
      public List<String> scrollFutureLines;
      public float scrollProgress = 1.0F;
   }
}
