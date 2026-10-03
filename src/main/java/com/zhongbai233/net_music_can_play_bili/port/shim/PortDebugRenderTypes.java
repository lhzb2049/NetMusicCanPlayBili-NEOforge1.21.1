package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.OptionalDouble;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard.LineStateShard;
import net.minecraft.client.renderer.RenderType.CompositeState;

public final class PortDebugRenderTypes {
   private static final Function<Float, RenderType> TRANSLUCENT_LINES = Util.memoize(
      width -> RenderType.create(
         "ncpb_debug_lines_" + width,
         DefaultVertexFormat.POSITION_COLOR_NORMAL,
         Mode.LINES,
         256,
         false,
         false,
         CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(new LineStateShard(OptionalDouble.of(width.floatValue())))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
      )
   );

   private PortDebugRenderTypes() {
   }

   public static RenderType translucentLines(float width) {
      return TRANSLUCENT_LINES.apply(width);
   }
}
