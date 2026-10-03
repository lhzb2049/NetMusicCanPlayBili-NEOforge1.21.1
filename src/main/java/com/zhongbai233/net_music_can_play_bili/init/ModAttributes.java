package com.zhongbai233.net_music_can_play_bili.init;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.ai.attributes.Attribute.Sentiment;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModAttributes {
   public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, "net_music_can_play_bili");
   public static final DeferredHolder<Attribute, Attribute> HEADPHONES = ATTRIBUTES.register(
      "headphones",
      () -> new RangedAttribute("attribute.name.net_music_can_play_bili.headphones", 0.0, 0.0, 1024.0).setSentiment(Sentiment.POSITIVE).setSyncable(true)
   );
   public static final DeferredHolder<Attribute, Attribute> HOLOGRAPHIC_GLASSES = ATTRIBUTES.register(
      "holographic_glasses",
      () -> new RangedAttribute("attribute.name.net_music_can_play_bili.holographic_glasses", 0.0, 0.0, 1024.0)
         .setSentiment(Sentiment.POSITIVE)
         .setSyncable(true)
   );

   private ModAttributes() {
   }
}
