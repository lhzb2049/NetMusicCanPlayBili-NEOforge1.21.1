package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class PortGuiTextures {
   private PortGuiTextures() {
   }

   public static void blitRegion(GuiGraphics g, ResourceLocation texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
      g.flush();
      RenderSystem.setShaderTexture(0, texture);
      RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
      Matrix4f matrix = g.pose().last().pose();
      BufferBuilder builder = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
      builder.addVertex(matrix, x0, y1, 0.0F).setUv(u0, v1).setColor(-1);
      builder.addVertex(matrix, x1, y1, 0.0F).setUv(u1, v1).setColor(-1);
      builder.addVertex(matrix, x1, y0, 0.0F).setUv(u1, v0).setColor(-1);
      builder.addVertex(matrix, x0, y0, 0.0F).setUv(u0, v0).setColor(-1);
      BufferUploader.drawWithShader(builder.buildOrThrow());
   }
}
