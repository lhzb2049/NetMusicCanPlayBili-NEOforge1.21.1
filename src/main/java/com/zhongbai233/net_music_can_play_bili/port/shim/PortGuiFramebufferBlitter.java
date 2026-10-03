package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

public final class PortGuiFramebufferBlitter {
   private PortGuiFramebufferBlitter() {
   }

   public static void blit(GuiGraphics guiGraphics, int textureId, int x, int y, int width, int height) {
      if (textureId >= 0 && width > 0 && height > 0) {
         RenderSystem.setShaderTexture(0, textureId);
         RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
         RenderSystem.enableBlend();
         Matrix4f matrix = guiGraphics.pose().last().pose();
         float x1 = x;
         float y1 = y;
         float x2 = (float)x + width;
         float y2 = (float)y + height;
         BufferBuilder builder = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
         builder.addVertex(matrix, x1, y1, 0.0F).setUv(0.0F, 1.0F).setColor(1.0F, 1.0F, 1.0F, 1.0F);
         builder.addVertex(matrix, x1, y2, 0.0F).setUv(0.0F, 0.0F).setColor(1.0F, 1.0F, 1.0F, 1.0F);
         builder.addVertex(matrix, x2, y2, 0.0F).setUv(1.0F, 0.0F).setColor(1.0F, 1.0F, 1.0F, 1.0F);
         builder.addVertex(matrix, x2, y1, 0.0F).setUv(1.0F, 1.0F).setColor(1.0F, 1.0F, 1.0F, 1.0F);
         BufferUploader.drawWithShader(builder.buildOrThrow());
         RenderSystem.disableBlend();
      }
   }
}
