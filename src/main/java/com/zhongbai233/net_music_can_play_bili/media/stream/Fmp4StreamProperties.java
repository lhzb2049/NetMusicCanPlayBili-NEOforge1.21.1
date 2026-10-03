package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class Fmp4StreamProperties {
   static final String MAX_BUFFERED_PAYLOAD_BYTES = "ncpb.media.fmp4.max_buffered_payload_bytes";
   private static final int MIN_BUFFERED_PAYLOAD_BYTES = 1048576;
   private static final int DEFAULT_MAX_BUFFERED_PAYLOAD_BYTES = 67108864;

   private Fmp4StreamProperties() {
   }

   static int maxBufferedPayloadBytes() {
      return Math.max(1048576, NcpbSystemProperties.intValue("ncpb.media.fmp4.max_buffered_payload_bytes", 67108864));
   }
}
