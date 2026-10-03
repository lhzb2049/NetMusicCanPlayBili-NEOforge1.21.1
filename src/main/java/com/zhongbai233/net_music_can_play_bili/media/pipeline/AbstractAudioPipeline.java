package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import javax.sound.sampled.AudioFormat;

public abstract class AbstractAudioPipeline implements AudioDecodePipeline {
   private final String container;
   private final String codec;
   private final String detail;
   private final boolean usesOpenAlOutput;

   protected AbstractAudioPipeline(String container, String codec, String detail, boolean usesOpenAlOutput) {
      this.container = container;
      this.codec = codec;
      this.detail = detail;
      this.usesOpenAlOutput = usesOpenAlOutput;
   }

   @Override
   public String container() {
      return this.container;
   }

   @Override
   public String codec() {
      return this.codec;
   }

   @Override
   public String detail() {
      return this.detail;
   }

   @Override
   public boolean usesOpenAlOutput() {
      return this.usesOpenAlOutput;
   }

   @Override
   public abstract AudioFormat format();
}
