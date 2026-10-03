package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

final class RecentFrameVisibility<K> {
   private final Map<K, Long> submittedFrames = new ConcurrentHashMap<>();
   private final AtomicLong frameSequence = new AtomicLong();
   private final long toleratedFrameAge;

   RecentFrameVisibility(long toleratedFrameAge) {
      this.toleratedFrameAge = Math.max(0L, toleratedFrameAge);
   }

   void beginFrame() {
      this.frameSequence.incrementAndGet();
   }

   void markSubmitted(K key) {
      if (key != null) {
         this.submittedFrames.put(key, this.frameSequence.get());
      }
   }

   boolean wasRecentlySubmitted(K key) {
      if (key == null) {
         return false;
      } else {
         Long submittedFrame = this.submittedFrames.get(key);
         if (submittedFrame == null) {
            return false;
         } else {
            long age = this.frameSequence.get() - submittedFrame;
            return age >= 0L && age <= this.toleratedFrameAge;
         }
      }
   }

   void remove(K key) {
      if (key != null) {
         this.submittedFrames.remove(key);
      }
   }

   void removeIf(Predicate<K> predicate) {
      if (predicate != null) {
         this.submittedFrames.keySet().removeIf(predicate);
      }
   }

   void clear() {
      this.submittedFrames.clear();
   }
}
