package com.zhongbai233.net_music_can_play_bili.client.media;

/**
 * 媒体源种类。
 *
 * <p>阶段 0 只用它做**识别与判定**（可离线验证的纯逻辑），真正的本地播放通路在后续阶段接入。
 */
public enum SourceKind {
   /** http(s) 且带 host —— 现有通路（B站直链、netmusic 直链等）。 */
   HTTP,
   /** 本地视频文件（mp4 / m4v / mov / m4s）。 */
   LOCAL_VIDEO,
   /** 本地图片文件（png / jpg / jpeg）。 */
   LOCAL_IMAGE,
   /** 形似本地路径，但扩展名不在支持列表内。 */
   LOCAL_OTHER,
   /** 既不是 http(s)，也不是能识别的本地绝对路径（含 netmusic 的关键字搜索词）。 */
   UNSUPPORTED;

   /** 是否属于"本地文件"这一类（含不支持的扩展名）。 */
   public boolean isLocal() {
      return this == LOCAL_VIDEO || this == LOCAL_IMAGE || this == LOCAL_OTHER;
   }
}
