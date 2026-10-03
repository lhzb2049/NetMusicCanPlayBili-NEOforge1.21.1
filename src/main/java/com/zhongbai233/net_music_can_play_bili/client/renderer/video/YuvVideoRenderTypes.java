package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.RenderStateShard.ShaderStateShard;
import net.minecraft.client.renderer.RenderStateShard.TextureStateShard;
import net.minecraft.client.renderer.RenderStateShard.TexturingStateShard;
import net.minecraft.client.renderer.RenderType.CompositeState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;
import org.slf4j.Logger;

public final class YuvVideoRenderTypes {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ResourceLocation YUV420P_SHADER = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "bili_yuv420p_entity");
   private static final ResourceLocation NV12_SHADER = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "bili_nv12_entity");
   private static final ResourceLocation TEXTURED_PROBE_SHADER = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "bili_yuv420p_textured_probe_entity"
   );
   private static final String YUV_SHADER_DEBUG = VideoPipelineProperties.yuv().shaderDebug();
   private static final boolean YUV_NO_DEPTH_WRITE = VideoYuvRenderPolicy.disableDepthWrite();
   private static volatile ShaderInstance yuv420pShader;
   private static volatile ShaderInstance nv12Shader;
   private static volatile ShaderInstance texturedProbeShader;
   private static volatile boolean shaderFailuresLogged;

   private YuvVideoRenderTypes() {
   }

   public static void registerPipelines(RegisterRenderers event) {
      if (!YUV_SHADER_DEBUG.isBlank()) {
         LOGGER.warn("YUV shader 可视化诊断已启用: mode={}。若画面不变，说明当前后端没有执行本模组 YUV fragment shader。", YUV_SHADER_DEBUG);
      }
   }

   public static void warmupYuvShaders() {
      yuvShader(YUV420P_SHADER);
      yuvShader(NV12_SHADER);
   }

   public static boolean yuvShadersRegistered() {
      return RenderSystem.isOnRenderThread() && yuvShader(YUV420P_SHADER) != null && yuvShader(NV12_SHADER) != null;
   }

   private static ShaderInstance yuvShader(ResourceLocation id) {
      if (!RenderSystem.isOnRenderThread()) {
         return null;
      } else if (id.equals(YUV420P_SHADER)) {
         ShaderInstance loaded = yuv420pShader;
         if (loaded == null) {
            yuv420pShader = loaded = loadShader("bili_yuv420p_entity");
         }

         return loaded;
      } else if (id.equals(NV12_SHADER)) {
         ShaderInstance loaded = nv12Shader;
         if (loaded == null) {
            nv12Shader = loaded = loadShader("bili_nv12_entity");
         }

         return loaded;
      } else {
         ShaderInstance loaded = texturedProbeShader;
         if (loaded == null) {
            texturedProbeShader = loaded = loadShader("bili_yuv420p_textured_probe_entity");
         }

         return loaded;
      }
   }

   private static ShaderInstance loadShader(String path) {
      ShaderInstance shader = null;

      try {
         shader = new ShaderInstance(
            Minecraft.getInstance().getResourceManager(),
            ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", path),
            DefaultVertexFormat.NEW_ENTITY
         );
      } catch (Exception var3) {
         shader = null;
      }

      if (shader == null && !shaderFailuresLogged) {
         shaderFailuresLogged = true;
         LOGGER.error(
            "YUV core shader '{}' failed to compile. YUV/NV12 surfaces fall back to a single-plane display; restart with video.iris.disable_yuv_shader=true to force CPU RGBA conversion instead.",
            path
         );
      }

      return shader;
   }

   public static RenderType yuv420pEntity(ResourceLocation yTexture, ResourceLocation uTexture, ResourceLocation vTexture) {
      return yuvEntity("bili_yuv420p_entity", yuvShader(YUV420P_SHADER), yTexture, uTexture, vTexture);
   }

   public static RenderType nv12Entity(ResourceLocation yTexture, ResourceLocation uvTexture, ResourceLocation placeholderTexture) {
      return nv12Entity("bili_nv12_entity", yTexture, uvTexture, placeholderTexture);
   }

   public static RenderType padNv12Entity(ResourceLocation yTexture, ResourceLocation uvTexture, ResourceLocation placeholderTexture) {
      return nv12Entity("ncpb_pad_video_nv12_entity", yTexture, uvTexture, placeholderTexture);
   }

   private static RenderType nv12Entity(String name, ResourceLocation yTexture, ResourceLocation uvTexture, ResourceLocation placeholderTexture) {
      return yuvEntity(name, yuvShader(NV12_SHADER), yTexture, uvTexture, placeholderTexture);
   }

   private static RenderType yuvEntity(String name, ShaderInstance shader, ResourceLocation sampler0, ResourceLocation sampler1, ResourceLocation sampler2) {
      return (RenderType)(shader == null
         ? RenderType.entityCutout(sampler0)
         : RenderType.create(
            name,
            DefaultVertexFormat.NEW_ENTITY,
            Mode.QUADS,
            1536,
            false,
            false,
            CompositeState.builder()
               .setShaderState(new ShaderStateShard(() -> shader))
               .setTextureState(new TextureStateShard(sampler0, false, false))
               .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
               .setCullState(RenderStateShard.NO_CULL)
               .setWriteMaskState(YUV_NO_DEPTH_WRITE ? RenderStateShard.COLOR_WRITE : RenderStateShard.COLOR_DEPTH_WRITE)
               .setTexturingState(new TexturingStateShard("yuv_planes", () -> {
                  RenderSystem.setShaderTexture(1, sampler1);
                  RenderSystem.setShaderTexture(2, sampler2);
               }, () -> {}))
               .createCompositeState(false)
         ));
   }

   static RenderType yOnlyTexturedProbeEntity(ResourceLocation yTexture) {
      return yuvEntity("bili_yuv420p_textured_probe_entity", yuvShader(TEXTURED_PROBE_SHADER), yTexture, yTexture, yTexture);
   }

   public static RenderType videoRgbaEntity(ResourceLocation texture) {
      return RenderType.entityCutout(texture);
   }

   public static RenderType videoRgbaTranslucentEntity(ResourceLocation texture) {
      return RenderType.entityTranslucent(texture);
   }

   public static RenderType videoRgbaEmissiveEntity(ResourceLocation texture) {
      return RenderType.entityTranslucentEmissive(texture);
   }

   public static RenderType padVideoRgbaEntity(ResourceLocation texture) {
      return RenderType.entityCutout(texture);
   }
}
