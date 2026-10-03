package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

public final class PortSubmitNodeCollector implements AutoCloseable {
   private final MultiBufferSource source;
   private final boolean ownBatch;
   private final List<PortSubmitNodeCollector.TextEntry> pendingTexts = new ArrayList<>(8);
   private Font font;

   public PortSubmitNodeCollector(MultiBufferSource source) {
      this(source, false);
   }

   public PortSubmitNodeCollector(MultiBufferSource source, boolean ownBatch) {
      this.source = source;
      this.ownBatch = ownBatch;
   }

   public MultiBufferSource source() {
      return this.source;
   }

   public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, BiConsumer<Pose, VertexConsumer> emitter) {
      emitter.accept(poseStack.last(), this.source.getBuffer(renderType));
   }

   public void submitText(
      PoseStack poseStack,
      float x,
      float y,
      FormattedCharSequence visual,
      boolean shadow,
      DisplayMode mode,
      int packedLight,
      int color,
      int backgroundColor,
      int packedOverlay
   ) {
      this.pendingTexts
         .add(
            new PortSubmitNodeCollector.TextEntry(
               new Matrix4f(poseStack.last().pose()), x, y, visual, shadow, mode, packedLight, color, backgroundColor, packedOverlay
            )
         );
   }

   public void submitText(
      PoseStack poseStack,
      float x,
      float y,
      Component text,
      boolean shadow,
      DisplayMode mode,
      int packedLight,
      int color,
      int backgroundColor,
      int packedOverlay
   ) {
      this.submitText(poseStack, x, y, text.getVisualOrderText(), shadow, mode, packedLight, color, backgroundColor, packedOverlay);
   }

   public void flush() {
      if (!this.pendingTexts.isEmpty()) {
         if (this.font == null) {
            this.font = Minecraft.getInstance() != null ? Minecraft.getInstance().font : null;
         }

         if (this.font == null) {
            this.pendingTexts.clear();
         } else {
            for (PortSubmitNodeCollector.TextEntry entry : this.pendingTexts) {
               if ((entry.backgroundColor & 0xFF000000) != 0) {
                  VertexConsumer background = this.source.getBuffer(RenderType.textBackground());
                  int width = this.font.width(entry.visual);
                  int height = 9;
                  background.addVertex(entry.pose, entry.x - 1.0F, entry.y - 1.0F, 0.0F).setColor(entry.backgroundColor).setLight(entry.packedLight);
                  background.addVertex(entry.pose, entry.x - 1.0F, entry.y + height, 0.0F).setColor(entry.backgroundColor).setLight(entry.packedLight);
                  background.addVertex(entry.pose, entry.x + width, entry.y + height, 0.0F).setColor(entry.backgroundColor).setLight(entry.packedLight);
                  background.addVertex(entry.pose, entry.x + width, entry.y - 1.0F, 0.0F).setColor(entry.backgroundColor).setLight(entry.packedLight);
               }

               this.font
                  .drawInBatch(
                     entry.visual, entry.x, entry.y, entry.color, entry.shadow, entry.pose, this.source, entry.mode, entry.packedLight, entry.packedOverlay
                  );
            }

            this.pendingTexts.clear();
         }
      }
   }

   public void end() {
      this.flush();
      if (this.ownBatch && this.source instanceof BufferSource bufferSource) {
         bufferSource.endBatch();
      }
   }

   @Override
   public void close() {
      this.end();
   }

   private record TextEntry(
      Matrix4f pose,
      float x,
      float y,
      FormattedCharSequence visual,
      boolean shadow,
      DisplayMode mode,
      int packedLight,
      int color,
      int backgroundColor,
      int packedOverlay
   ) {
   }
}
