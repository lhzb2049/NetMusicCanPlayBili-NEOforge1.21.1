package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import org.slf4j.Logger;

public final class PadMapScopeSavedData extends SavedData {
   private static final String NAME = "pad_map_scope";
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final Codec<PadMapScopeSavedData> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(Codec.STRING.fieldOf("worldScopeId").forGetter(data -> data.worldScopeId)).apply(instance, PadMapScopeSavedData::new)
   );
   // 【反编译伪影修复】补回菱形推断（同 AudioPlaybackIndexSavedData 的说明）
   public static final Factory<PadMapScopeSavedData> FACTORY = new Factory<>(PadMapScopeSavedData::new, PadMapScopeSavedData::load);
   private final String worldScopeId;

   public PadMapScopeSavedData() {
      this(UUID.randomUUID().toString());
      this.setDirty();
   }

   private PadMapScopeSavedData(String worldScopeId) {
      this.worldScopeId = normalize(worldScopeId);
   }

   public static PadMapScopeSavedData get(ServerLevel level) {
      return (PadMapScopeSavedData)level.getDataStorage().computeIfAbsent(FACTORY, "pad_map_scope");
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CODEC.encodeStart(NbtOps.INSTANCE, this).resultOrPartial(error -> LOGGER.error("Failed to save pad map scope: {}", error)).ifPresent(encoded -> {
         if (encoded instanceof CompoundTag compound) {
            tag.merge(compound);
         }
      });
      return tag;
   }

   private static PadMapScopeSavedData load(CompoundTag tag, Provider registries) {
      return CODEC.parse(NbtOps.INSTANCE, tag)
         .resultOrPartial(error -> LOGGER.warn("Failed to load pad map scope: {}", error))
         .orElseGet(PadMapScopeSavedData::new);
   }

   public String worldScopeId() {
      return this.worldScopeId;
   }

   private static String normalize(String value) {
      return value != null && !value.isBlank() ? value.trim() : UUID.randomUUID().toString();
   }
}
