package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class LiveSubtitleMetadata {
   public static final String TITLE_MODE = "LIVE_TITLE";
   public static final String ROOM_MODE = "LIVE_ROOM";
   public static final String STATUS_MODE = "LIVE_STATUS";
   public static final LiveSubtitleMetadata.Metadata EMPTY = new LiveSubtitleMetadata.Metadata("", "", "", "", "");

   private LiveSubtitleMetadata() {
   }

   public static boolean isLiveMode(String contentMode) {
      return "LIVE_TITLE".equals(contentMode) || "LIVE_ROOM".equals(contentMode) || "LIVE_STATUS".equals(contentMode);
   }

   public static LiveSubtitleMetadata.Metadata resolve(
      String roomId, String title, String parentAreaName, String areaName, int apiLiveStatus, boolean playing, boolean waitingForLive
   ) {
      String normalizedRoom = safe(roomId);
      String normalizedTitle = safe(title);
      String area = areaText(parentAreaName, areaName);
      String status;
      if (playing) {
         status = apiLiveStatus == 2 ? "轮播中" : "直播中";
      } else if (waitingForLive) {
         status = "等待开播";
      } else {
         status = "已停止";
      }

      return new LiveSubtitleMetadata.Metadata(
         normalizedTitle.isEmpty() ? fallbackTitle(normalizedRoom) : normalizedTitle, normalizedRoom, area, status, roomText(normalizedRoom, area)
      );
   }

   public static String text(String contentMode, LiveSubtitleMetadata.Metadata metadata) {
      LiveSubtitleMetadata.Metadata value = metadata != null ? metadata : EMPTY;

      return switch (contentMode) {
         case "LIVE_TITLE" -> value.title();
         case "LIVE_ROOM" -> value.roomText();
         case "LIVE_STATUS" -> value.status();
         default -> "";
      };
   }

   private static String fallbackTitle(String roomId) {
      return roomId.isEmpty() ? "B站直播" : "B站直播 " + roomId;
   }

   private static String roomText(String roomId, String area) {
      String base = roomId.isEmpty() ? "直播间" : "房间 " + roomId;
      return area.isEmpty() ? base : base + " · " + area;
   }

   private static String areaText(String parentAreaName, String areaName) {
      String parent = safe(parentAreaName);
      String area = safe(areaName);
      if (parent.isEmpty()) {
         return area;
      } else {
         return !area.isEmpty() && !parent.equals(area) ? parent + " / " + area : parent;
      }
   }

   private static String safe(String value) {
      return value == null ? "" : value.trim();
   }

   public record Metadata(String title, String roomId, String area, String status, String roomText) {
      public Metadata(String title, String roomId, String area, String status, String roomText) {
         title = LiveSubtitleMetadata.safe(title);
         roomId = LiveSubtitleMetadata.safe(roomId);
         area = LiveSubtitleMetadata.safe(area);
         status = LiveSubtitleMetadata.safe(status);
         roomText = LiveSubtitleMetadata.safe(roomText);
         this.title = title;
         this.roomId = roomId;
         this.area = area;
         this.status = status;
         this.roomText = roomText;
      }
   }
}
