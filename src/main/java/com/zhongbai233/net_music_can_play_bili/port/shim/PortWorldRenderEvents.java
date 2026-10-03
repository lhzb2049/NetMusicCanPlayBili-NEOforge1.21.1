package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

public final class PortWorldRenderEvents {
   private static final int SCRATCH_BUFFER_BYTES = 32768;
   private static final ThreadLocal<PortWorldRenderEvents.Scratch> SCRATCH = ThreadLocal.withInitial(PortWorldRenderEvents.Scratch::new);

   private PortWorldRenderEvents() {
   }

   public static PortSubmitNodeCollector begin() {
      PortWorldRenderEvents.Scratch scratch = SCRATCH.get();
      return new PortSubmitNodeCollector(scratch.source, true);
   }

   public static void end(PortSubmitNodeCollector collector) {
      if (collector != null) {
         collector.end();
         SCRATCH.get().builder.discard();
      }
   }

   public static boolean isStage(RenderLevelStageEvent event, Stage stage) {
      return event != null && stage != null && event.getStage() == stage;
   }

   private static final class Scratch {
      final ByteBufferBuilder builder = new ByteBufferBuilder(32768);
      final BufferSource source = MultiBufferSource.immediate(this.builder);

      Scratch() {
      }
   }
}
