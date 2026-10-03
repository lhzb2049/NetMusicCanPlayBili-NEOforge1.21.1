package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class BiliApiProperties {
   static final String SESSDATA = "ncpb.bili.sessdata";
   static final String WEB_COOKIE = "ncpb.bili.cookie";
   static final String USER_AGENT = "ncpb.bili.user_agent";
   static final String ROTATE_USER_AGENT = "ncpb.bili.rotate_user_agent";
   static final String AUDIO_PREFERENCE = "ncpb.bili.audio.preference";
   static final String LIVE_OFFLINE_RETRY_SECONDS = "ncpb.bili.live.offline_retry_seconds";
   static final String VIDEO_CODEC_POLICY = "ncpb.bili.video.codec_policy";

   private BiliApiProperties() {
   }

   static String initialSessdata() {
      return NcpbSystemProperties.stringValue("ncpb.bili.sessdata", "");
   }

   static String initialWebCookie() {
      return NcpbSystemProperties.stringValue("ncpb.bili.cookie", "");
   }

   static String initialUserAgent() {
      return NcpbSystemProperties.stringValue("ncpb.bili.user_agent", "");
   }

   static boolean rotateUserAgent() {
      return NcpbSystemProperties.booleanValue("ncpb.bili.rotate_user_agent", false);
   }

   static String audioPreference() {
      return NcpbSystemProperties.stringValue("ncpb.bili.audio.preference", "auto").toLowerCase(Locale.ROOT);
   }

   static long liveOfflineRetryMillis() {
      long seconds = NcpbSystemProperties.longValue("ncpb.bili.live.offline_retry_seconds", 60L);
      return Math.max(10000L, TimeUnit.SECONDS.toMillis(seconds));
   }

   static BiliApiClient.VideoCodecPolicy videoCodecPolicy() {
      return BiliApiClient.VideoCodecPolicy.parse(NcpbSystemProperties.stringValue("ncpb.bili.video.codec_policy", "auto"));
   }
}
