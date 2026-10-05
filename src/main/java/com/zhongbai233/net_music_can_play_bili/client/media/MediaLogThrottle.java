package com.zhongbai233.net_music_can_play_bili.client.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志去重闸门：同一个 key 只在"载荷"变化时放行一次。
 *
 * <p>用途：唱片机/投影仪的同步逻辑是**每帧**执行的（`ControlConsoleRenderer` 两个调用点 + `VideoProjectorRenderer`），
 * 一旦某个源的判定结果不变，就会以 60~120 条/秒的速率刷同一条日志。这里按 key 记住上一次的载荷，
 * 只有结果真的变了才再打一条，既不丢状态变化、也不再刷屏。
 *
 * <p>上限保护：条目超过 {@link #MAX_ENTRIES} 时整体清空（判定内容本身是廉价的，宁可多打一条也不无限增长）。
 */
public final class MediaLogThrottle {
   private static final int MAX_ENTRIES = 256;
   private static final Map<String, String> LAST_PAYLOAD = new ConcurrentHashMap<>();

   private MediaLogThrottle() {
   }

   /** @return true 表示"这次的载荷与上次不同（或首次）"，调用方应当打日志。 */
   public static boolean shouldLog(String key, String payload) {
      String safeKey = key == null ? "" : key;
      String safePayload = payload == null ? "" : payload;
      if (LAST_PAYLOAD.size() > MAX_ENTRIES) {
         LAST_PAYLOAD.clear();
      }

      String previous = LAST_PAYLOAD.put(safeKey, safePayload);
      return !safePayload.equals(previous);
   }

   public static void clear() {
      LAST_PAYLOAD.clear();
   }

   /** 便于诊断/测试：当前记住了多少个 key。 */
   public static int size() {
      return LAST_PAYLOAD.size();
   }
}
