package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableStopPacket;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public class ModernTurntableBlockEntity extends BlockEntity implements PlaybackAudioSource {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String DISC_TAG = "Disc";
   private static final String PLAYING_TAG = "Playing";
   private static final String RAW_URL_TAG = "RawUrl";
   private static final String PLAY_URL_TAG = "PlayUrl";
   private static final String SONG_NAME_TAG = "SongName";
   private static final String DURATION_TAG = "DurationSeconds";
   private static final String STARTED_TIME_TAG = "StartedGameTime";
   private static final String ELAPSED_SECONDS_TAG = "ElapsedSeconds";
   private static final String ELAPSED_TICKS_TAG = "ElapsedTicks";
   private static final String SEEK_GENERATION_TAG = "SeekGeneration";
   private static final String OWNER_TAG = "PlaybackOwner";
   private static final String REPEAT_ONE_TAG = "RepeatOne";
   private static final String REDSTONE_MODE_TAG = "RedstoneMode";
   private static final String EXTRACTION_MODE_TAG = "ExtractionMode";
   private static final String PLAYBACK_COMPLETED_TAG = "PlaybackCompleted";
   private static final String VOLUME_PER_MILLE_TAG = "VolumePerMille";
   private static final String SOURCE_ID_TAG = "PlaybackSourceId";
   private static final int SYNC_RANGE = 96;
   private static final int SYNC_INTERVAL_TICKS = 20;
   private static final Set<ModernTurntableBlockEntity> LOADED_SERVER_TURNTABLES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
   private final Set<UUID> syncedPlayers = new HashSet<>();
   private ItemStack disc = ItemStack.EMPTY;
   private boolean playing;
   private String rawUrl = "";
   private String playUrl = "";
   private String songName = "";
   private int durationSeconds;
   private long startedGameTime;
   private transient MonotonicMediaClock.Anchor playbackClock = MonotonicMediaClock.paused(0L);
   private int savedElapsedSeconds;
   private long savedElapsedTicks;
   private int seekGeneration;
   private boolean needsResolveOnLoad;
   private boolean pendingAutomaticStart;
   private boolean resolvingPlayback;
   private ResolveGeneration playbackIntentGeneration = ResolveGeneration.initial();
   private boolean redstonePowered;
   private boolean redstoneStateInitialized;
   private boolean pulsePlaybackRequested;
   private int lastComparatorOutput = -1;
   private UUID playbackOwnerId;
   private boolean repeatOne;
   private TurntableRedstoneMode redstoneMode = TurntableRedstoneMode.IGNORE;
   private TurntableExtractionMode extractionMode = TurntableExtractionMode.AFTER_PLAYBACK;
   private boolean playbackCompleted;
   private int volumePerMille = 1000;
   private UUID playbackSourceId = UUID.randomUUID();
   private transient LyricRecord clientLyricRecord;
   private transient String clientLyricSessionId = "";
   private transient int clientLyricTick = -1;
   private final ItemStackHandler itemHandler = new ModernTurntableDiscHandler(
      () -> this.disc,
      stack -> this.disc = stack.isEmpty() ? ItemStack.EMPTY : BiliSongInfoSanitizer.sanitizeDisc(stack.copyWithCount(1)),
      this::canAutomationExtract,
      this::onDiscHandlerCommit
   );

   public ModernTurntableBlockEntity(BlockPos pos, BlockState blockState) {
      super((BlockEntityType)ModBlockEntities.MODERN_TURNTABLE.get(), pos, blockState);
   }

   public PlaybackSourceId getPlaybackSourceId() {
      return PlaybackSourceId.of(this.playbackSourceId);
   }

   public void onLoad() {
      super.onLoad();
      if (this.level instanceof ServerLevel) {
         LOADED_SERVER_TURNTABLES.add(this);
         AudioLinkIndex.registerPlaybackSource(
            (ServerLevel)this.level, this.worldPosition, this.getPlaybackSourceId(), AudioPlaybackIndexSavedData.SourceKind.TURNTABLE
         );
      }
   }

   public void setRemoved() {
      this.invalidatePlaybackIntent();
      super.setRemoved();
      LOADED_SERVER_TURNTABLES.remove(this);
      this.syncedPlayers.clear();
      this.clientLyricRecord = null;
      this.clientLyricSessionId = "";
      this.clientLyricTick = -1;
   }

   private void onDiscHandlerCommit(ItemStack originalStack) {
      boolean hadDisc = !originalStack.isEmpty();
      if (hadDisc && this.disc.isEmpty()) {
         this.pendingAutomaticStart = false;
         this.stopPlayback();
      } else if (!hadDisc && !this.disc.isEmpty()) {
         this.playbackCompleted = false;
         this.pendingAutomaticStart = true;
      }

      this.markDirty();
   }

   public ItemStackHandler getItemHandler() {
      return this.itemHandler;
   }

   public static void tick(Level level, BlockPos pos, BlockState state, ModernTurntableBlockEntity turntable) {
      if (level instanceof ServerLevel serverLevel) {
         turntable.pollRedstoneSignal(serverLevel);
         if (turntable.pendingAutomaticStart && turntable.hasDisc() && !turntable.playing && !turntable.resolvingPlayback) {
            turntable.pendingAutomaticStart = false;
            if (turntable.redstoneMode != TurntableRedstoneMode.PULSE_TOGGLE && turntable.redstoneMode.shouldPlay(turntable.redstonePowered)) {
               turntable.startFromDisc();
            }
         }

         if (turntable.needsResolveOnLoad && turntable.playing && !turntable.rawUrl.isBlank()) {
            turntable.needsResolveOnLoad = false;
            turntable.resolveAndResume(serverLevel);
         } else if (!turntable.playing) {
            turntable.updateComparatorOutput();
         } else {
            int remaining = turntable.remainingSeconds();
            if (remaining > 0) {
               if (serverLevel.getServer().getTickCount() % 20 == 0) {
                  turntable.syncNearbyPlayers(serverLevel, remaining);
               }

               turntable.updateComparatorOutput();
               turntable.recordAudit(serverLevel);
            } else {
               if (turntable.repeatOne && turntable.hasPlaybackData()) {
                  turntable.restartForRepeat(serverLevel);
               } else {
                  turntable.playbackCompleted = true;
                  turntable.pulsePlaybackRequested = false;
                  turntable.stopPlayback();
               }
            }
         }
      }
   }

   private void resolveAndResume(ServerLevel serverLevel) {
      if (!this.isPlaybackAllowed(serverLevel, this.rawUrl, null)) {
         this.stopPlayback();
      } else {
         long elapsedTicks = this.snapshotElapsedTicks();
         if ((this.rawUrl.startsWith("BV") || this.rawUrl.startsWith("bv") || this.rawUrl.startsWith("av") || this.rawUrl.startsWith("AV"))
            && this.rawUrl.contains("|p=")) {
            String requestedRawUrl = this.rawUrl;
            ResolveGeneration capturedGeneration = this.beginPlaybackIntent();
            this.playing = false;
            this.syncedPlayers.clear();
            this.markDirty();
            SongInfo resumeInfo = new SongInfo(requestedRawUrl, this.songName, this.durationSeconds, false);
            MusicPlayResolverManager.resolve(resumeInfo).whenCompleteAsync((resolved, error) -> {
               if (this.acceptsPlaybackResult(serverLevel, capturedGeneration, this.rawUrl, requestedRawUrl)) {
                  this.resolvingPlayback = false;
                  if (error != null) {
                     LOGGER.error("现代化唱片机恢复播放 B站 解析失败: {}", this.songName, error);
                  } else {
                     String newUrl = playbackUrlForStorage(requestedRawUrl, resolved.songUrl != null ? resolved.songUrl : this.playUrl);
                     if (!newUrl.isBlank() && this.redstoneAllowsPlayback(serverLevel)) {
                        this.playUrl = newUrl;
                        this.playing = true;
                        this.startedGameTime = MonotonicMediaClock.nowTick();
                        this.playbackClock = MonotonicMediaClock.running(elapsedTicks * 50L, MonotonicMediaClock.nowNanos());
                        this.durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : this.durationSeconds);
                        this.syncedPlayers.clear();
                        this.markDirty();
                        this.syncNearbyPlayers(serverLevel, this.remainingSeconds());
                     }
                  }
               }
            }, serverLevel.getServer());
         } else if (!this.redstoneAllowsPlayback(serverLevel)) {
            this.resolvingPlayback = false;
         } else {
            this.playing = true;
            this.resolvingPlayback = false;
            this.startedGameTime = MonotonicMediaClock.nowTick();
            this.playbackClock = MonotonicMediaClock.running(elapsedTicks * 50L, MonotonicMediaClock.nowNanos());
            this.syncedPlayers.clear();
            this.markDirty();
            this.syncNearbyPlayers(serverLevel, this.remainingSeconds());
         }
      }
   }

   public boolean hasDisc() {
      return !this.disc.isEmpty();
   }

   public ItemStack getDisc() {
      return this.disc;
   }

   @Override
   public boolean isPlaying() {
      return this.playing;
   }

   public boolean isRepeatOne() {
      return this.repeatOne;
   }

   public TurntableRedstoneMode getRedstoneMode() {
      return this.redstoneMode;
   }

   public TurntableExtractionMode getExtractionMode() {
      return this.extractionMode;
   }

   @Override
   public float getVolume() {
      return this.volumePerMille / 1000.0F;
   }

   public int getVolumePerMille() {
      return this.volumePerMille;
   }

   public String getSongName() {
      return this.songName;
   }

   public String getRawUrl() {
      return this.rawUrl;
   }

   public int getDurationSeconds() {
      return this.durationSeconds;
   }

   public boolean hasPlaybackData() {
      return this.durationSeconds > 0 && (!this.playUrl.isBlank() || !this.rawUrl.isBlank());
   }

   public int getComparatorOutput() {
      if (this.hasDisc() && this.durationSeconds > 0 && this.level != null) {
         long elapsedMillis = this.getPlaybackElapsedMillis();
         long durationMillis = Math.max(1L, this.durationSeconds * 1000L);
         return TurntableComparatorSignal.fromProgress(true, elapsedMillis, durationMillis);
      } else {
         return 0;
      }
   }

   public LyricRecord getClientLyricRecord() {
      return this.clientLyricRecord;
   }

   public int getClientLyricTick() {
      return this.clientLyricTick;
   }

   public void setClientLyricRecord(LyricRecord lyricRecord, String sessionId) {
      this.setClientLyricRecord(lyricRecord, sessionId, -1);
   }

   public void setClientLyricRecord(LyricRecord lyricRecord, String sessionId, int lyricTick) {
      if (this.level == null || this.level.isClientSide()) {
         this.clientLyricRecord = lyricRecord;
         this.clientLyricSessionId = normalizeSessionId(sessionId);
         this.clientLyricTick = lyricTick;
      }
   }

   public void clearClientLyricRecord(String sessionId) {
      if (this.level == null || this.level.isClientSide()) {
         String normalized = normalizeSessionId(sessionId);
         if (normalized.isBlank() || this.clientLyricSessionId.isBlank() || this.clientLyricSessionId.equals(normalized)) {
            this.clientLyricRecord = null;
            this.clientLyricSessionId = "";
            this.clientLyricTick = -1;
         }
      }
   }

   @Override
   public long getPlaybackElapsedMillis() {
      return this.playbackClock.elapsedMillis(MonotonicMediaClock.nowNanos(), Math.max(0L, this.durationSeconds * 1000L));
   }

   public PlaybackSync.Metadata getPlaybackSyncMetadata() {
      return this.playing && !this.playUrl.isBlank()
         ? new PlaybackSync.Metadata(this.playbackSessionId(), this.elapsedMillis(), this.durationSeconds * 1000L)
         : new PlaybackSync.Metadata("", 0L, 0L);
   }

   public void setDisc(ItemStack stack) {
      this.disc = stack.isEmpty() ? ItemStack.EMPTY : BiliSongInfoSanitizer.sanitizeDisc(stack);
      this.playbackCompleted = false;
      this.pendingAutomaticStart = false;
      this.resolvingPlayback = false;
      this.stopPlayback();
      this.markDirty();
   }

   public ItemStack removeDisc() {
      ItemStack removed = this.disc;
      this.disc = ItemStack.EMPTY;
      this.pendingAutomaticStart = false;
      this.resolvingPlayback = false;
      this.stopPlayback();
      this.markDirty();
      return removed;
   }

   public ItemStack removeDiscForBlockRemoval() {
      ItemStack removed = this.disc;
      this.disc = ItemStack.EMPTY;
      this.stopPlaybackWithoutBlockUpdate();
      this.setChanged();
      return removed;
   }

   public void stopPlaybackForBlockRemoval() {
      this.stopPlaybackWithoutBlockUpdate();
      this.setChanged();
   }

   public void startFromDisc(ServerPlayer triggerPlayer) {
      this.startFromDiscInternal(triggerPlayer);
   }

   public void startFromDisc() {
      this.startFromDiscInternal(null);
   }

   private void startFromDiscInternal(ServerPlayer triggerPlayer) {
      if (this.level instanceof ServerLevel serverLevel && !this.disc.isEmpty()) {
         this.pendingAutomaticStart = false;
         this.playbackCompleted = false;
         if (this.redstoneAllowsPlayback(serverLevel)) {
            SongInfo songInfo = ItemMusicCD.getSongInfo(this.disc);
            if (songInfo == null) {
               if (triggerPlayer != null) {
                  triggerPlayer.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.modern_turntable.need_cd"));
               }
            } else if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
               if (triggerPlayer != null) {
                  triggerPlayer.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.modern_turntable.need_vip"));
               }
            } else if (this.isPlaybackAllowed(serverLevel, songInfo.songUrl, triggerPlayer)) {
               SongInfo original = songInfo.clone();
               this.playbackOwnerId = triggerPlayer != null ? triggerPlayer.getUUID() : null;
               ResolveGeneration capturedGeneration = this.beginPlaybackIntent();
               MusicPlayResolverManager.resolve(original.clone()).whenCompleteAsync((resolved, error) -> {
                  if (this.acceptsPlaybackResult(serverLevel, capturedGeneration, this.currentDiscUrl(), original.songUrl)) {
                     this.resolvingPlayback = false;
                     if (error != null) {
                        LOGGER.error("现代化唱片机解析播放失败: {}", original.songName, error);
                     } else {
                        this.applyResolvedPlayback(serverLevel, original, resolved);
                     }
                  }
               }, serverLevel.getServer());
            }
         }
      }
   }

   private void applyResolvedPlayback(ServerLevel serverLevel, SongInfo original, SongInfo resolved) {
      SongInfo current = ItemMusicCD.getSongInfo(this.disc);
      if (current != null && Objects.equals(current.songUrl, original.songUrl)) {
         if (!this.isPlaybackAllowed(serverLevel, original.songUrl, null)) {
            this.stopPlayback();
         } else if (this.redstoneAllowsPlayback(serverLevel)) {
            this.rawUrl = original.songUrl != null ? original.songUrl : "";
            this.playUrl = playbackUrlForStorage(this.rawUrl, resolved.songUrl != null ? resolved.songUrl : this.rawUrl);
            this.songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName : original.songName;
            this.durationSeconds = Math.max(1, resolved.songTime);
            this.startedGameTime = MonotonicMediaClock.nowTick();
            this.playbackClock = MonotonicMediaClock.running(0L, MonotonicMediaClock.nowNanos());
            this.savedElapsedSeconds = 0;
            this.savedElapsedTicks = 0L;
            this.seekGeneration = 0;
            this.playing = true;
            this.syncedPlayers.clear();
            this.markDirty();
            this.syncNearbyPlayers(serverLevel, this.durationSeconds);
         }
      }
   }

   private void pollRedstoneSignal(ServerLevel serverLevel) {
      boolean powered = serverLevel.hasNeighborSignal(this.worldPosition);
      boolean stateChanged = !this.redstoneStateInitialized || powered != this.redstonePowered;
      boolean risingEdge = this.redstoneStateInitialized && powered && !this.redstonePowered;
      this.redstonePowered = powered;
      this.redstoneStateInitialized = true;
      if (this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
         if (risingEdge) {
            this.togglePlaybackFromPulse(serverLevel);
         }
      } else if (stateChanged) {
         this.applyRedstoneMode(serverLevel);
      }

      if (this.lastComparatorOutput < 0) {
         this.lastComparatorOutput = this.getComparatorOutput();
      }
   }

   private void applyRedstoneMode(ServerLevel serverLevel) {
      if (this.redstoneStateInitialized
         && this.redstoneMode != TurntableRedstoneMode.IGNORE
         && this.redstoneMode != TurntableRedstoneMode.PULSE_TOGGLE
         && this.hasDisc()) {
         if (this.redstoneMode.shouldPlay(this.redstonePowered)) {
            if (!this.playing && !this.resolvingPlayback) {
               if (this.hasPlaybackData()) {
                  this.resumePlaybackAutomatically(serverLevel);
               } else {
                  this.startFromDisc();
               }
            }
         } else if (this.playing) {
            this.pausePlayback(serverLevel);
         }
      }
   }

   public void cycleRedstoneMode(ServerLevel serverLevel) {
      this.redstoneMode = this.redstoneMode.next();
      this.redstonePowered = serverLevel.hasNeighborSignal(this.worldPosition);
      this.redstoneStateInitialized = true;
      this.pulsePlaybackRequested = this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE && this.playing;
      if (this.redstoneMode == TurntableRedstoneMode.IGNORE) {
         if (this.hasDisc() && !this.playing && !this.resolvingPlayback) {
            if (this.hasPlaybackData()) {
               this.resumePlaybackAutomatically(serverLevel);
            } else {
               this.startFromDisc();
            }
         }
      } else {
         this.applyRedstoneMode(serverLevel);
      }

      this.markDirty();
   }

   public void cycleExtractionMode() {
      this.extractionMode = this.extractionMode.next();
      this.markDirty();
   }

   public void setVolumePerMille(int value) {
      int nextVolume = ModernTurntableVolumePolicy.clamp(value);
      ModernTurntableVolumePolicy.Action action = ModernTurntableVolumePolicy.decide(this.volumePerMille, nextVolume, this.playing);
      if (action != ModernTurntableVolumePolicy.Action.NONE) {
         this.volumePerMille = nextVolume;
         this.markDirty();
      }
   }

   private boolean canAutomationExtract() {
      return this.extractionMode == TurntableExtractionMode.ALWAYS || this.playbackCompleted;
   }

   private void togglePlaybackFromPulse(ServerLevel serverLevel) {
      if (!this.hasDisc()) {
         this.pulsePlaybackRequested = false;
      } else {
         this.pulsePlaybackRequested = !this.pulsePlaybackRequested;
         if (!this.pulsePlaybackRequested) {
            if (this.playing) {
               this.pausePlayback(serverLevel);
            }
         } else if (!this.playing && !this.resolvingPlayback) {
            if (this.hasPlaybackData()) {
               this.resumePlaybackAutomatically(serverLevel);
            } else {
               this.startFromDisc();
            }
         }
      }
   }

   public void pauseFromControl(ServerLevel serverLevel) {
      if (this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
         this.pulsePlaybackRequested = false;
         this.pausePlayback(serverLevel);
      } else if (this.redstoneMode == TurntableRedstoneMode.IGNORE || !this.redstoneAllowsPlayback(serverLevel)) {
         this.pausePlayback(serverLevel);
      }
   }

   private boolean redstoneAllowsPlayback(ServerLevel serverLevel) {
      return this.redstoneMode == TurntableRedstoneMode.IGNORE
         || (
            this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE
               ? this.pulsePlaybackRequested
               : this.redstoneMode.shouldPlay(serverLevel.hasNeighborSignal(this.worldPosition))
         );
   }

   private void resumePlaybackAutomatically(ServerLevel serverLevel) {
      if (this.isPlaybackAllowed(serverLevel, this.rawUrl, null)) {
         long elapsedTicks = this.saveElapsedTicks(this.storedElapsedTicks());
         if (isStoredBiliSelection(this.rawUrl)) {
            this.resolvingPlayback = true;
            this.resolveAndResume(serverLevel);
         } else {
            this.playing = true;
            this.startedGameTime = MonotonicMediaClock.nowTick();
            this.playbackClock = MonotonicMediaClock.running(elapsedTicks * 50L, MonotonicMediaClock.nowNanos());
            this.syncedPlayers.clear();
            this.markDirty();
            this.syncNearbyPlayers(serverLevel, this.remainingSeconds());
         }
      }
   }

   private void updateComparatorOutput() {
      if (this.level != null && !this.level.isClientSide()) {
         int output = this.getComparatorOutput();
         if (output != this.lastComparatorOutput) {
            this.lastComparatorOutput = output;
            this.level.updateNeighbourForOutputSignal(this.worldPosition, this.getBlockState().getBlock());
         }
      }
   }

   private static String playbackUrlForStorage(String rawUrl, String resolvedUrl) {
      return isStoredBiliSelection(rawUrl) ? rawUrl : (resolvedUrl != null ? resolvedUrl : "");
   }

   public void stopPlayback() {
      if (this.level instanceof ServerLevel serverLevel) {
         IndexedBlockPlaybackSessionManager.remove(serverLevel, this.getPlaybackSourceId());
      }

      this.notifyPlaybackStopped();
      this.invalidatePlaybackIntent();
      if (!this.playing && this.playUrl.isBlank()) {
         this.resolvingPlayback = false;
      } else {
         this.clearPlaybackState();
         this.markDirty();
      }
   }

   private void stopPlaybackWithoutBlockUpdate() {
      if (this.level instanceof ServerLevel serverLevel) {
         IndexedBlockPlaybackSessionManager.remove(serverLevel, this.getPlaybackSourceId());
      }

      this.notifyPlaybackStopped();
      this.invalidatePlaybackIntent();
      this.clearPlaybackState();
   }

   private void notifyPlaybackStopped() {
      if (this.level instanceof ServerLevel serverLevel && this.playing && !this.playUrl.isBlank()) {
         PlaybackSessionId stoppedSession = PlaybackSessionId.parse(this.playbackSessionId()).orElse(null);
         if (stoppedSession != null) {
            ModernTurntableStopPacket packet = new ModernTurntableStopPacket(this.worldPosition, stoppedSession.value());
            Set<UUID> recipients = new HashSet<>(this.syncedPlayers);
            AABB range = new AABB(this.worldPosition).inflate(96.0);

            for (ServerPlayer player : serverLevel.players()) {
               if (range.contains(player.position())) {
                  recipients.add(player.getUUID());
               }
            }

            for (UUID recipientId : recipients) {
               ServerPlayer playerx = serverLevel.getServer().getPlayerList().getPlayer(recipientId);
               if (playerx != null && playerx.level() == serverLevel) {
                  PacketDistributor.sendToPlayer(playerx, packet, new CustomPacketPayload[0]);
               }
            }
         }
      }
   }

   private void clearPlaybackState() {
      this.playing = false;
      this.resolvingPlayback = false;
      this.pulsePlaybackRequested = false;
      this.rawUrl = "";
      this.playUrl = "";
      this.songName = "";
      this.durationSeconds = 0;
      this.startedGameTime = 0L;
      this.playbackClock = MonotonicMediaClock.paused(0L);
      this.savedElapsedSeconds = 0;
      this.savedElapsedTicks = 0L;
      this.seekGeneration = 0;
      this.syncedPlayers.clear();
      this.playbackOwnerId = null;
   }

   private ResolveGeneration beginPlaybackIntent() {
      this.resolvingPlayback = true;
      this.playbackIntentGeneration = this.playbackIntentGeneration.next();
      return this.playbackIntentGeneration;
   }

   private void invalidatePlaybackIntent() {
      this.playbackIntentGeneration = this.playbackIntentGeneration.next();
      this.resolvingPlayback = false;
   }

   private boolean acceptsPlaybackResult(ServerLevel requestedLevel, ResolveGeneration capturedGeneration, String currentSource, String requestedSource) {
      return TurntableResolveAdmissionPolicy.decide(
            this.isRemoved(), this.level == requestedLevel, this.playbackIntentGeneration, capturedGeneration, currentSource, requestedSource
         )
         == TurntableResolveAdmissionPolicy.Decision.APPLY;
   }

   private String currentDiscUrl() {
      SongInfo current = ItemMusicCD.getSongInfo(this.disc);
      return current != null ? current.songUrl : null;
   }

   public void replayFromBeginning(ServerPlayer player) {
      if (this.level instanceof ServerLevel && !this.disc.isEmpty()) {
         this.notifyPlaybackStopped();
         if (this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            this.pulsePlaybackRequested = true;
         }

         if (this.redstoneAllowsPlayback((ServerLevel)this.level)) {
            this.playbackCompleted = false;
            this.playing = false;
            this.startedGameTime = 0L;
            this.playbackClock = MonotonicMediaClock.paused(0L);
            this.savedElapsedSeconds = 0;
            this.savedElapsedTicks = 0L;
            this.syncedPlayers.clear();
            this.markDirty();
            this.startFromDisc(player);
         }
      }
   }

   public void toggleRepeatOne() {
      this.repeatOne = !this.repeatOne;
      this.markDirty();
   }

   private void restartForRepeat(ServerLevel serverLevel) {
      this.playbackCompleted = false;
      this.startedGameTime = MonotonicMediaClock.nowTick();
      this.playbackClock = MonotonicMediaClock.running(0L, MonotonicMediaClock.nowNanos());
      this.savedElapsedSeconds = 0;
      this.savedElapsedTicks = 0L;
      this.seekGeneration++;
      this.syncedPlayers.clear();
      this.markDirty();
      this.syncNearbyPlayers(serverLevel, this.durationSeconds);
   }

   public void pausePlayback(ServerLevel serverLevel) {
      if (this.playing || this.resolvingPlayback) {
         if (this.playing) {
            IndexedBlockPlaybackSessionManager.remove(serverLevel, this.getPlaybackSourceId());
            this.notifyPlaybackStopped();
            this.snapshotElapsedTicks();
         }

         this.invalidatePlaybackIntent();
         this.playing = false;
         this.startedGameTime = 0L;
         this.playbackClock = MonotonicMediaClock.paused(this.storedElapsedTicks() * 50L);
         this.syncedPlayers.clear();
         this.markDirty();
      }
   }

   public void resumePlayback(ServerPlayer player) {
      this.resumePlayback(player, -1L);
   }

   public void resumePlayback(ServerPlayer player, long targetMillis) {
      if (this.level instanceof ServerLevel serverLevel && !this.playing) {
         if (this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            this.pulsePlaybackRequested = true;
         }

         if (this.redstoneAllowsPlayback(serverLevel)) {
            this.playbackCompleted = false;
            if (!this.hasPlaybackData()) {
               this.startFromDisc(player);
            } else {
               this.playbackOwnerId = player.getUUID();
               if (this.isPlaybackAllowed(serverLevel, this.rawUrl, player)) {
                  long elapsedTicks = targetMillis >= 0L
                     ? this.saveElapsedTicks(Math.round(Math.max(0L, targetMillis) / 50.0))
                     : this.saveElapsedTicks(this.storedElapsedTicks());
                  if (isStoredBiliSelection(this.rawUrl)) {
                     this.playing = false;
                     this.syncedPlayers.clear();
                     this.markDirty();
                     this.resolveAndResume(serverLevel);
                  } else {
                     this.playing = true;
                     this.startedGameTime = MonotonicMediaClock.nowTick();
                     this.playbackClock = MonotonicMediaClock.running(elapsedTicks * 50L, MonotonicMediaClock.nowNanos());
                     this.syncedPlayers.clear();
                     this.markDirty();
                     this.syncNearbyPlayers(serverLevel, this.remainingSeconds());
                  }
               }
            }
         }
      }
   }

   public void seekTo(ServerLevel serverLevel, long targetMillis) {
      if (this.hasPlaybackData() && this.durationSeconds > 0) {
         long targetTicks = this.clampElapsedTicks(Math.round(Math.max(0L, targetMillis) / 50.0));
         this.saveElapsedTicks(targetTicks);
         this.playbackClock = MonotonicMediaClock.paused(targetTicks * 50L);
         if (this.playing) {
            this.startedGameTime = MonotonicMediaClock.nowTick();
            this.playbackClock = MonotonicMediaClock.running(targetTicks * 50L, MonotonicMediaClock.nowNanos());
            this.seekGeneration++;
            this.syncedPlayers.clear();
            this.markDirty();
            this.syncNearbyPlayers(serverLevel, this.remainingSeconds());
         } else {
            this.markDirty();
         }
      }
   }

   private long clampElapsedTicks(long ticks) {
      long maxTicks = Math.max(0L, this.durationSeconds * 20L - 1L);
      return Math.max(0L, Math.min(maxTicks, ticks));
   }

   private long storedElapsedTicks() {
      return this.savedElapsedTicks > 0L ? this.savedElapsedTicks : this.savedElapsedSeconds * 20L;
   }

   private long saveElapsedTicks(long elapsedTicks) {
      long clamped = this.clampElapsedTicks(elapsedTicks);
      this.savedElapsedTicks = clamped;
      this.savedElapsedSeconds = (int)(clamped / 20L);
      return clamped;
   }

   private long snapshotElapsedTicks() {
      return this.playing ? this.saveElapsedTicks(this.getPlaybackElapsedMillis() / 50L) : this.saveElapsedTicks(this.storedElapsedTicks());
   }

   private static boolean isStoredBiliSelection(String value) {
      return value != null && (value.startsWith("BV") || value.startsWith("bv") || value.startsWith("av") || value.startsWith("AV")) && value.contains("|p=");
   }

   private static String normalizeSessionId(String sessionId) {
      return sessionId != null ? sessionId : "";
   }

   private void syncNearbyPlayers(ServerLevel serverLevel, int remainingSeconds) {
      if (!this.playUrl.isBlank()) {
         if (!this.isPlaybackAllowed(serverLevel, this.rawUrl.isBlank() ? this.playUrl : this.rawUrl, null)) {
            this.stopPlayback();
         } else {
            long elapsedMillis = this.elapsedMillis();
            Set<UUID> nearby = IndexedBlockPlaybackSessionManager.publishAndSync(
               serverLevel,
               this.level,
               this.getPlaybackSourceId(),
               this.worldPosition,
               this.playUrl,
               this.rawUrl,
               this.songName,
               this.playbackSessionId(),
               elapsedMillis,
               this.durationSeconds * 1000L,
               remainingSeconds,
               this.repeatOne
            );
            this.syncedPlayers.clear();
            this.syncedPlayers.addAll(nearby);
         }
      }
   }

   public static void syncLoadedTurntablesToSpectators(ServerLevel serverLevel) {
      ModernTurntableBlockEntity[] loaded;
      synchronized (LOADED_SERVER_TURNTABLES) {
         loaded = LOADED_SERVER_TURNTABLES.toArray(ModernTurntableBlockEntity[]::new);
      }

      for (ModernTurntableBlockEntity turntable : loaded) {
         if (!turntable.isRemoved() && turntable.level == serverLevel) {
            turntable.syncNearbySpectators(serverLevel);
         }
      }
   }

   public static void clearLoadedServerTurntables() {
      LOADED_SERVER_TURNTABLES.clear();
   }

   private void syncNearbySpectators(ServerLevel serverLevel) {
      if (this.playing && !this.playUrl.isBlank()) {
         int remaining = this.remainingSeconds();
         if (remaining > 0) {
            long elapsedMillis = this.elapsedMillis();
            Set<UUID> retained = ModernTurntableAudienceSync.syncNearbySpectators(
               serverLevel,
               this.level,
               this.worldPosition,
               this.getPlaybackSourceId(),
               this.syncedPlayers,
               this.playUrl,
               this.rawUrl,
               this.songName,
               this.playbackSessionId(),
               elapsedMillis,
               this.durationSeconds * 1000L,
               remaining,
               96
            );
            this.syncedPlayers.clear();
            this.syncedPlayers.addAll(retained);
         }
      }
   }

   private void recordAudit(ServerLevel serverLevel) {
      PlaybackAuditManager.recordModernTurntable(
         serverLevel,
         this.worldPosition,
         this.songName,
         this.rawUrl.isBlank() ? this.playUrl : this.rawUrl,
         this.durationSeconds,
         this.getPlaybackElapsedMillis(),
         this.playbackOwnerId
      );
   }

   private boolean isPlaybackAllowed(ServerLevel serverLevel, String sourceUrl, ServerPlayer actor) {
      return ModernTurntablePlaybackAccess.isAllowed(serverLevel, sourceUrl, actor);
   }

   private String playbackSessionId() {
      return ModernTurntablePlaybackClock.sessionId(this.level, this.worldPosition, this.startedGameTime, this.seekGeneration);
   }

   private int remainingSeconds() {
      if (this.playing && this.durationSeconds > 0) {
         long remainingMillis = Math.max(0L, this.durationSeconds * 1000L - this.getPlaybackElapsedMillis());
         return (int)((remainingMillis + 999L) / 1000L);
      } else {
         return 0;
      }
   }

   private long elapsedMillis() {
      return this.getPlaybackElapsedMillis();
   }

   protected void saveAdditional(CompoundTag output, Provider registries) {
      super.saveAdditional(output, registries);
      if (!this.disc.isEmpty()) {
         output.put("Disc", this.disc.save(registries));
      }

      output.putBoolean("Playing", this.playing);
      output.putString("RawUrl", this.rawUrl);
      output.putString("PlayUrl", this.playUrl);
      output.putString("SongName", this.songName);
      output.putInt("DurationSeconds", this.durationSeconds);
      output.putLong("StartedGameTime", this.startedGameTime);
      long elapsedTicks = this.level instanceof ServerLevel ? this.snapshotElapsedTicks() : this.saveElapsedTicks(this.storedElapsedTicks());
      output.putInt("ElapsedSeconds", (int)(elapsedTicks / 20L));
      output.putLong("ElapsedTicks", elapsedTicks);
      output.putInt("SeekGeneration", this.seekGeneration);
      if (this.playbackOwnerId != null) {
         output.putString("PlaybackOwner", this.playbackOwnerId.toString());
      }

      output.putBoolean("RepeatOne", this.repeatOne);
      output.putString("RedstoneMode", this.redstoneMode.serializedName());
      output.putString("ExtractionMode", this.extractionMode.serializedName());
      output.putBoolean("PlaybackCompleted", this.playbackCompleted);
      output.putInt("VolumePerMille", this.volumePerMille);
      output.putString("PlaybackSourceId", this.playbackSourceId.toString());
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      this.invalidatePlaybackIntent();
      this.disc = BiliSongInfoSanitizer.sanitizeDisc(
         input.contains("Disc", 10) ? ItemStack.parse(registries, input.getCompound("Disc")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY
      );
      this.playing = LinkHelper.getBooleanOr(input, "Playing", false);
      this.rawUrl = LinkHelper.getStringOr(input, "RawUrl", "");
      this.playUrl = LinkHelper.getStringOr(input, "PlayUrl", "");
      this.songName = LinkHelper.getStringOr(input, "SongName", "");
      this.durationSeconds = LinkHelper.getIntOr(input, "DurationSeconds", 0);
      this.startedGameTime = LinkHelper.getLongOr(input, "StartedGameTime", 0L);
      this.savedElapsedSeconds = LinkHelper.getIntOr(input, "ElapsedSeconds", 0);
      this.savedElapsedTicks = LinkHelper.getLongOr(input, "ElapsedTicks", this.savedElapsedSeconds * 20L);
      this.seekGeneration = Math.max(0, LinkHelper.getIntOr(input, "SeekGeneration", 0));
      this.playbackOwnerId = parseUuid(LinkHelper.getStringOr(input, "PlaybackOwner", ""));
      this.repeatOne = LinkHelper.getBooleanOr(input, "RepeatOne", false);
      this.redstoneMode = TurntableRedstoneMode.byName(LinkHelper.getStringOr(input, "RedstoneMode", "ignore"));
      this.extractionMode = TurntableExtractionMode.byName(LinkHelper.getStringOr(input, "ExtractionMode", "after_playback"));
      this.playbackCompleted = LinkHelper.getBooleanOr(input, "PlaybackCompleted", false);
      this.volumePerMille = Math.max(0, Math.min(1000, LinkHelper.getIntOr(input, "VolumePerMille", 1000)));
      this.playbackSourceId = parseUuid(LinkHelper.getStringOr(input, "PlaybackSourceId", ""));
      if (this.playbackSourceId == null) {
         this.playbackSourceId = UUID.randomUUID();
      }

      this.redstoneStateInitialized = false;
      this.pulsePlaybackRequested = this.redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE && this.playing;
      this.saveElapsedTicks(this.savedElapsedTicks);
      this.playbackClock = this.playing
         ? MonotonicMediaClock.running(this.savedElapsedTicks * 50L, MonotonicMediaClock.nowNanos())
         : MonotonicMediaClock.paused(this.savedElapsedTicks * 50L);
      this.syncedPlayers.clear();
      this.needsResolveOnLoad = this.playing && this.durationSeconds > 0 && this.savedElapsedTicks < this.durationSeconds * 20L;
   }

   public CompoundTag getUpdateTag(Provider registries) {
      return this.saveWithoutMetadata(registries);
   }

   public Packet<ClientGamePacketListener> getUpdatePacket() {
      return ClientboundBlockEntityDataPacket.create(this);
   }

   public void markDirty() {
      this.setChanged();
      if (this.level != null) {
         BlockState current = this.level.getBlockState(this.worldPosition);
         if (current.getBlock() instanceof ModernTurntableBlock
            && (
               (Boolean)current.getValue(ModernTurntableBlock.HAS_DISC) != this.hasDisc()
                  || (Boolean)current.getValue(ModernTurntableBlock.PLAYING) != this.isPlaying()
            )) {
            this.level
               .setBlock(
                  this.worldPosition,
                  (BlockState)((BlockState)current.setValue(ModernTurntableBlock.HAS_DISC, this.hasDisc()))
                     .setValue(ModernTurntableBlock.PLAYING, this.isPlaying()),
                  3
               );
         }

         BlockState state = this.getBlockState();
         this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
         this.updateComparatorOutput();
      }
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
