package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Locale;

public final class BiliLiveRoomInput {
   private static final String LIVE_PREFIX = "live:";
   private static final String LIVE_HOST = "live.bilibili.com";
   private static final String PLACEHOLDER_PREFIX = "http://live/";
   private static final String PLACEHOLDER_SUFFIX = ".m3u8";

   private BiliLiveRoomInput() {
   }

   public static String placeholderUrl(String roomId) {
      return "http://live/" + roomId + ".m3u8";
   }

   public static String roomIdFromPlaceholder(String url) {
      if (url != null && url.startsWith("http://live/") && url.endsWith(".m3u8")) {
         String roomId = url.substring("http://live/".length(), url.length() - ".m3u8".length());
         return BiliLiveStreamResolver.isValidRoomId(roomId) ? roomId : "";
      } else {
         return "";
      }
   }

   public static String parseExplicitRoomId(String input) {
      if (input == null) {
         return "";
      } else {
         String text = input.trim();
         if (text.toLowerCase(Locale.ROOT).startsWith("live:")) {
            String candidate = text.substring("live:".length()).trim();
            return BiliLiveStreamResolver.isValidRoomId(candidate) ? candidate : "";
         } else {
            return parseRoomUrl(text);
         }
      }
   }

   public static String parseRoomId(String input) {
      if (input == null) {
         return "";
      } else {
         String text = input.trim();
         if (text.isEmpty()) {
            return "";
         } else {
            String candidate = text.toLowerCase(Locale.ROOT).startsWith("live:") ? text.substring("live:".length()).trim() : text;
            return BiliLiveStreamResolver.isValidRoomId(candidate) ? candidate : parseRoomUrl(candidate);
         }
      }
   }

   private static String parseRoomUrl(String candidate) {
      String lower = candidate.toLowerCase(Locale.ROOT);
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
         return "";
      } else {
         int hostStart = lower.indexOf("//") + 2;
         int hostEnd = indexOfAny(lower, hostStart, '/', '?', '#');
         String host = lower.substring(hostStart, hostEnd);
         if (!host.equals("live.bilibili.com")) {
            return "";
         } else {
            String path = candidate.substring(hostEnd, indexOfAny(candidate, hostEnd, '?', '#'));

            for (String segment : path.split("/")) {
               String trimmed = segment.trim();
               if (BiliLiveStreamResolver.isValidRoomId(trimmed)) {
                  return trimmed;
               }
            }

            return "";
         }
      }
   }

   private static int indexOfAny(String text, int fromIndex, char... chars) {
      for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
         for (char c : chars) {
            if (text.charAt(i) == c) {
               return i;
            }
         }
      }

      return text.length();
   }
}
