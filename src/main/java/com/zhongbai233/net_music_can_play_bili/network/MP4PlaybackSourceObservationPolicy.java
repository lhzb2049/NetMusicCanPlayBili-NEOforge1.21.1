package com.zhongbai233.net_music_can_play_bili.network;

import java.util.Objects;
import java.util.UUID;

final class MP4PlaybackSourceObservationPolicy {
   private MP4PlaybackSourceObservationPolicy() {
   }

   static MP4PlaybackSourceObservationPolicy.Action action(
      MP4PlaybackSourceObservationPolicy.Observation existing, MP4PlaybackSourceObservationPolicy.Observation observed
   ) {
      Objects.requireNonNull(observed, "observed");
      if (existing == null) {
         return MP4PlaybackSourceObservationPolicy.Action.START;
      } else {
         boolean matches = switch (observed.sourceType()) {
            case 0 -> existing.sourceType() == 0;
            case 1 -> existing.sourceType() == 1 && existing.sourceEntityId() == observed.sourceEntityId();
            case 2 -> existing.sourceType() == 2
               && Objects.equals(existing.sourcePosition(), observed.sourcePosition())
               && existing.containerSlot() == observed.containerSlot();
            case 3 -> existing.sourceType() == 3
               && existing.sourceEntityId() == observed.sourceEntityId()
               && existing.containerSlot() == observed.containerSlot();
            default -> false;
         };
         return matches ? MP4PlaybackSourceObservationPolicy.Action.KEEP : MP4PlaybackSourceObservationPolicy.Action.MIGRATE;
      }
   }

   static boolean samePhysicalSource(MP4PlaybackSourceObservationPolicy.PhysicalIdentity left, MP4PlaybackSourceObservationPolicy.PhysicalIdentity right) {
      return left != null
         && right != null
         && Objects.equals(left.levelKey(), right.levelKey())
         && left.sourceType() == right.sourceType()
         && left.sourceEntityId() == right.sourceEntityId()
         && Objects.equals(left.sourcePosition(), right.sourcePosition())
         && left.containerSlot() == right.containerSlot()
         && Objects.equals(left.ownerId(), right.ownerId());
   }

   static enum Action {
      START,
      KEEP,
      MIGRATE;
   }

   record Observation(int sourceType, int sourceEntityId, Object sourcePosition, int containerSlot) {
   }

   record PhysicalIdentity(Object levelKey, UUID ownerId, int sourceType, int sourceEntityId, Object sourcePosition, int containerSlot) {
   }
}
