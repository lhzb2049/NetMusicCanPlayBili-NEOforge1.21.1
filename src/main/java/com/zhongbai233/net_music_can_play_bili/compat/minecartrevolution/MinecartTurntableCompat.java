package com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class MinecartTurntableCompat {
   private static final String SIMULATION_PACKAGE = "ml.mypals.minecartrevolution.entity.minecarts.simulation.";
   private static final int HOST_MINECART_ENTITY_ID = -2147483647;
   private static final ConcurrentHashMap<Class<?>, Optional<Field>> MINECART_FIELDS = new ConcurrentHashMap<>();
   private static final Map<Level, WeakReference<Entity>> HOSTS_BY_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());

   private MinecartTurntableCompat() {
   }

   public static Entity hostMinecart(Level level) {
      if (level != null && level.getClass().getName().startsWith("ml.mypals.minecartrevolution.entity.minecarts.simulation.")) {
         WeakReference<Entity> cached = HOSTS_BY_LEVEL.get(level);
         Entity cachedHost = cached != null ? cached.get() : null;
         if (isUsable(cachedHost)) {
            return cachedHost;
         } else {
            Entity host = hostFromSentinel(level);
            if (isUsable(host)) {
               cache(level, host);
               return host;
            } else {
               return hostFromLegacyField(level);
            }
         }
      } else {
         return null;
      }
   }

   public static UUID hostUuid(Level level) {
      Entity host = hostMinecart(level);
      return host != null ? host.getUUID() : null;
   }

   private static Entity hostFromSentinel(Level level) {
      try {
         return level.getEntity(-2147483647);
      } catch (RuntimeException var2) {
         return null;
      }
   }

   private static Entity hostFromLegacyField(Level level) {
      Optional<Field> field = MINECART_FIELDS.computeIfAbsent(level.getClass(), MinecartTurntableCompat::findField);
      if (field.isEmpty()) {
         return null;
      } else {
         try {
            if (field.get().get(level) instanceof Entity entity && isUsable(entity)) {
               cache(level, entity);
               return entity;
            } else {
               return null;
            }
         } catch (RuntimeException | ReflectiveOperationException var4) {
            return null;
         }
      }
   }

   private static boolean isUsable(Entity entity) {
      return entity != null && !entity.isRemoved();
   }

   private static void cache(Level level, Entity entity) {
      HOSTS_BY_LEVEL.put(level, new WeakReference<>(entity));
   }

   private static Optional<Field> findField(Class<?> type) {
      Class<?> current = type;

      while (current != null) {
         try {
            Field field = current.getDeclaredField("minecart");
            field.setAccessible(true);
            return Optional.of(field);
         } catch (NoSuchFieldException var3) {
            current = current.getSuperclass();
         } catch (RuntimeException var4) {
            return Optional.empty();
         }
      }

      return Optional.empty();
   }
}
