package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Locale;

public final class VideoFallbackReason {
   public static final String NO_AV1_STREAM = "no-av1-stream";
   public static final String AV1_HARDWARE_UNAVAILABLE = "av1-hardware-unavailable";
   public static final String AV1_PROFILE_INCOMPATIBLE = "av1-profile-incompatible";
   public static final String AV1_STARTUP_FAILURE = "av1-startup-failure";
   public static final String PERFORMANCE_LOW_FPS = "performance-low-fps";
   public static final String PERFORMANCE_GROWING_DRIFT = "performance-growing-av-drift";
   public static final String NO_H264_CANDIDATE = "no-h264-candidate";

   private VideoFallbackReason() {
   }

   public static String classifyAv1StartupFailure(Throwable error, boolean h264Available) {
      if (!h264Available) {
         return "no-h264-candidate";
      } else {
         StringBuilder text = new StringBuilder();

         for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
               text.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
         }

         String value = text.toString();
         if (value.contains("profile")
            || value.contains("bit depth")
            || value.contains("10-bit")
            || value.contains("av1c")
            || value.contains("extradata")
            || value.contains("config obu")) {
            return "av1-profile-incompatible";
         } else {
            return !value.contains("hardware")
                  && !value.contains("hwaccel")
                  && !value.contains("backend")
                  && !value.contains("硬件")
                  && !value.contains("actual=cpu")
                  && !value.contains("actual=none")
                  && !value.contains("actual=unknown")
               ? "av1-startup-failure"
               : "av1-hardware-unavailable";
         }
      }
   }

   public static String userLabel(String reason) {
      if (reason != null && !reason.isBlank()) {
         return switch (reason) {
            case "no-av1-stream" -> "无AV1流";
            case "av1-hardware-unavailable" -> "AV1硬解不可用";
            case "av1-profile-incompatible" -> "AV1 profile不兼容";
            case "av1-startup-failure" -> "AV1启动失败";
            case "performance-low-fps" -> "性能降级(FPS)";
            case "performance-growing-av-drift" -> "性能降级(音画差)";
            case "no-h264-candidate" -> "无H.264后备";
            default -> reason;
         };
      } else {
         return "";
      }
   }
}
