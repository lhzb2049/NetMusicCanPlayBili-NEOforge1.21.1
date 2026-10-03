package com.zhongbai233.net_music_can_play_bili.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PlaybackReportTracker<R> {
   private final long notificationCooldownTicks;
   private final int reminderLimit;
   private final ConcurrentHashMap<String, PlaybackReportTracker.State<R>> reports = new ConcurrentHashMap<>();

   PlaybackReportTracker(long notificationCooldownTicks, int reminderLimit) {
      if (notificationCooldownTicks >= 0L && reminderLimit > 0) {
         this.notificationCooldownTicks = notificationCooldownTicks;
         this.reminderLimit = reminderLimit;
      } else {
         throw new IllegalArgumentException("invalid report tracker policy");
      }
   }

   PlaybackReportTracker.Decision<R> record(String sourceKey, R reporterId, long now) {
      PlaybackReportTracker.State<R> state = this.reports.computeIfAbsent(sourceKey, ignored -> new PlaybackReportTracker.State<>());
      synchronized (state) {
         state.totalReports++;
         state.lastReportGameTime = now;
         state.reporterIds.add(reporterId);
         boolean firstReport = state.totalReports == 1;
         boolean reachedLimit = state.totalReports >= this.reminderLimit;
         boolean shouldNotify = firstReport
            || !state.reminderLimitReached && now - state.lastNotifiedGameTime >= this.notificationCooldownTicks
            || reachedLimit && !state.reminderLimitReached;
         return new PlaybackReportTracker.Decision<>(shouldNotify, !firstReport, reachedLimit, this.snapshot(state));
      }
   }

   PlaybackReportTracker.Snapshot<R> markNotified(String sourceKey, long now, boolean limitReached) {
      PlaybackReportTracker.State<R> state = this.reports.get(sourceKey);
      if (state == null) {
         return PlaybackReportTracker.Snapshot.empty();
      } else {
         synchronized (state) {
            state.lastNotifiedGameTime = now;
            state.notifiedReportCount = state.totalReports;
            state.reminderLimitReached |= limitReached;
            return this.snapshot(state);
         }
      }
   }

   List<PlaybackReportTracker.Entry<R>> snapshots() {
      List<PlaybackReportTracker.Entry<R>> result = new ArrayList<>();
      this.reports.forEach((key, state) -> {
         synchronized (state) {
            result.add(new PlaybackReportTracker.Entry<>(key, this.snapshot((PlaybackReportTracker.State<R>)state)));
         }
      });
      return result;
   }

   void retainSources(Set<String> activeSourceKeys) {
      this.reports.keySet().removeIf(key -> !activeSourceKeys.contains(key));
   }

   private PlaybackReportTracker.Snapshot<R> snapshot(PlaybackReportTracker.State<R> state) {
      return new PlaybackReportTracker.Snapshot<>(
         state.totalReports,
         state.reporterIds.size(),
         state.notifiedReportCount,
         state.lastNotifiedGameTime,
         state.lastReportGameTime,
         state.reminderLimitReached
      );
   }

   record Decision<R>(boolean shouldNotify, boolean merged, boolean reachedLimit, PlaybackReportTracker.Snapshot<R> snapshot) {
   }

   record Entry<R>(String sourceKey, PlaybackReportTracker.Snapshot<R> snapshot) {
   }

   record Snapshot<R>(
      int totalReports, int uniqueReporterCount, int notifiedReportCount, long lastNotifiedGameTime, long lastReportGameTime, boolean reminderLimitReached
   ) {
      static <R> PlaybackReportTracker.Snapshot<R> empty() {
         return new PlaybackReportTracker.Snapshot<>(0, 0, 0, Long.MIN_VALUE, 0L, false);
      }

      int suppressedReportCount() {
         return Math.max(0, this.totalReports - this.notifiedReportCount - 1);
      }
   }

   private static final class State<R> {
      private final Set<R> reporterIds = ConcurrentHashMap.newKeySet();
      private int totalReports;
      private int notifiedReportCount;
      private long lastNotifiedGameTime = Long.MIN_VALUE;
      private long lastReportGameTime;
      private boolean reminderLimitReached;
   }
}
