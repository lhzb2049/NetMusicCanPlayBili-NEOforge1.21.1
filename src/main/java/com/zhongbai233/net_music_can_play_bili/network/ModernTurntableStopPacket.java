package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ModernTurntableStopPacket(BlockPos pos, String sessionId) implements CustomPacketPayload {
   private static final int MAX_SESSION_ID_LENGTH = 128;
   public static final Type<ModernTurntableStopPacket> TYPE = new Type(NetworkPayloadIds.id("modern_turntable_stop"));
   public static final StreamCodec<RegistryFriendlyByteBuf, ModernTurntableStopPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ModernTurntableStopPacket>() {
      public ModernTurntableStopPacket decode(RegistryFriendlyByteBuf buffer) {
         return new ModernTurntableStopPacket((BlockPos)BlockPos.STREAM_CODEC.decode(buffer), buffer.readUtf(128));
      }

      public void encode(RegistryFriendlyByteBuf buffer, ModernTurntableStopPacket packet) {
         BlockPos.STREAM_CODEC.encode(buffer, packet.pos());
         buffer.writeUtf(packet.sessionId(), 128);
      }
   };

   public ModernTurntableStopPacket(BlockPos pos, String sessionId) {
      pos = Objects.requireNonNull(pos, "pos").immutable();
      sessionId = PlaybackSessionId.of(sessionId).value();
      this.pos = pos;
      this.sessionId = sessionId;
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ModernTurntableStopPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> ModernTurntablePlaybackCoordinator.stop(payload.pos(), payload.sessionId()));
   }
}
