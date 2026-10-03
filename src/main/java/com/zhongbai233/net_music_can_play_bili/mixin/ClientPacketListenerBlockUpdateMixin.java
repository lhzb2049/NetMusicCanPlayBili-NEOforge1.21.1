package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ClientPacketListener.class})
public abstract class ClientPacketListenerBlockUpdateMixin {
   @Shadow
   private ClientLevel level;

   @Inject(
      method = {"handleBlockUpdate"},
      at = {@At("TAIL")},
      remap = false
   )
   private void net_music_can_play_bili$markSingleBlockDirty(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
      BlockPos pos = packet.getPos();
      if (this.net_music_can_play_bili$isLoaded(pos.getX(), pos.getZ())) {
         PadMapClientCache.markBlockDirty(this.level, pos);
         TerrainPreviewManager.markBlockDirty(this.level, pos);
      }
   }

   @Inject(
      method = {"handleChunkBlocksUpdate"},
      at = {@At("TAIL")},
      remap = false
   )
   private void net_music_can_play_bili$markSectionBlocksDirty(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
      packet.runUpdates((pos, state) -> {
         if (this.net_music_can_play_bili$isLoaded(pos.getX(), pos.getZ())) {
            PadMapClientCache.markChunkDirty(this.level, Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
            TerrainPreviewManager.markBlockDirty(this.level, pos);
         }
      });
   }

   private boolean net_music_can_play_bili$isLoaded(int blockX, int blockZ) {
      return this.level != null && this.level.getChunkSource().hasChunk(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
   }
}
