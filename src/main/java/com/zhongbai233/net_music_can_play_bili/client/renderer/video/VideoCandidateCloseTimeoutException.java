package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

final class VideoCandidateCloseTimeoutException extends IOException {
   final BiliVideoStreamResolver.VideoCandidate candidate;
   final CompletableFuture<Void> nativeTermination;

   VideoCandidateCloseTimeoutException(BiliVideoStreamResolver.VideoCandidate candidate, CompletableFuture<Void> nativeTermination) {
      this(candidate, nativeTermination, "close timeout", null);
   }

   VideoCandidateCloseTimeoutException(BiliVideoStreamResolver.VideoCandidate candidate, CompletableFuture<Void> nativeTermination, String reason) {
      this(candidate, nativeTermination, reason, null);
   }

   VideoCandidateCloseTimeoutException(
      BiliVideoStreamResolver.VideoCandidate candidate, CompletableFuture<Void> nativeTermination, String reason, Throwable cause
   ) {
      super("旧视频候选 native worker 未正常收敛: quality=" + candidate.quality() + ", codec=" + candidate.codecId() + ", reason=" + reason, cause);
      this.candidate = candidate;
      this.nativeTermination = nativeTermination;
   }
}
