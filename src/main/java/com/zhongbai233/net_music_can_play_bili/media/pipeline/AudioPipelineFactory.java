package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;

public final class AudioPipelineFactory {
   private AudioPipelineFactory() {
   }

   public static AudioPipelineFactory.Selection selectFmp4(
      Fmp4ToMp4Converter.ParseResult parseResult,
      String discoveredCodecs,
      BlockingAudioPipe fallbackPipe,
      AtomicBoolean closed,
      PlaybackRequest request,
      float startOffsetSeconds
   ) throws IOException {
      boolean openAlOutput = request != null;
      BlockPos sourcePos = request != null ? request.pos() : null;
      String sessionId = request != null ? request.sessionId() : "";
      UUID ownerId = request != null ? request.ownerId() : null;
      float timelineStartOffsetSeconds = request != null ? request.startOffsetSeconds() : startOffsetSeconds;
      if ("ec-3".equals(parseResult.audioCodec)) {
         return (AudioPipelineFactory.Selection)(openAlOutput && Eac3NativeDecoder.isNativeAvailable()
            ? new AudioPipelineFactory.Supported(
               new DolbyEc3Pipeline("fMP4", closed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, ownerId)
            )
            : new AudioPipelineFactory.Unsupported("EC-3 requires modern turntable Dolby playback and native decoder support"));
      } else if (parseResult.flacDfLa != null) {
         return new AudioPipelineFactory.Supported(
            (AudioDecodePipeline)(openAlOutput
               ? new FlacOpenALPipeline(
                  (byte[])parseResult.flacDfLa.clone(), closed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, ownerId
               )
               : new FlacPcmPipeline((byte[])parseResult.flacDfLa.clone(), fallbackPipe))
         );
      } else {
         return (AudioPipelineFactory.Selection)(parseResult.asc != null
            ? new AudioPipelineFactory.Supported(
               (AudioDecodePipeline)(openAlOutput
                  ? new AacOpenALPipeline(
                     (byte[])parseResult.asc.clone(), closed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, ownerId
                  )
                  : new AacPcmPipeline((byte[])parseResult.asc.clone(), fallbackPipe))
            )
            : new AudioPipelineFactory.Unsupported("unsupported fMP4 audio codec: " + (discoveredCodecs != null ? discoveredCodecs : "")));
      }
   }

   public sealed interface Selection permits AudioPipelineFactory.Supported, AudioPipelineFactory.Unsupported {
   }

   public record Supported(AudioDecodePipeline pipeline) implements AudioPipelineFactory.Selection {
      public Supported(AudioDecodePipeline pipeline) {
         if (pipeline == null) {
            throw new IllegalArgumentException("pipeline must not be null");
         } else {
            this.pipeline = pipeline;
         }
      }
   }

   public record Unsupported(String reason) implements AudioPipelineFactory.Selection {
      public Unsupported(String reason) {
         reason = reason != null && !reason.isBlank() ? reason : "unsupported audio pipeline";
         this.reason = reason;
      }
   }
}
