package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.Objects;
import java.util.UUID;

public record AreaAudioZone(boolean isolated, UUID areaId) {
   public static final UUID WILDNESS_ID = new UUID(0L, 0L);
   private static final AreaAudioZone UNRESTRICTED = new AreaAudioZone(false, WILDNESS_ID);

   public AreaAudioZone(boolean isolated, UUID areaId) {
      areaId = Objects.requireNonNull(areaId, "areaId");
      this.isolated = isolated;
      this.areaId = areaId;
   }

   public static AreaAudioZone unrestricted() {
      return UNRESTRICTED;
   }

   public static AreaAudioZone isolated(UUID areaId) {
      return new AreaAudioZone(true, Objects.requireNonNull(areaId, "areaId"));
   }

   public static AreaAudioZone wildness() {
      return isolated(WILDNESS_ID);
   }

   public boolean allows(AreaAudioZone listenerZone) {
      return !this.isolated ? true : listenerZone != null && listenerZone.isolated && this.areaId.equals(listenerZone.areaId);
   }
}
