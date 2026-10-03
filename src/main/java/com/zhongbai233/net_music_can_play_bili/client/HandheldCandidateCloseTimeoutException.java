package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import java.io.IOException;

final class HandheldCandidateCloseTimeoutException extends IOException {
   HandheldCandidateCloseTimeoutException(BiliVideoStreamResolver.ResolvedVideoStream stream) {
      super("旧手持视频候选 native worker 未在关闭预算内退出: quality=" + stream.quality() + ", codec=" + stream.codecId());
   }
}
