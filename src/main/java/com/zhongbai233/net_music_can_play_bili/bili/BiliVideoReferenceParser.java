package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BiliVideoReferenceParser {
   private static final Pattern BV_FULL = Pattern.compile("^[Bb][Vv][0-9A-Za-z]{10}$");
   private static final Pattern AV_FULL = Pattern.compile("^av(\\d+)$", 2);
   private static final Pattern BV_ANYWHERE = Pattern.compile("[Bb][Vv][0-9A-Za-z]{10}");
   private static final Pattern AV_ANYWHERE = Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d+)(?:$|[^0-9A-Za-z])", 2);
   private static final Pattern STORED_SELECTION = Pattern.compile("^((?:[Bb][Vv][0-9A-Za-z]{10}|av\\d+))(?:\\|p=(\\d+))?$", 2);
   private static final Pattern PAGE_PARAM = Pattern.compile("(?:^|[?&#|;])p=(\\d+)(?:$|[&#;])", 2);

   private BiliVideoReferenceParser() {
   }

   static boolean isVideoId(String input) {
      if (input != null && !input.isBlank()) {
         String raw = input.trim();
         return BV_FULL.matcher(raw).matches() || AV_FULL.matcher(raw).matches();
      } else {
         return false;
      }
   }

   static BiliApiClient.VideoId extractVideoId(String input) {
      if (input != null && !input.isBlank()) {
         String raw = input.trim();
         Matcher fullBv = BV_FULL.matcher(raw);
         if (fullBv.matches()) {
            return BiliApiClient.VideoId.bvid("BV" + raw.substring(2));
         } else {
            Matcher fullAv = AV_FULL.matcher(raw);
            return fullAv.matches() ? BiliApiClient.VideoId.aid(fullAv.group(1)) : null;
         }
      } else {
         return null;
      }
   }

   static BiliApiClient.VideoId extractVideoIdLenient(String input) {
      if (input != null && !input.isBlank()) {
         String raw = input.trim();
         BiliApiClient.VideoSelection stored = parseStoredSelection(raw);
         if (stored != null) {
            return stored.videoId();
         } else {
            BiliApiClient.VideoId direct = extractVideoId(raw);
            if (direct != null) {
               return direct;
            } else {
               Matcher bv = BV_ANYWHERE.matcher(raw);
               if (bv.find()) {
                  String value = bv.group();
                  return BiliApiClient.VideoId.bvid("BV" + value.substring(2));
               } else {
                  Matcher av = AV_ANYWHERE.matcher(raw);
                  return av.find() ? BiliApiClient.VideoId.aid(av.group(1)) : null;
               }
            }
         }
      } else {
         return null;
      }
   }

   static BiliApiClient.VideoSelection extractSelectionLenient(String input) {
      if (input != null && !input.isBlank()) {
         String raw = input.trim();
         BiliApiClient.VideoSelection stored = parseStoredSelection(raw);
         if (stored != null) {
            return stored;
         } else {
            BiliApiClient.VideoId id = extractVideoIdLenient(raw);
            return id != null ? new BiliApiClient.VideoSelection(id, extractPage(raw, 1)) : null;
         }
      } else {
         return null;
      }
   }

   static BiliApiClient.VideoSelection parseStoredSelection(String input) {
      if (input != null && !input.isBlank()) {
         Matcher matcher = STORED_SELECTION.matcher(input.trim());
         if (!matcher.matches()) {
            return null;
         } else {
            BiliApiClient.VideoId videoId = extractVideoId(matcher.group(1));
            if (videoId == null) {
               return null;
            } else {
               int page = matcher.group(2) != null && !matcher.group(2).isBlank() ? parsePositivePage(matcher.group(2), 1) : 1;
               return new BiliApiClient.VideoSelection(videoId, page);
            }
         }
      } else {
         return null;
      }
   }

   static String formatStoredSelection(BiliApiClient.VideoId videoId, int page) {
      return videoId.asInputText() + "|p=" + Math.max(1, page);
   }

   static boolean looksLikeShortLink(String input) {
      String lower = input.toLowerCase(Locale.ROOT);
      return lower.contains("b23.tv/") || lower.contains("bili2233.cn/");
   }

   private static int extractPage(String input, int fallback) {
      int safeFallback = Math.max(1, fallback);
      Matcher matcher = PAGE_PARAM.matcher(input.trim());
      return matcher.find() ? parsePositivePage(matcher.group(1), safeFallback) : safeFallback;
   }

   private static int parsePositivePage(String page, int fallback) {
      try {
         return Math.max(1, Integer.parseInt(page));
      } catch (NumberFormatException var3) {
         return Math.max(1, fallback);
      }
   }
}
