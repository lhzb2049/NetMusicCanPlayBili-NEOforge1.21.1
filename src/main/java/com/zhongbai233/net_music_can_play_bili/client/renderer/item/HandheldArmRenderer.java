package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;

public interface HandheldArmRenderer {
   void renderMapHand(PoseStack var1, MultiBufferSource var2, int var3, HumanoidArm var4);

   void renderPlayerArm(PoseStack var1, MultiBufferSource var2, int var3, float var4, float var5, HumanoidArm var6);
}
