package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveStreamResolver;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public class LiveStreamerBlockEntity extends BlockEntity implements PlaybackAudioSource {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String ROOM_ID_TAG = "RoomId";
   private static final String PLAYING_TAG = "Playing";
   private static final String AUTO_RESUME_TAG = "AutoResumeRequested";
   private static final String STARTED_TIME_TAG = "StartedGameTime";
   private static final String OWNER_TAG = "PlaybackOwner";
   private static final String VOLUME_PER_MILLE_TAG = "VolumePerMille";
   private static final String SOURCE_ID_TAG = "PlaybackSourceId";
   private static final int SYNC_INTERVAL_TICKS = 20;
   private static final int FULL_RESYNC_INTERVAL_TICKS = 60;
   private static final ExecutorService LIVE_STATUS_EXECUTOR = Executors.newFixedThreadPool(2, NetMusicThreadFactory.daemon("BiliLiveStatus"));
   private static final int LIVE_REMAINING_SECONDS = 86400;
   private final Set<UUID> syncedPlayers = new HashSet<>();
   private String roomId = "";
   private boolean playing;
   private boolean autoResumeRequested;
   private boolean checkingLiveStatus;
   private boolean needsLiveStatusConfirmation;
   private long nextLiveStatusCheckGameTime;
   private ResolveGeneration liveStatusRequestGeneration = ResolveGeneration.initial();
   private long liveStatusProbeId;
   private CancellableTaskFuture<Integer> liveStatusProbeTask;
   private long startedGameTime;
   private long lastFullSyncGameTime;
   private UUID playbackOwnerId;
   private int volumePerMille = 1000;
   private UUID playbackSourceId = UUID.randomUUID();

   public LiveStreamerBlockEntity(BlockPos pos, BlockState blockState) {
      super((BlockEntityType)ModBlockEntities.LIVE_STREAMER.get(), pos, blockState);
   }

   public PlaybackSourceId getPlaybackSourceId() {
      return PlaybackSourceId.of(this.playbackSourceId);
   }

   public void onLoad() {
      super.onLoad();
      if (this.level instanceof ServerLevel serverLevel) {
         AudioLinkIndex.registerPlaybackSource(
            serverLevel, this.worldPosition, this.getPlaybackSourceId(), AudioPlaybackIndexSavedData.SourceKind.LIVE_STREAMER
         );
      }
   }

   public void setRemoved() {
      this.cancelLiveStatusProbe();
      this.liveStatusRequestGeneration = this.liveStatusRequestGeneration.next();
      this.liveStatusProbeId++;
      super.setRemoved();
   }

   public static void tick(Level level, BlockPos pos, BlockState state, LiveStreamerBlockEntity streamer) {
      if (level instanceof ServerLevel serverLevel && streamer.autoResumeRequested) {
         if (!streamer.isLiveAllowed(serverLevel, null)) {
            LOGGER.info("直播间已被移出白名单，停止后台检测: pos={} room={}", pos, streamer.roomId);
            streamer.stopLive();
         } else {
            long gameTime = MonotonicMediaClock.nowTick();
            if (streamer.needsLiveStatusConfirmation) {
               streamer.needsLiveStatusConfirmation = false;
               if (streamer.stopForegroundPlayback()) {
                  streamer.markDirty();
               }
            }

            if (streamer.checkingLiveStatus && gameTime >= streamer.nextLiveStatusCheckGameTime) {
               streamer.cancelLiveStatusProbe();
               streamer.checkingLiveStatus = false;
               streamer.liveStatusProbeId++;
               LOGGER.warn("直播间状态检测超过 10 秒，作废旧结果并继续低频检测: pos={} room={}", pos, streamer.roomId);
            }

            if (streamer.playing) {
               if (gameTime - streamer.lastFullSyncGameTime > 60L) {
                  streamer.syncedPlayers.clear();
                  streamer.lastFullSyncGameTime = gameTime;
               }

               if (gameTime % 20L == 0L) {
                  streamer.syncNearbyPlayers(serverLevel);
               }
            }

            if (LiveStatusProbePolicy.shouldProbe(streamer.autoResumeRequested, streamer.checkingLiveStatus, gameTime, streamer.nextLiveStatusCheckGameTime)) {
               streamer.probeLiveStatus(serverLevel, null, false);
            }
         }
      }
   }

   public String getRoomId() {
      return this.roomId;
   }

   @Override
   public boolean isPlaying() {
      return this.playing;
   }

   public boolean isWaitingForLive() {
      return this.autoResumeRequested && !this.playing;
   }

   @Override
   public float getVolume() {
      return this.volumePerMille / 1000.0F;
   }

   public int getVolumePerMille() {
      return this.volumePerMille;
   }

   @Override
   public long getPlaybackElapsedMillis() {
      return -1L;
   }

   public void setVolumePerMille(int value) {
      this.volumePerMille = Math.max(0, Math.min(1000, value));
      this.markDirty();
   }

   public void setClientVolumePerMille(int value) {
      if (this.level != null && this.level.isClientSide()) {
         this.volumePerMille = Math.max(0, Math.min(1000, value));
      }
   }

   public boolean setRoomId(ServerLevel serverLevel, String input, ServerPlayer actor) {
      String text = input == null ? "" : input.trim();
      String parsed = text.isEmpty() ? "" : BiliLiveRoomInput.parseRoomId(text);
      if (!text.isEmpty() && parsed.isEmpty()) {
         if (actor != null) {
            actor.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.live_streamer.invalid_room").withStyle(ChatFormatting.RED));
         }

         return false;
      } else if (parsed.equals(this.roomId)) {
         return true;
      } else {
         this.stopLive();
         this.roomId = parsed;
         this.markDirty();
         return true;
      }
   }

   public void startLive(ServerLevel serverLevel, ServerPlayer actor) {
      if (this.roomId.isEmpty()) {
         if (actor != null) {
            actor.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.live_streamer.need_room").withStyle(ChatFormatting.RED));
         }
      } else if (this.isLiveAllowed(serverLevel, actor)) {
         if (!this.autoResumeRequested) {
            this.autoResumeRequested = true;
            this.playbackOwnerId = actor != null ? actor.getUUID() : this.playbackOwnerId;
            this.liveStatusRequestGeneration = this.liveStatusRequestGeneration.next();
            this.checkingLiveStatus = false;
            this.nextLiveStatusCheckGameTime = MonotonicMediaClock.nowTick();
            this.markDirty();
            this.probeLiveStatus(serverLevel, actor != null ? actor.getUUID() : null, true);
         }
      }
   }

   private void probeLiveStatus(ServerLevel serverLevel, UUID feedbackTargetId, boolean userInitiated) {
      if (LiveStatusProbePolicy.shouldProbe(this.autoResumeRequested, this.checkingLiveStatus, MonotonicMediaClock.nowTick(), this.nextLiveStatusCheckGameTime)
         )
       {
         this.checkingLiveStatus = true;
         this.nextLiveStatusCheckGameTime = LiveStatusProbePolicy.nextProbeGameTime(MonotonicMediaClock.nowTick());
         String requestedRoomId = this.roomId;
         ResolveGeneration requestGeneration = this.liveStatusRequestGeneration;
         long probeId = ++this.liveStatusProbeId;
         CancellableTaskFuture<Integer> probeTask = CancellableTaskFuture.submit(LIVE_STATUS_EXECUTOR, () -> {
            try {
               return BiliLiveStreamResolver.queryLiveStatus(requestedRoomId);
            } catch (IOException var2x) {
               throw new CompletionException(var2x);
            }
         });
         CancellableTaskFuture<Integer> previous = this.liveStatusProbeTask;
         this.liveStatusProbeTask = probeTask;
         if (previous != null && previous != probeTask) {
            previous.cancel(true);
         }

         probeTask.whenCompleteAsync(
            (liveStatus, error) -> {
               if (this.liveStatusProbeTask == probeTask) {
                  this.liveStatusProbeTask = null;
               }

               if (!this.isRemoved()
                  && this.level instanceof ServerLevel currentLevel
                  && LiveStatusProbePolicy.acceptsResult(
                     this.autoResumeRequested,
                     this.liveStatusRequestGeneration,
                     requestGeneration,
                     this.liveStatusProbeId,
                     probeId,
                     this.roomId,
                     requestedRoomId
                  )) {
                  this.checkingLiveStatus = false;
                  ServerPlayer feedbackTarget = feedbackTargetId != null ? currentLevel.getServer().getPlayerList().getPlayer(feedbackTargetId) : null;
                  if (error != null) {
                     Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                     if (feedbackTarget != null) {
                        feedbackTarget.sendSystemMessage(
                           Component.translatable("message.net_music_can_play_bili.live_streamer.status_unavailable", new Object[]{requestedRoomId})
                              .withStyle(ChatFormatting.YELLOW)
                        );
                     }

                     LOGGER.warn(
                        "直播间状态检测失败，保持后台重试: pos={} room={} reason={}",
                        new Object[]{this.worldPosition, requestedRoomId, cause != null ? cause.toString() : "unknown"}
                     );
                  } else if (liveStatus == 0) {
                     if (this.stopForegroundPlayback()) {
                        this.markDirty();
                     }

                     if (feedbackTarget != null) {
                        feedbackTarget.sendSystemMessage(
                           Component.translatable("message.net_music_can_play_bili.live_streamer.room_offline_waiting", new Object[]{requestedRoomId, 10L})
                              .withStyle(ChatFormatting.YELLOW)
                        );
                     }

                     LOGGER.info("直播间未开播，已关闭前台播放并转入后台检测: pos={} room={} interval={}s", new Object[]{this.worldPosition, requestedRoomId, 10L});
                  } else if (liveStatus < 0) {
                     if (userInitiated) {
                        LOGGER.info("直播状态接口暂不可用，保持后台检测: pos={} room={}", this.worldPosition, requestedRoomId);
                     }
                  } else {
                     if (!this.playing) {
                        this.beginPlaying(currentLevel, this.playbackOwnerId);
                     }
                  }
               }
            },
            serverLevel.getServer()
         );
      }
   }

   private void beginPlaying(ServerLevel serverLevel, UUID actorId) {
      this.playbackOwnerId = actorId != null ? actorId : this.playbackOwnerId;
      this.playing = true;
      this.startedGameTime = MonotonicMediaClock.nowTick();
      this.syncedPlayers.clear();
      this.lastFullSyncGameTime = MonotonicMediaClock.nowTick();
      this.markDirty();
      this.syncNearbyPlayers(serverLevel);
      LOGGER.info("直播机开始播放: pos={} room={} owner={}", new Object[]{this.worldPosition, this.roomId, this.playbackOwnerId});
   }

   public void stopLive() {
      if (this.level instanceof ServerLevel serverLevel) {
         IndexedBlockPlaybackSessionManager.remove(serverLevel, this.getPlaybackSourceId());
      }

      this.autoResumeRequested = false;
      this.liveStatusRequestGeneration = this.liveStatusRequestGeneration.next();
      this.cancelLiveStatusProbe();
      this.checkingLiveStatus = false;
      this.nextLiveStatusCheckGameTime = 0L;
      this.stopForegroundPlayback();
      this.markDirty();
   }

   private boolean stopForegroundPlayback() {
      if (!this.playing) {
         return false;
      } else {
         if (this.level instanceof ServerLevel serverLevel) {
            IndexedBlockPlaybackSessionManager.remove(serverLevel, this.getPlaybackSourceId());
         }

         this.playing = false;
         this.startedGameTime = 0L;
         this.syncedPlayers.clear();
         return true;
      }
   }

   public void stopForBlockRemoval() {
      this.autoResumeRequested = false;
      this.liveStatusRequestGeneration = this.liveStatusRequestGeneration.next();
      this.cancelLiveStatusProbe();
      this.checkingLiveStatus = false;
      this.playing = false;
      this.syncedPlayers.clear();
      this.setChanged();
   }

   private void syncNearbyPlayers(ServerLevel serverLevel) {
      if (this.roomId.isEmpty()) {
         this.stopLive();
      } else if (!this.isLiveAllowed(serverLevel, null)) {
         LOGGER.info("直播间已被移出白名单，停止播放: pos={} room={}", this.worldPosition, this.roomId);
         this.stopLive();
      } else {
         long elapsedMillis = Math.max(0L, (MonotonicMediaClock.nowTick() - this.startedGameTime) * 50L);
         String songName = this.liveSongName();
         Set<UUID> nearby = IndexedBlockPlaybackSessionManager.publishAndSync(
            serverLevel,
            serverLevel,
            this.getPlaybackSourceId(),
            this.worldPosition,
            BiliLiveRoomInput.placeholderUrl(this.roomId),
            this.whitelistSourceId(),
            songName,
            this.playbackSessionId(),
            elapsedMillis,
            0L,
            86400
         );
         this.syncedPlayers.clear();
         this.syncedPlayers.addAll(nearby);
         PlaybackAuditManager.recordModernTurntable(serverLevel, this.worldPosition, songName, this.whitelistSourceId(), 0, elapsedMillis, this.playbackOwnerId);
      }
   }

   private boolean isLiveAllowed(ServerLevel serverLevel, ServerPlayer actor) {
      if (BiliWhitelistManager.enabled() && !BiliWhitelistManager.isAllowed(serverLevel.getServer(), this.whitelistSourceId())) {
         if (actor != null) {
            actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, this.whitelistSourceId(), "播放"));
         }

         return false;
      } else {
         return true;
      }
   }

   private String whitelistSourceId() {
      return "live:" + this.roomId;
   }

   private String liveSongName() {
      return "B站直播 " + this.roomId;
   }

   private String playbackSessionId() {
      return "live-" + Long.toString(this.worldPosition.asLong()) + "-" + Long.toString(this.startedGameTime);
   }

   public void markDirty() {
      this.setChanged();
      if (this.level != null) {
         BlockState current = this.level.getBlockState(this.worldPosition);
         if (current.getBlock() instanceof LiveStreamerBlock && (Boolean)current.getValue(LiveStreamerBlock.PLAYING) != this.playing) {
            this.level.setBlock(this.worldPosition, (BlockState)current.setValue(LiveStreamerBlock.PLAYING, this.playing), 3);
         }

         BlockState state = this.getBlockState();
         this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
      }
   }

   protected void saveAdditional(CompoundTag output, Provider registries) {
      super.saveAdditional(output, registries);
      output.putString("RoomId", this.roomId);
      output.putBoolean("Playing", this.playing);
      output.putBoolean("AutoResumeRequested", this.autoResumeRequested);
      output.putLong("StartedGameTime", this.startedGameTime);
      output.putInt("VolumePerMille", this.volumePerMille);
      output.putString("PlaybackSourceId", this.playbackSourceId.toString());
      if (this.playbackOwnerId != null) {
         output.putString("PlaybackOwner", this.playbackOwnerId.toString());
      }
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      this.roomId = sanitizeRoomId(LinkHelper.getStringOr(input, "RoomId", ""));
      this.playing = LinkHelper.getBooleanOr(input, "Playing", false) && !this.roomId.isEmpty();
      this.autoResumeRequested = LinkHelper.getBooleanOr(input, "AutoResumeRequested", this.playing) && !this.roomId.isEmpty();
      this.checkingLiveStatus = false;
      this.needsLiveStatusConfirmation = this.playing && this.autoResumeRequested;
      this.nextLiveStatusCheckGameTime = 0L;
      this.liveStatusRequestGeneration = this.liveStatusRequestGeneration.next();
      this.cancelLiveStatusProbe();
      this.liveStatusProbeId++;
      this.startedGameTime = LinkHelper.getLongOr(input, "StartedGameTime", 0L);
      this.volumePerMille = Math.max(0, Math.min(1000, LinkHelper.getIntOr(input, "VolumePerMille", 1000)));
      this.playbackSourceId = parseUuid(LinkHelper.getStringOr(input, "PlaybackSourceId", ""));
      if (this.playbackSourceId == null) {
         this.playbackSourceId = UUID.randomUUID();
      }

      this.playbackOwnerId = parseUuid(LinkHelper.getStringOr(input, "PlaybackOwner", ""));
      this.syncedPlayers.clear();
   }

   private void cancelLiveStatusProbe() {
      CancellableTaskFuture<Integer> task = this.liveStatusProbeTask;
      this.liveStatusProbeTask = null;
      if (task != null) {
         task.cancel(true);
      }
   }

   public CompoundTag getUpdateTag(Provider registries) {
      return this.saveWithoutMetadata(registries);
   }

   public Packet<ClientGamePacketListener> getUpdatePacket() {
      return ClientboundBlockEntityDataPacket.create(this);
   }

   private static String sanitizeRoomId(String value) {
      return BiliLiveRoomInput.parseRoomId(value);
   }

   private static UUID parseUuid(String value) {
      if (value != null && !value.isBlank()) {
         try {
            return UUID.fromString(value);
         } catch (IllegalArgumentException var2) {
            return null;
         }
      } else {
         return null;
      }
   }
}
