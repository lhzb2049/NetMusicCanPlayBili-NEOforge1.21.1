package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.PadMapClientHooks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PadMapWorldScopePacket(String worldScopeId, String worldName) implements CustomPacketPayload {
   public static final Type<PadMapWorldScopePacket> TYPE = new Type(NetworkPayloadIds.id("pad_map_world_scope"));
   private static final int MAX_SCOPE_LENGTH = 256;
   public static final StreamCodec<RegistryFriendlyByteBuf, PadMapWorldScopePacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, PadMapWorldScopePacket>() {
      public PadMapWorldScopePacket decode(RegistryFriendlyByteBuf buffer) {
         return new PadMapWorldScopePacket(buffer.readUtf(256), buffer.readUtf(256));
      }

      public void encode(RegistryFriendlyByteBuf buffer, PadMapWorldScopePacket packet) {
         buffer.writeUtf(packet.worldScopeId(), 256);
         buffer.writeUtf(packet.worldName(), 256);
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(PadMapWorldScopePacket payload, IPayloadContext context) {
      context.enqueueWork(() -> PadMapClientHooks.setServerWorldScope(payload.worldScopeId(), payload.worldName()));
   }
}
