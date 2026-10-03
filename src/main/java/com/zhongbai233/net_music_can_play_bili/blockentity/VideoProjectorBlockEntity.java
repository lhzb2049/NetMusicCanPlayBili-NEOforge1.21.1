package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.media.VideoSurfaceBrightness;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap.Builder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class VideoProjectorBlockEntity extends SyncedBlockEntity {
   private static final String LINK_KEY = "LinkedTarget";
   private static final String PROJ_YAW = "ProjYaw";
   private static final String PROJ_PITCH = "ProjPitch";
   private static final String PROJ_SCALE = "ProjScale";
   private static final String PROJ_HEIGHT = "ProjHeight";
   private static final String PROJ_DISTANCE_X = "ProjDistanceX";
   private static final String PROJ_DISTANCE_Z = "ProjDistanceZ";
   private static final String PROJ_BRIGHTNESS = "ProjBrightness";
   private static final String PREFERRED_QUALITY = "PreferredQuality";
   public static final int DEFAULT_PREFERRED_QUALITY = 116;
   @Nullable
   private BlockPos linkedTurntablePos;
   private float projectionYaw = 180.0F;
   private float projectionPitch = 0.0F;
   private float projectionScale = 1.0F;
   private float projectionHeight = 1.8F;
   private float projectionDistanceX = 0.0F;
   private float projectionDistanceZ = 0.0F;
   private float projectionBrightness = 1.0F;
   private int preferredQuality = 116;

   public VideoProjectorBlockEntity(BlockPos pos, BlockState blockState) {
      super((BlockEntityType<?>)ModBlockEntities.VIDEO_PROJECTOR.get(), pos, blockState);
   }

   public void linkTo(BlockPos turntablePos) {
      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.unregisterVideoProjector(serverLevel, this.worldPosition);
      }

      this.linkedTurntablePos = turntablePos.immutable();
      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.registerVideoProjector(serverLevel, this.worldPosition, this.linkedTurntablePos);
      }

      this.refreshClientLinkRegistration();
      this.markDirtyAndSync();
   }

   public void unlink() {
      if (this.level != null && this.level.isClientSide()) {
         ClientLinkRegistry.unlink(this.worldPosition);
      }

      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.unregisterVideoProjector(serverLevel, this.worldPosition);
      }

      this.linkedTurntablePos = null;
      this.markDirtyAndSync();
   }

   @Nullable
   public BlockPos getLinkedTurntablePos() {
      return this.linkedTurntablePos;
   }

   public boolean isLinked() {
      return this.linkedTurntablePos != null;
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

   public float getProjectionBrightness() {
      return this.projectionBrightness;
   }

   public void setProjectionBrightness(float value) {
      this.projectionBrightness = VideoSurfaceBrightness.normalize(value);
      this.setChanged();
   }

   public int getPreferredQuality() {
      return this.preferredQuality;
   }

   public void setPreferredQuality(int v) {
      this.preferredQuality = v;
      this.setChanged();
   }

   public void setRemoved() {
      super.setRemoved();
      if (this.level != null && this.level.isClientSide()) {
         ClientLinkRegistry.unlink(this.worldPosition);
         VideoBillboardPreview.stopIfProjector(this.worldPosition);
      }

      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.unregisterVideoProjector(serverLevel, this.worldPosition);
      }
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
      output.putFloat("ProjBrightness", this.projectionBrightness);
      output.putInt("PreferredQuality", this.preferredQuality);
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      BlockPos oldLinkedTurntablePos = this.linkedTurntablePos;
      int oldPreferredQuality = this.preferredQuality;
      this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input, "LinkedTarget_has", "LinkedTarget_x", "LinkedTarget_y", "LinkedTarget_z");
      this.projectionYaw = LinkHelper.getFloatOr(input, "ProjYaw", 180.0F);
      this.projectionPitch = LinkHelper.getFloatOr(input, "ProjPitch", 0.0F);
      this.projectionScale = LinkHelper.getFloatOr(input, "ProjScale", 1.0F);
      this.projectionHeight = LinkHelper.getFloatOr(input, "ProjHeight", 1.8F);
      this.projectionDistanceX = LinkHelper.getFloatOr(input, "ProjDistanceX", 0.0F);
      this.projectionDistanceZ = LinkHelper.getFloatOr(input, "ProjDistanceZ", 0.0F);
      this.projectionBrightness = VideoSurfaceBrightness.normalize(LinkHelper.getFloatOr(input, "ProjBrightness", 1.0F));
      this.preferredQuality = LinkHelper.getIntOr(input, "PreferredQuality", 116);
      this.refreshClientLinkRegistration();
      if (this.level instanceof ServerLevel serverLevel && this.linkedTurntablePos != null) {
         AudioLinkIndex.registerVideoProjector(serverLevel, this.worldPosition, this.linkedTurntablePos);
      }

      if (this.level != null
         && this.level.isClientSide()
         && VideoProjectorClientRefreshPolicy.shouldRefresh(oldLinkedTurntablePos, this.linkedTurntablePos, oldPreferredQuality, this.preferredQuality)) {
         ModernTurntableVideoClient.refreshProjector(this.worldPosition);
      }
   }

   protected void collectImplicitComponents(Builder components) {
      super.collectImplicitComponents(components);
      if (this.linkedTurntablePos != null) {
         components.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
   }

   private void refreshClientLinkRegistration() {
      if (this.level != null && this.level.isClientSide()) {
         ClientLinkRegistry.unlink(this.worldPosition);
         if (this.linkedTurntablePos != null) {
            ClientLinkRegistry.link(this.worldPosition, this.linkedTurntablePos);
            LogUtils.getLogger().debug("视频投影仪客户端链接注册: projector={} target={}", this.worldPosition, this.linkedTurntablePos);
         }
      }
   }
}
