package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

final class VideoPlaybackFrameQueue {
   private final int capacity;
   private final ArrayDeque<DecodedVideoFrame> frames = new ArrayDeque<>();
   private long droppedFrames;

   VideoPlaybackFrameQueue(int capacity) {
      this.capacity = Math.max(1, capacity);
   }

   synchronized boolean offer(DecodedVideoFrame frame, BooleanSupplier shouldContinue) throws InterruptedException {
      while (this.frames.size() >= this.capacity && shouldContinue.getAsBoolean()) {
         this.wait(5L);
      }

      if (!shouldContinue.getAsBoolean()) {
         return false;
      } else {
         this.frames.addLast(frame);
         this.notifyAll();
         return true;
      }
   }

   synchronized DecodedVideoFrame pollBestFrame(long playbackNanos, long earlyToleranceNanos) {
      DecodedVideoFrame best = null;
      long visibleUntil = playbackNanos + Math.max(0L, earlyToleranceNanos);

      while (!this.frames.isEmpty()) {
         DecodedVideoFrame next = this.frames.peekFirst();
         if (next.ptsNanos() > visibleUntil) {
            break;
         }

         DecodedVideoFrame polled = this.frames.pollFirst();
         if (best != null) {
            best.close();
            this.droppedFrames++;
         }

         best = polled;
      }

      if (best != null) {
         this.notifyAll();
      }

      return best;
   }

   synchronized void clear() {
      for (DecodedVideoFrame frame : this.frames) {
         frame.close();
      }

      this.frames.clear();
      this.notifyAll();
   }

   synchronized long drainDroppedFrames() {
      long value = this.droppedFrames;
      this.droppedFrames = 0L;
      return value;
   }

   synchronized boolean isFull() {
      return this.frames.size() >= this.capacity;
   }

   synchronized boolean isEmpty() {
      return this.frames.isEmpty();
   }

   synchronized int size() {
      return this.frames.size();
   }

   int capacity() {
      return this.capacity;
   }

   synchronized long latestPtsNanos() {
      DecodedVideoFrame latest = this.frames.peekLast();
      return latest != null ? latest.ptsNanos() : -1L;
   }
}
