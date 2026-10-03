package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;

public class ModernTurntableRenderer implements BlockEntityRenderer<ModernTurntableBlockEntity> {
   private static final float TEXT_SCALE = 0.025F;
   private static final float TRANSLATED_LINE_OFFSET = 12.0F;
   private final Font font;
   private final NetMusicDiscModelAdapter discModel;

   public ModernTurntableRenderer(Context context) {
      this.font = context.getFont();
      this.discModel = new NetMusicDiscModelAdapter(context);
   }

   public void render(
      ModernTurntableBlockEntity turntable, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay
   ) {
      ModernTurntableRenderer.State state = this.extract(turntable, partialTick, packedLight);
      this.discModel.submit(state.hasDisc, state.playing, state.facing, state.gameTime, state.partialTick, packedLight, poseStack, bufferSource);
      boolean hasCurrent = state.currentLine != null && !state.currentLine.getString().isBlank();
      boolean hasTranslated = state.translatedLine != null && !state.translatedLine.getString().isBlank();
      if (hasCurrent || hasTranslated) {
         PortSubmitNodeCollector collector = new PortSubmitNodeCollector(bufferSource);

         try {
            poseStack.pushPose();
            poseStack.translate(0.5, 1.625, 0.5);
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            poseStack.mulPose(Axis.YN.rotationDegrees(camera.getYRot()));
            poseStack.mulPose(Axis.XN.rotationDegrees(-camera.getXRot()));
            poseStack.scale(-0.025F, -0.025F, -0.025F);
            if (hasCurrent) {
               this.submitCenteredText(state.currentLine, -state.y, state.currentLyricColor, state, poseStack, collector);
            }

            if (hasTranslated) {
               this.submitCenteredText(state.translatedLine, -state.y - 12.0F, state.transLyricColor, state, poseStack, collector);
            }

            poseStack.popPose();
         } finally {
            collector.end();
         }
      }
   }

   private ModernTurntableRenderer.State extract(ModernTurntableBlockEntity turntable, float partialTick, int packedLight) {
      ModernTurntableRenderer.State state = new ModernTurntableRenderer.State();
      state.lightCoords = packedLight;
      state.currentLine = Component.empty();
      state.translatedLine = null;
      state.currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
      state.transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
      state.y = 0.5F;
      state.projected = false;
      state.hasDisc = turntable.hasDisc();
      state.playing = turntable.isPlaying();
      state.facing = (Direction)turntable.getBlockState().getValue(ModernTurntableBlock.FACING);
      state.gameTime = turntable.getLevel() != null ? MonotonicMediaClock.nowTick() : 0L;
      state.partialTick = partialTick;
      if (!(Boolean)GeneralConfig.ENABLE_PLAYER_LYRICS.get() || !turntable.isPlaying()) {
         return state;
      } else if (isLinkedToProjector(turntable)) {
         state.projected = true;
         state.currentLine = Component.translatable("message.net_music_can_play_bili.modern_turntable.projected");
         state.currentLyricColor = -5592406;
         return state;
      } else {
         LyricRecord lyricRecord = turntable.getClientLyricRecord();
         if (lyricRecord == null) {
            return state;
         } else {
            String current = currentLine(lyricRecord.getLyrics());
            String translated = currentLine(lyricRecord.getTransLyrics());
            boolean hasCurrent = current != null && !current.isBlank();
            boolean hasTranslated = translated != null && !translated.isBlank();
            if (!hasCurrent && !hasTranslated) {
               return state;
            } else {
               if (hasCurrent) {
                  state.currentLine = Component.literal(current);
               }

               if (hasTranslated) {
                  state.translatedLine = Component.literal(translated);
                  state.y += 0.5F;
               } else {
                  state.currentLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
               }

               return state;
            }
         }
      }
   }

   public AABB getRenderBoundingBox(ModernTurntableBlockEntity blockEntity) {
      return new AABB(blockEntity.getBlockPos()).inflate(1.0, 2.5, 1.0);
   }

   private void submitCenteredText(
      MutableComponent text, float y, int color, ModernTurntableRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector
   ) {
      FormattedCharSequence visual = text.getVisualOrderText();
      float x = -this.font.width(text) / 2.0F;
      int backgroundColor = (int)(Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
      collector.submitText(poseStack, x, y, visual, false, DisplayMode.NORMAL, state.lightCoords, color, backgroundColor, 0);
   }

   private static String currentLine(Int2ObjectSortedMap<String> lyrics) {
      return lyrics != null && !lyrics.isEmpty() ? (String)lyrics.get(lyrics.firstIntKey()) : null;
   }

   private static boolean isLinkedToProjector(ModernTurntableBlockEntity turntable) {
      return ClientLinkRegistry.isSubtitleProjectionTarget(turntable.getBlockPos());
   }

   public static class State {
      public int lightCoords;
      public MutableComponent currentLine = Component.empty();
      public MutableComponent translatedLine;
      public int currentLyricColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
      public int transLyricColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
      public float y = 0.5F;
      public boolean projected;
      public boolean hasDisc;
      public boolean playing;
      public Direction facing = Direction.SOUTH;
      public long gameTime;
      public float partialTick;
   }
}
