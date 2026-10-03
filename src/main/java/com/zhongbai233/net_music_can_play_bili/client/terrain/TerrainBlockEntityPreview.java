package com.zhongbai233.net_music_can_play_bili.client.terrain;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public record TerrainBlockEntityPreview(BlockPos worldPos, BlockEntity blockEntity) {
   public TerrainBlockEntityPreview(BlockPos worldPos, BlockEntity blockEntity) {
      worldPos = Objects.requireNonNull(worldPos, "worldPos").immutable();
      Objects.requireNonNull(blockEntity, "blockEntity");
      this.worldPos = worldPos;
      this.blockEntity = blockEntity;
   }
}
