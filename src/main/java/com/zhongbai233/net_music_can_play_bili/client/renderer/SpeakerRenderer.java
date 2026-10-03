package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.PlaybackAudioSource;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class SpeakerRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {
   public SpeakerRenderer(Context context) {
   }

   public void render(SpeakerBlockEntity speaker, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
      boolean active = false;
      BlockPos linked = speaker.getLinkedTurntablePos();
      if (linked != null) {
         Level level = speaker.getLevel();
         if (level != null && level.getBlockEntity(linked) instanceof PlaybackAudioSource source && source.isPlaying()) {
            active = true;
         }
      }

      BlockState currentState = speaker.getLevel() != null ? speaker.getLevel().getBlockState(speaker.getBlockPos()) : null;
      if (currentState != null && currentState.hasProperty(SpeakerBlock.ACTIVATED)) {
         boolean currentlyActivated = (Boolean)currentState.getValue(SpeakerBlock.ACTIVATED);
         if (active != currentlyActivated) {
            boolean newValue = active;
            Minecraft.getInstance().execute(() -> {
               Level lvl = speaker.getLevel();
               if (lvl != null) {
                  BlockPos pos = speaker.getBlockPos();
                  BlockState bs = lvl.getBlockState(pos);
                  if (bs.hasProperty(SpeakerBlock.ACTIVATED)) {
                     lvl.setBlock(pos, (BlockState)bs.setValue(SpeakerBlock.ACTIVATED, newValue), 3);
                  }
               }
            });
         }
      }
   }
}
