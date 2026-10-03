package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;

public abstract class PortPictureInPictureRenderer<S> implements AutoCloseable {
   private static final Map<Class<?>, Function<BufferSource, ? extends PortPictureInPictureRenderer<?>>> PIP_FACTORIES = new ConcurrentHashMap<>();
   protected final BufferSource bufferSource;
   protected float activeLineWidth = 1.0F;
   private RenderTarget target;
   private int lastWidth;
   private int lastHeight;

   protected PortPictureInPictureRenderer(BufferSource suppliedSource) {
      this.bufferSource = MultiBufferSource.immediate(new ByteBufferBuilder(4096));
   }

   public static <S> void registerPipRenderer(Class<S> stateClass, Function<BufferSource, ? extends PortPictureInPictureRenderer<S>> factory) {
      PIP_FACTORIES.put(stateClass, factory);
   }

   public static <S> PortPictureInPictureRenderer<S> createPipRenderer(Class<S> stateClass, BufferSource source) {
      Function<BufferSource, ? extends PortPictureInPictureRenderer<?>> factory = PIP_FACTORIES.get(stateClass);
      return factory == null ? null : (PortPictureInPictureRenderer)factory.apply(source);
   }

   public abstract Class<S> getRenderStateClass();

   protected abstract void renderToTexture(S var1, PoseStack var2);

   protected abstract String getTextureLabel();

   protected float getTranslateY(int textureHeight, int pixelScale) {
      return 0.0F;
   }

   public final void render(S state, int width, int height) {
      if (state != null && width > 0 && height > 0) {
         Minecraft minecraft = Minecraft.getInstance();
         RenderSystem.assertOnRenderThread();
         if (this.target == null || this.lastWidth != width || this.lastHeight != height) {
            this.lastWidth = width;
            this.lastHeight = height;
            if (this.target != null) {
               this.target.destroyBuffers();
            }

            this.target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            this.target.setFilterMode(9729);
         }

         float previousFogStart = RenderSystem.getShaderFogStart();
         float previousFogEnd = RenderSystem.getShaderFogEnd();
         RenderSystem.setShaderFogStart(Float.MAX_VALUE);
         RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
         this.activeLineWidth = 1.0F;
         RenderSystem.lineWidth(1.0F);
         RenderSystem.enableCull();
         RenderSystem.enableDepthTest();
         RenderSystem.getModelViewStack().pushMatrix();
         RenderSystem.getModelViewStack().identity();
         RenderSystem.applyModelViewMatrix();
         this.target.bindWrite(true);
         RenderSystem.enableScissor(0, 0, width, height);
         RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
         RenderSystem.clear(16640, Minecraft.ON_OSX);
         PoseStack pipPoseStack = new PoseStack();

         try {
            this.renderToTexture(state, pipPoseStack);
            this.bufferSource.endBatch();
         } finally {
            RenderSystem.disableScissor();
            this.target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.lineWidth(1.0F);
            this.activeLineWidth = 1.0F;
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            Lighting.setupForFlatItems();
         }
      }
   }

   public int colorTextureId() {
      return this.target != null ? this.target.getColorTextureId() : -1;
   }

   protected void applyLineWidth(float width) {
      if (width != this.activeLineWidth) {
         this.bufferSource.endBatch();
         RenderSystem.lineWidth(width);
         this.activeLineWidth = width;
      }
   }

   @Override
   public void close() {
      if (this.target != null) {
         this.target.destroyBuffers();
         this.target = null;
      }
   }
}
