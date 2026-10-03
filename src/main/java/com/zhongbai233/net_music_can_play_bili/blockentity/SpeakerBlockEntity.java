package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap.Builder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class SpeakerBlockEntity extends SyncedBlockEntity {
   private static final String LINK_KEY = "LinkedTarget";
   private static final String CHANNEL_INDEX = "ChannelIndex";
   private static final String VOLUME = "Volume";
   private static final String AUTO_MIX_JOC = "AutoMixJoc";
   private static final String ENDPOINT_ID = "AudioEndpointId";
   private static final String ENDPOINT_REVISION = "AudioEndpointRevision";
   public static final int CH_NONE = -1;
   public static final int CH_L = 0;
   public static final int CH_R = 1;
   public static final int CH_C = 2;
   public static final int CH_LFE = 3;
   public static final int CH_LS = 4;
   public static final int CH_RS = 5;
   public static final int CH_LRS = 6;
   public static final int CH_RRS = 7;
   public static final int CH_LTF = 8;
   public static final int CH_RTF = 9;
   public static final int CH_LTR = 10;
   public static final int CH_RTR = 11;
   public static final int CH_COUNT = 12;
   public static final String[] CH_NAMES = new String[]{"L", "R", "C", "LFE", "Ls", "Rs", "Lrs", "Rrs", "Ltf", "Rtf", "Ltr", "Rtr"};
   @Nullable
   private BlockPos linkedTurntablePos;
   private int channelIndex = -1;
   private float volume = 1.0F;
   private boolean autoMixJoc;
   private UUID endpointId = UUID.randomUUID();
   private long endpointRevision;

   public static int channelBit(int index) {
      return index >= 0 && index < 12 ? 1 << index : 0;
   }

   public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
      super((BlockEntityType<?>)ModBlockEntities.SPEAKER.get(), pos, blockState);
   }

   public void linkTo(BlockPos turntablePos) {
      this.linkedTurntablePos = turntablePos.immutable();
      this.endpointRevision++;
      this.syncServerEndpoint();
      this.markDirtyAndSync();
   }

   public void unlink() {
      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.removeSpeakerEndpoint(serverLevel, this.endpointId, this.worldPosition);
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

   public int getChannelIndex() {
      return this.channelIndex;
   }

   public void setChannelIndex(int v) {
      this.channelIndex = v;
      this.endpointRevision++;
      this.syncServerEndpoint();
      this.setChanged();
   }

   public float getVolume() {
      return this.volume;
   }

   public void setVolume(float v) {
      this.volume = Math.clamp(v, 0.0F, 2.0F);
      this.endpointRevision++;
      this.syncServerEndpoint();
      this.setChanged();
   }

   public boolean isAutoMixJoc() {
      return this.autoMixJoc;
   }

   public void setAutoMixJoc(boolean v) {
      this.autoMixJoc = v;
      this.endpointRevision++;
      this.syncServerEndpoint();
      this.setChanged();
   }

   protected void saveAdditional(CompoundTag output, Provider registries) {
      super.saveAdditional(output, registries);
      LinkHelper.saveLinkToBE(output, this.linkedTurntablePos, "LinkedTarget_has", "LinkedTarget_x", "LinkedTarget_y", "LinkedTarget_z");
      output.putInt("ChannelIndex", this.channelIndex);
      output.putFloat("Volume", this.volume);
      output.putBoolean("AutoMixJoc", this.autoMixJoc);
      output.putString("AudioEndpointId", this.endpointId.toString());
      output.putLong("AudioEndpointRevision", this.endpointRevision);
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      this.linkedTurntablePos = LinkHelper.loadLinkFromBE(input, "LinkedTarget_has", "LinkedTarget_x", "LinkedTarget_y", "LinkedTarget_z");
      this.channelIndex = LinkHelper.getIntOr(input, "ChannelIndex", -1);
      this.volume = LinkHelper.getFloatOr(input, "Volume", 1.0F);
      this.autoMixJoc = LinkHelper.getBooleanOr(input, "AutoMixJoc", false);
      UUID savedEndpointId = parseUuid(LinkHelper.getStringOr(input, "AudioEndpointId", ""));
      this.endpointId = savedEndpointId != null ? savedEndpointId : UUID.randomUUID();
      this.endpointRevision = Math.max(0L, LinkHelper.getLongOr(input, "AudioEndpointRevision", 0L));
      if (this.level != null && this.level.isClientSide()) {
         this.syncAudioOverride();
      } else if (this.level instanceof ServerLevel && this.linkedTurntablePos != null) {
         this.syncServerEndpoint();
      }
   }

   protected void collectImplicitComponents(Builder components) {
      super.collectImplicitComponents(components);
      if (this.linkedTurntablePos != null) {
         components.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
   }

   public void onLoad() {
      super.onLoad();
      if (this.level instanceof ServerLevel) {
         this.syncServerEndpoint();
      } else if (this.level != null && this.level.isClientSide()) {
         this.syncAudioOverride();
      }
   }

   public void setRemoved() {
      if (this.level != null && this.level.isClientSide()) {
         boolean bindingDestroyed = !this.level.getBlockState(this.worldPosition).is((Block)ModBlocks.SPEAKER.get());
         if (bindingDestroyed) {
            ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(this.worldPosition);
         }
      }

      super.setRemoved();
   }

   private void syncAudioOverride() {
      ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(this.worldPosition);
      if (this.linkedTurntablePos != null) {
         SpeakerAudioRelay relay = new SpeakerAudioRelay();
         relay.setChannelIndex(this.channelIndex);
         relay.setUserVolume(this.volume);
         ClientAudioOutputRegistry.registerRelay(this.worldPosition, this.linkedTurntablePos, relay);
         ClientAudioOutputRegistry.updateRelayConfig(this.worldPosition, this.channelIndex, this.volume, this.autoMixJoc);
      }
   }

   public UUID getEndpointId() {
      return this.endpointId;
   }

   private void syncServerEndpoint() {
      if (this.level instanceof ServerLevel serverLevel && this.linkedTurntablePos != null) {
         AudioLinkIndex.upsertSpeakerEndpoint(
            serverLevel,
            this.endpointId,
            this.worldPosition,
            this.linkedTurntablePos,
            this.channelIndex,
            this.volume,
            this.autoMixJoc,
            64.0F,
            this.endpointRevision
         );
      }
   }

   @Nullable
   private static UUID parseUuid(String value) {
      try {
         return value != null && !value.isBlank() ? UUID.fromString(value) : null;
      } catch (IllegalArgumentException var2) {
         return null;
      }
   }
}
