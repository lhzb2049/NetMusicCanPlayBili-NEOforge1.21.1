package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import java.util.List;
import java.util.stream.Stream;

final class VideoStartupFallbackPolicy {
   private VideoStartupFallbackPolicy() {
   }

   static VideoStartupFallbackPolicy.DecodeSize candidateDecodeSize(int currentWidth, int currentHeight, int sourceWidth, int sourceHeight) {
      int safeCurrentWidth = Math.max(1, currentWidth);
      int safeCurrentHeight = Math.max(1, currentHeight);
      int safeSourceWidth = Math.max(1, sourceWidth);
      int safeSourceHeight = Math.max(1, sourceHeight);
      double scale = Math.min(1.0, Math.min((double)safeCurrentWidth / safeSourceWidth, (double)safeCurrentHeight / safeSourceHeight));
      int width = evenAtLeastTwo((int)Math.round(safeSourceWidth * scale));
      int height = evenAtLeastTwo((int)Math.round(safeSourceHeight * scale));
      return new VideoStartupFallbackPolicy.DecodeSize(width, height);
   }

   private static int evenAtLeastTwo(int value) {
      int safe = Math.max(2, value);
      return (safe & 1) == 0 ? safe : safe - 1;
   }

   static List<BiliVideoStreamResolver.VideoCandidate> operationalCandidates(
      List<BiliVideoStreamResolver.VideoCandidate> candidates, int maxSourceWidth, int maxSourceHeight
   ) {
      if (candidates != null && !candidates.isEmpty()) {
         int safeMaxWidth = Math.max(1, maxSourceWidth);
         int safeMaxHeight = Math.max(1, maxSourceHeight);
         List<BiliVideoStreamResolver.VideoCandidate> withinLimit = candidates.stream()
            .filter(candidate -> candidate.sourceWidth() <= safeMaxWidth && candidate.sourceHeight() <= safeMaxHeight)
            .toList();
         if (withinLimit.isEmpty()) {
            return List.copyOf(candidates);
         } else {
            return withinLimit.size() == candidates.size()
               ? withinLimit
               : Stream.concat(
                     withinLimit.stream().filter(candidate -> candidate.codecId() == 7), withinLimit.stream().filter(candidate -> candidate.codecId() != 7)
                  )
                  .toList();
         }
      } else {
         return List.of();
      }
   }

   static boolean requiresBoundedFirstFrameProbe(BiliVideoStreamResolver.VideoCandidate candidate) {
      return candidate != null && candidate.codecId() == 13 && candidate.decodeMode() == BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
   }

   static List<BiliVideoStreamResolver.VideoCandidate> lockedH264Candidates(List<BiliVideoStreamResolver.VideoCandidate> candidates) {
      return candidates != null && !candidates.isEmpty() ? candidates.stream().filter(candidate -> candidate.codecId() == 7).toList() : List.of();
   }

   record DecodeSize(int width, int height) {
   }
}
