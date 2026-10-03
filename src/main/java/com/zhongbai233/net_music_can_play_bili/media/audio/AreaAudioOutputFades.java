package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AreaAudioOutputFades<K> {
   private final ConcurrentMap<K, AreaAudioZone> zones = new ConcurrentHashMap<>();
   private final ConcurrentMap<K, AreaAudioBoundaryEnvelope> fades = new ConcurrentHashMap<>();
   private volatile AreaAudioZone listenerZone = AreaAudioZone.unrestricted();

   public void acceptListener(AreaAudioZone zone) {
      this.listenerZone = normalized(zone);
   }

   public void set(K outputKey, AreaAudioZone zone) {
      this.zones.put(Objects.requireNonNull(outputKey, "outputKey"), normalized(zone));
   }

   public void remove(K outputKey) {
      if (outputKey != null) {
         this.zones.remove(outputKey);
         this.fades.remove(outputKey);
      }
   }

   public float gain(K outputKey, long nowNanos) {
      if (outputKey == null) {
         return 1.0F;
      } else {
         AreaAudioZone output = this.zones.get(outputKey);
         return output == null
            ? 1.0F
            : this.fades.computeIfAbsent(outputKey, ignored -> new AreaAudioBoundaryEnvelope()).gain(output.allows(this.listenerZone), nowNanos);
      }
   }

   public void clear() {
      this.listenerZone = AreaAudioZone.unrestricted();
      this.zones.clear();
      this.fades.clear();
   }

   private static AreaAudioZone normalized(AreaAudioZone zone) {
      return zone != null ? zone : AreaAudioZone.unrestricted();
   }
}
