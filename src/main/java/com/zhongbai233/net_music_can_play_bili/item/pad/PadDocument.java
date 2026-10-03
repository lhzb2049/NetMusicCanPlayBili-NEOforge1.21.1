package com.zhongbai233.net_music_can_play_bili.item.pad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

public record PadDocument(
   String title,
   String author,
   boolean locked,
   long updatedAtMillis,
   long sequence,
   PadMapSettings mapSettings,
   List<PadMediaEntry> mediaEntries,
   List<PadTriggerPoint> triggerPoints
) {
   public static final int MAX_MEDIA_ENTRIES = 64;
   public static final int MAX_TRIGGER_POINTS = 128;
   public static final PadDocument DEFAULT = new PadDocument("", "", false, 0L, 0L, PadMapSettings.DEFAULT, List.of(), List.of());

   public PadDocument(
      String title,
      String author,
      boolean locked,
      long updatedAtMillis,
      long sequence,
      PadMapSettings mapSettings,
      List<PadMediaEntry> mediaEntries,
      List<PadTriggerPoint> triggerPoints
   ) {
      title = title == null ? "" : title;
      author = author == null ? "" : author;
      updatedAtMillis = Math.max(0L, updatedAtMillis);
      sequence = Math.max(0L, sequence);
      mapSettings = mapSettings == null ? PadMapSettings.DEFAULT : mapSettings;
      mediaEntries = mediaEntries == null
         ? List.of()
         : mediaEntries.stream()
            .filter(entry -> entry != null && !entry.disc().isEmpty())
            .sorted(Comparator.comparingInt(entry -> entry.mediaId()))
            .limit(64L)
            .toList();
      triggerPoints = triggerPoints == null ? List.of() : triggerPoints.stream().filter(point -> point != null).limit(128L).toList();
      this.title = title;
      this.author = author;
      this.locked = locked;
      this.updatedAtMillis = updatedAtMillis;
      this.sequence = sequence;
      this.mapSettings = mapSettings;
      this.mediaEntries = mediaEntries;
      this.triggerPoints = triggerPoints;
   }

   public Optional<PadMediaEntry> media(int mediaId) {
      return this.mediaEntries.stream().filter(entry -> entry.mediaId() == mediaId).findFirst();
   }

   public int nextFreeMediaId() {
      for (int id = 1; id <= 64; id++) {
         int candidate = id;
         if (this.mediaEntries.stream().noneMatch(entry -> entry.mediaId() == candidate)) {
            return candidate;
         }
      }

      return -1;
   }

   public PadDocument withAddedMedia(ItemStack disc) {
      int id = this.nextFreeMediaId();
      if (id >= 0 && disc != null && !disc.isEmpty()) {
         ArrayList<PadMediaEntry> entries = new ArrayList<>(this.mediaEntries);
         entries.add(new PadMediaEntry(id, disc));
         return this.touch(entries, this.triggerPoints);
      } else {
         return this;
      }
   }

   public PadDocument withRemovedMedia(int mediaId) {
      ArrayList<PadMediaEntry> entries = new ArrayList<>(this.mediaEntries);
      return !entries.removeIf(entry -> entry.mediaId() == mediaId) ? this : this.touch(entries, this.triggerPoints);
   }

   public PadDocument withTrigger(PadTriggerPoint point) {
      if (point == null) {
         return this;
      } else {
         ArrayList<PadTriggerPoint> points = new ArrayList<>(this.triggerPoints);
         points.removeIf(existing -> existing.pointId().equals(point.pointId()));
         points.add(point);
         return this.touch(this.mediaEntries, points);
      }
   }

   public PadDocument withLocked(boolean locked) {
      PadDocumentLockPolicy.LockCopy<PadMediaEntry, PadTriggerPoint> copy = PadDocumentLockPolicy.transition(
         this.locked, locked, this.updatedAtMillis, this.sequence, this.mediaEntries, this.triggerPoints, true, System.currentTimeMillis()
      );
      return copy == null
         ? this
         : new PadDocument(
            this.title, this.author, copy.locked(), copy.updatedAtMillis(), copy.sequence(), this.mapSettings, copy.mediaEntries(), copy.triggerPoints()
         );
   }

   public PadDocument copyWithLocked(boolean locked) {
      PadDocumentLockPolicy.LockCopy<PadMediaEntry, PadTriggerPoint> copy = PadDocumentLockPolicy.transition(
         this.locked, locked, this.updatedAtMillis, this.sequence, this.mediaEntries, this.triggerPoints, false, 0L
      );
      return copy == null
         ? this
         : new PadDocument(
            this.title, this.author, copy.locked(), copy.updatedAtMillis(), copy.sequence(), this.mapSettings, copy.mediaEntries(), copy.triggerPoints()
         );
   }

   private PadDocument touch(List<PadMediaEntry> media, List<PadTriggerPoint> points) {
      return new PadDocument(this.title, this.author, this.locked, System.currentTimeMillis(), this.sequence + 1L, this.mapSettings, media, points);
   }
}
