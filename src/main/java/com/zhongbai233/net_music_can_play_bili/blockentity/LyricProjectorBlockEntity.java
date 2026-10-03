package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap.Builder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class LyricProjectorBlockEntity extends SyncedBlockEntity {
   private static final String LINK_KEY = "LinkedTarget";
   private static final String PROJ_YAW = "ProjYaw";
   private static final String PROJ_PITCH = "ProjPitch";
   private static final String PROJ_SCALE = "ProjScale";
   private static final String PROJ_HEIGHT = "ProjHeight";
   private static final String PROJ_DISTANCE_X = "ProjDistanceX";
   private static final String PROJ_DISTANCE_Z = "ProjDistanceZ";
   private static final String PROJ_MODE = "ProjMode";
   private static final String ALLOW_AI = "AllowAi";
   @Nullable
   private BlockPos linkedTurntablePos;
   private float projectionYaw = 180.0F;
   private float projectionPitch = 0.0F;
   private float projectionScale = 1.0F;
   private float projectionHeight = 1.2F;
   private float projectionDistanceX = 0.0F;
   private float projectionDistanceZ = 0.0F;
   private int projectionMode;
   private boolean allowAi;
   private transient LyricRecord cachedLyricRecord;
   private transient LyricRecord cachedAiLyricRecord;
   private transient String cachedAiRawUrl;
   private transient long cachedAiBaseTick = -1L;

   public LyricProjectorBlockEntity(BlockPos pos, BlockState blockState) {
      super((BlockEntityType<?>)ModBlockEntities.LYRIC_PROJECTOR.get(), pos, blockState);
   }

   public void linkTo(BlockPos turntablePos) {
      this.linkedTurntablePos = turntablePos.immutable();
      this.syncClientLink();
      this.markDirtyAndSync();
   }

   public void unlink() {
      this.linkedTurntablePos = null;
      this.cachedLyricRecord = null;
      this.syncClientLink();
      this.markDirtyAndSync();
   }

   @Nullable
   public BlockPos getLinkedTurntablePos() {
      return this.linkedTurntablePos;
   }

   public boolean isLinked() {
      return this.linkedTurntablePos != null;
   }

   public void cacheLyricRecord(LyricRecord record) {
      this.cachedLyricRecord = record;
   }

   @Nullable
   public LyricRecord getCachedLyricRecord() {
      return this.cachedLyricRecord;
   }

   @Nullable
   public LyricRecord getCachedAiLyricRecord() {
      return this.cachedAiLyricRecord;
   }

   public void setCachedAiLyricRecord(@Nullable LyricRecord record, String rawUrl) {
      this.cachedAiLyricRecord = record;
      this.cachedAiRawUrl = rawUrl;
      this.cachedAiBaseTick = -1L;
   }

   public String getCachedAiRawUrl() {
      return this.cachedAiRawUrl;
   }

   public long getCachedAiBaseTick() {
      return this.cachedAiBaseTick;
   }

   public void setCachedAiBaseTick(long tick) {
      this.cachedAiBaseTick = tick;
   }

   public float getProjectionYaw() {
      return this.projectionYaw;
   }

   public void setProjectionYaw(float v) {
      this.projectionYaw = v;
      this.setChanged();
   }

   public float getProjectionPitch() {
      return this.projectionPitch;
   }

   public void setProjectionPitch(float v) {
      this.projectionPitch = v;
      this.setChanged();
   }

   public float getProjectionScale() {
      return this.projectionScale;
   }

   public void setProjectionScale(float v) {
      this.projectionScale = v;
      this.setChanged();
   }

   public float getProjectionHeight() {
      return this.projectionHeight;
   }

   public void setProjectionHeight(float v) {
      this.projectionHeight = v;
      this.setChanged();
   }

   public float getProjectionDistance() {
      return this.projectionDistanceX;
   }

   public void setProjectionDistance(float v) {
      this.setProjectionDistanceX(v);
   }

   public float getProjectionDistanceX() {
      return this.projectionDistanceX;
   }

   public void setProjectionDistanceX(float v) {
      this.projectionDistanceX = v;
      this.setChanged();
   }

   public float getProjectionDistanceZ() {
      return this.projectionDistanceZ;
   }

   public void setProjectionDistanceZ(float v) {
      this.projectionDistanceZ = v;
      this.setChanged();
   }

   public int getProjectionMode() {
      return this.projectionMode;
   }

   public void setProjectionMode(int v) {
      this.projectionMode = v;
      this.setChanged();
   }

   public boolean getAllowAi() {
      return this.allowAi;
   }

   public void setAllowAi(boolean v) {
      this.allowAi = v;
      this.setChanged();
   }

   public void onLoad() {
      super.onLoad();
      this.syncClientLink();
   }

   public void setRemoved() {
      if (this.level != null && this.level.isClientSide()) {
         boolean bindingDestroyed = !this.level.getBlockState(this.worldPosition).is((Block)ModBlocks.LYRIC_PROJECTOR.get());
         if (bindingDestroyed) {
            ClientLinkRegistry.unlink(this.worldPosition);
         }
      }

      super.setRemoved();
   }

   protected void saveAdditional(CompoundTag output, Provider registries) {
      super.saveAdditional(output, registries);
      LinkHelper.saveLinkToBE(output, this.linkedTurntablePos, "LinkedTarget_has", "LinkedTarget_x", "LinkedTarget_y", "LinkedTarget_z");
      output.putFloat("ProjYaw", this.projectionYaw);
      output.putFloat("ProjPitch", this.projectionPitch);
      output.putFloat("ProjScale", this.projectionScale);
      output.putFloat("ProjHeight", this.projectionHeight);
      output.putFloat("ProjDistanceX", this.projectionDistanceX);
      output.putFloat("ProjDistanceZ", this.projectionDistanceZ);
      output.putInt("ProjMode", this.projectionMode);
      output.putBoolean("AllowAi", this.allowAi);
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input, "LinkedTarget_has", "LinkedTarget_x", "LinkedTarget_y", "LinkedTarget_z");
      this.projectionYaw = LinkHelper.getFloatOr(input, "ProjYaw", 180.0F);
      this.projectionPitch = LinkHelper.getFloatOr(input, "ProjPitch", 0.0F);
      this.projectionScale = LinkHelper.getFloatOr(input, "ProjScale", 1.0F);
      this.projectionHeight = LinkHelper.getFloatOr(input, "ProjHeight", 1.2F);
      this.projectionDistanceX = LinkHelper.getFloatOr(input, "ProjDistanceX", 0.0F);
      this.projectionDistanceZ = LinkHelper.getFloatOr(input, "ProjDistanceZ", 0.0F);
      this.projectionMode = LinkHelper.getIntOr(input, "ProjMode", 0);
      this.allowAi = LinkHelper.getBooleanOr(input, "AllowAi", false);
      this.syncClientLink();
   }

   private void syncClientLink() {
      if (this.level != null && this.level.isClientSide()) {
         ClientLinkRegistry.unlink(this.worldPosition);
         if (this.linkedTurntablePos != null) {
            ClientLinkRegistry.linkSubtitleProjector(this.worldPosition, this.linkedTurntablePos);
         }
      }
   }

   protected void collectImplicitComponents(Builder components) {
      super.collectImplicitComponents(components);
      if (this.linkedTurntablePos != null) {
         components.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
   }
}
