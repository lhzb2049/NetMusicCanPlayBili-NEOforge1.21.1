package com.zhongbai233.net_music_can_play_bili.bili;

public final class BiliApiResponseException extends Exception {
   public BiliApiResponseException(String message) {
      super(message);
   }

   public BiliApiResponseException(String message, Throwable cause) {
      super(message, cause);
   }
}
