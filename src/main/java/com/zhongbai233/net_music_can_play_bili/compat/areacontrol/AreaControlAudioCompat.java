package com.zhongbai233.net_music_can_play_bili.compat.areacontrol;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

public final class AreaControlAudioCompat {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final AtomicBoolean RUNTIME_WARNING_LOGGED = new AtomicBoolean();
   private static final AreaControlAudioCompat.Adapter ADAPTER = createAdapter();

   private AreaControlAudioCompat() {
   }

   public static boolean active() {
      return ADAPTER != null;
   }

   public static AreaAudioZone zoneAt(ServerLevel level, BlockPos pos) {
      if (ADAPTER != null && level != null && pos != null) {
         try {
            return AreaAudioZone.isolated(ADAPTER.find(level.dimension().location().toString(), pos));
         } catch (RuntimeException | ReflectiveOperationException var3) {
            if (RUNTIME_WARNING_LOGGED.compareAndSet(false, true)) {
               LOGGER.warn("AreaControl acoustic lookup failed; NCPB audio isolation is failing open", var3);
            }

            return AreaAudioZone.unrestricted();
         }
      } else {
         return AreaAudioZone.unrestricted();
      }
   }

   private static AreaControlAudioCompat.Adapter createAdapter() {
      if (!ModList.get().isLoaded("area_control")) {
         return null;
      } else {
         try {
            Class<?> apiClass = Class.forName("org.teacon.areacontrol.api.AreaControlAPI");
            Object lookup = apiClass.getField("areaLookup").get(null);
            UUID wildness = (UUID)apiClass.getField("WILDNESS").get(null);
            Method findBy = Class.forName("org.teacon.areacontrol.api.AreaLookup").getMethod("findBy", String.class, int.class, int.class, int.class);
            Class<?> areaClass = Class.forName("org.teacon.areacontrol.api.Area");
            Field uid = areaClass.getField("uid");
            LOGGER.info("AreaControl detected: strict acoustic region isolation enabled");
            return new AreaControlAudioCompat.Adapter(lookup, findBy, uid, wildness);
         } catch (RuntimeException | ReflectiveOperationException var6) {
            LOGGER.warn("AreaControl is installed but its public API is unavailable; NCPB audio isolation is disabled", var6);
            return null;
         }
      }
   }

   private record Adapter(Object lookup, Method findBy, Field uid, UUID wildness) {
      private UUID find(String dimension, BlockPos pos) throws ReflectiveOperationException {
         Object area = this.findBy.invoke(this.lookup, dimension, pos.getX(), pos.getY(), pos.getZ());
         return area != null ? (UUID)this.uid.get(area) : this.wildness;
      }
   }
}
