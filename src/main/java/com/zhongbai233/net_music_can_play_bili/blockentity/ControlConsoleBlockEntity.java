package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementLockPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleGeometryValidator;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleRangeMigration;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsolePermissionPolicy;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicPermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class ControlConsoleBlockEntity extends SyncedBlockEntity {
   public static final int CURRENT_SCHEMA_VERSION = 7;
   private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
   private static final String DOCUMENT_TAG = "Document";
   private static final String DOCUMENT_REVISION_TAG = "DocumentRevision";
   private static final String CONSOLE_ID_TAG = "ConsoleId";
   private static final String OWNER_ID_TAG = "OwnerId";
   private static final String ACCESS_MODE_TAG = "AccessMode";
   private static final String TRUSTED_PLAYERS_TAG = "TrustedPlayers";
   private static final String PLAYER_ID_TAG = "PlayerId";
   private static final String DISPLAY_NAME_TAG = "DisplayName";
   private static final String HARD_RANGE_X_TAG = "HardRangeX";
   private static final String HARD_RANGE_Y_TAG = "HardRangeY";
   private static final String HARD_RANGE_Z_TAG = "HardRangeZ";
   private static final String SOURCE_DIMENSION_TAG = "SourceDimension";
   private static final String SOURCE_KIND_TAG = "SourceKind";
   private static final String SOURCE_X_TAG = "SourceX";
   private static final String SOURCE_Y_TAG = "SourceY";
   private static final String SOURCE_Z_TAG = "SourceZ";
   private static final String ELEMENTS_TAG = "Elements";
   private static final String ELEMENT_TYPE_TAG = "Type";
   private static final String ELEMENT_NAME_TAG = "Name";
   private static final String ELEMENT_DISTANCE_TAG = "Distance";
   private static final String ELEMENT_OFFSET_X_TAG = "OffsetX";
   private static final String ELEMENT_OFFSET_Y_TAG = "OffsetY";
   private static final String ELEMENT_HEIGHT_TAG = "Height";
   private static final String ELEMENT_ASPECT_TAG = "Aspect";
   private static final String ELEMENT_YAW_TAG = "Yaw";
   private static final String ELEMENT_PITCH_TAG = "Pitch";
   private static final String ELEMENT_ROLL_TAG = "Roll";
   private static final String ELEMENT_SCALE_X_TAG = "ScaleX";
   private static final String ELEMENT_SCALE_Y_TAG = "ScaleY";
   private static final String ELEMENT_SCALE_Z_TAG = "ScaleZ";
   private static final String ELEMENT_PIVOT_X_TAG = "PivotX";
   private static final String ELEMENT_PIVOT_Y_TAG = "PivotY";
   private static final String ELEMENT_PIVOT_Z_TAG = "PivotZ";
   private static final String ELEMENT_SKEW_X_BY_Y_TAG = "SkewXByY";
   private static final String ELEMENT_SKEW_Y_BY_X_TAG = "SkewYByX";
   private static final String ELEMENT_BRIGHTNESS_TAG = "Brightness";
   private static final String ELEMENT_CONTENT_MODE_TAG = "ContentMode";
   private static final String ELEMENT_TEXT_TAG = "Text";
   private static final String ELEMENT_FOLLOW_LYRICS_TAG = "FollowLyrics";
   private static final String ELEMENT_SHOW_TRANSLATION_TAG = "ShowTranslation";
   private static final String ELEMENT_TEXT_SCALE_TAG = "TextScale";
   private static final String ELEMENT_COLOR_TAG = "Color";
   private static final String ELEMENT_VOLUME_TAG = "Volume";
   private static final String ELEMENT_CHANNEL_INDEX_TAG = "ChannelIndex";
   private static final String ELEMENT_MAX_DISTANCE_TAG = "MaxDistance";
   private static final String ELEMENT_ENABLED_TAG = "Enabled";
   private static final String ELEMENT_ID_TAG = "ElementId";
   private static final String ELEMENT_LOCKED_TAG = "Locked";
   private static final String ELEMENT_TRANSLATION_COLOR_TAG = "TranslationColor";
   private static final String ELEMENT_BACKGROUND_COLOR_TAG = "BackgroundColor";
   private static final String ELEMENT_ALIGNMENT_TAG = "Alignment";
   private static final String ELEMENT_MAX_WIDTH_TAG = "MaxWidth";
   private static final String ELEMENT_WRAP_TAG = "Wrap";
   private ControlConsoleDocument document = ControlConsoleDocument.empty();
   private int loadedSchemaVersion = 7;
   private CompoundTag preservedFutureDocument;
   private final LinkedHashSet<UUID> appliedOperationIds = new LinkedHashSet<>();

   public ControlConsoleBlockEntity(BlockPos pos, BlockState state) {
      super((BlockEntityType<?>)ModBlockEntities.CONTROL_CONSOLE.get(), pos, state);
   }

   public void onLoad() {
      super.onLoad();
      if (this.level != null && this.level.isClientSide()) {
         ControlConsoleRenderer.registerConsumer(this);
      }
   }

   public void setRemoved() {
      if (this.level != null && this.level.isClientSide()) {
         boolean bindingDestroyed = !this.level.getBlockState(this.worldPosition).is((Block)ModBlocks.CONTROL_CONSOLE.get());
         ControlConsoleRenderer.notifyConsoleRemoved(this.worldPosition, bindingDestroyed);
      }

      super.setRemoved();
   }

   public int documentSchemaVersion() {
      return this.loadedSchemaVersion;
   }

   public boolean isDocumentReadOnly() {
      return this.loadedSchemaVersion > 7;
   }

   public long documentRevision() {
      return this.document.revision();
   }

   public void advanceDocumentRevision() {
      if (!this.isDocumentReadOnly()) {
         this.document = this.document.withRevision(this.document.revision() + 1L);
         this.markDirtyAndSync();
      }
   }

   public ControlConsoleDocument document() {
      return this.document;
   }

   public boolean claimIfUnowned(UUID playerId) {
      Objects.requireNonNull(playerId, "playerId");
      if (!this.isDocumentReadOnly() && this.document.ownerId() == null) {
         this.document = this.document.withOwnerIfAbsent(playerId);
         this.markDirtyAndSync();
         return true;
      } else {
         return false;
      }
   }

   public boolean canEdit(ServerPlayer player) {
      if (player != null && !this.isDocumentReadOnly()) {
         boolean administrator = NetMusicPermissions.canAdministerControlConsole(player.createCommandSourceStack());
         return !ControlConsolePermissionPolicy.passesBuildGate(player.mayBuild(), administrator)
            ? false
            : this.document.canEdit(player.getUUID(), administrator);
      } else {
         return false;
      }
   }

   public ControlConsoleBlockEntity.ReplaceResult replaceAccessControl(
      ServerPlayer player, UUID operationId, long expectedRevision, ControlConsoleDocument.AccessMode accessMode, Set<UUID> trustedPlayerIds
   ) {
      Objects.requireNonNull(operationId, "operationId");
      if (player != null && !this.isDocumentReadOnly()) {
         boolean administrator = NetMusicPermissions.canAdministerControlConsole(player.createCommandSourceStack());
         if (!ControlConsolePermissionPolicy.passesBuildGate(player.mayBuild(), administrator)) {
            return ControlConsoleBlockEntity.ReplaceResult.REJECTED;
         } else if (!administrator && !player.getUUID().equals(this.document.ownerId())) {
            return ControlConsoleBlockEntity.ReplaceResult.REJECTED;
         } else if (this.appliedOperationIds.contains(operationId)) {
            return ControlConsoleBlockEntity.ReplaceResult.DUPLICATE;
         } else if (this.document.revision() != expectedRevision) {
            return ControlConsoleBlockEntity.ReplaceResult.CONFLICT;
         } else {
            this.document = this.document.withAccessControl(accessMode, trustedPlayerIds);
            this.rememberOperation(operationId);
            this.markDirtyAndSync();
            return ControlConsoleBlockEntity.ReplaceResult.APPLIED;
         }
      } else {
         return this.isDocumentReadOnly() ? ControlConsoleBlockEntity.ReplaceResult.READ_ONLY : ControlConsoleBlockEntity.ReplaceResult.REJECTED;
      }
   }

   public boolean replaceDocument(
      long expectedRevision, String displayName, double hardRangeX, double hardRangeY, double hardRangeZ, List<ControlConsoleElement> elements
   ) {
      if (this.isDocumentReadOnly()) {
         return false;
      } else if (this.document.revision() != expectedRevision) {
         return false;
      } else if (!ControlConsoleElementLockPolicy.permits(this.document.elements(), elements)) {
         return false;
      } else {
         ControlConsoleGeometryValidator.ValidationResult geometry = ControlConsoleGeometryValidator.validate(hardRangeX, hardRangeY, hardRangeZ, elements);
         if (!geometry.valid()) {
            throw new IllegalArgumentException(geometry.reason());
         } else {
            this.document = new ControlConsoleDocument(
               this.document.schemaVersion(),
               this.document.consoleId(),
               this.document.revision() + 1L,
               this.document.ownerId(),
               this.document.accessMode(),
               this.document.trustedPlayerIds(),
               displayName,
               this.document.sourceDimension(),
               this.document.sourceKind(),
               this.document.sourceX(),
               this.document.sourceY(),
               this.document.sourceZ(),
               hardRangeX,
               hardRangeY,
               hardRangeZ,
               elements
            );
            this.markDirtyAndSync();
            return true;
         }
      }
   }

   public ControlConsoleBlockEntity.ReplaceResult replaceDocument(
      UUID operationId,
      long expectedRevision,
      String displayName,
      double hardRangeX,
      double hardRangeY,
      double hardRangeZ,
      List<ControlConsoleElement> elements
   ) {
      Objects.requireNonNull(operationId, "operationId");
      if (this.appliedOperationIds.contains(operationId)) {
         return ControlConsoleBlockEntity.ReplaceResult.DUPLICATE;
      } else if (this.isDocumentReadOnly()) {
         return ControlConsoleBlockEntity.ReplaceResult.READ_ONLY;
      } else if (this.document.revision() != expectedRevision) {
         return ControlConsoleBlockEntity.ReplaceResult.CONFLICT;
      } else if (!this.replaceDocument(expectedRevision, displayName, hardRangeX, hardRangeY, hardRangeZ, elements)) {
         return ControlConsoleBlockEntity.ReplaceResult.REJECTED;
      } else {
         this.rememberOperation(operationId);
         return ControlConsoleBlockEntity.ReplaceResult.APPLIED;
      }
   }

   private void rememberOperation(UUID operationId) {
      this.appliedOperationIds.add(operationId);

      while (this.appliedOperationIds.size() > 64) {
         this.appliedOperationIds.remove(this.appliedOperationIds.iterator().next());
      }
   }

   public void linkTo(String dimension, BlockPos sourcePos) {
      this.linkTo(dimension, sourcePos, ControlConsoleDocument.SourceKind.TURNTABLE);
   }

   public void linkTo(String dimension, BlockPos sourcePos, ControlConsoleDocument.SourceKind sourceKind) {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(sourcePos, "sourcePos");
      if (!this.isDocumentReadOnly()) {
         this.document = new ControlConsoleDocument(
            this.document.schemaVersion(),
            this.document.consoleId(),
            this.document.revision() + 1L,
            this.document.ownerId(),
            this.document.accessMode(),
            this.document.trustedPlayerIds(),
            this.document.displayName(),
            dimension,
            sourceKind,
            sourcePos.getX(),
            sourcePos.getY(),
            sourcePos.getZ(),
            this.document.hardRangeX(),
            this.document.hardRangeY(),
            this.document.hardRangeZ(),
            this.document.elements()
         );
         this.markDirtyAndSync();
      }
   }

   protected void saveAdditional(CompoundTag output, Provider registries) {
      super.saveAdditional(output, registries);
      output.putInt("SchemaVersion", this.loadedSchemaVersion);
      if (this.isDocumentReadOnly()) {
         if (this.preservedFutureDocument != null) {
            output.put("Document", this.preservedFutureDocument.copy());
         }
      } else {
         CompoundTag documentOutput = new CompoundTag();
         this.saveDocument(documentOutput);
         output.put("Document", documentOutput);
      }
   }

   private void saveDocument(CompoundTag output) {
      output.putString("ConsoleId", this.document.consoleId().toString());
      output.putLong("DocumentRevision", this.document.revision());
      if (this.document.ownerId() != null) {
         output.putString("OwnerId", this.document.ownerId().toString());
      }

      output.putString("AccessMode", this.document.accessMode().name());
      ListTag trustedPlayers = new ListTag();

      for (UUID playerId : this.document.trustedPlayerIds()) {
         CompoundTag child = new CompoundTag();
         child.putString("PlayerId", playerId.toString());
         trustedPlayers.add(child);
      }

      output.put("TrustedPlayers", trustedPlayers);
      output.putString("DisplayName", this.document.displayName());
      output.putDouble("HardRangeX", this.document.hardRangeX());
      output.putDouble("HardRangeY", this.document.hardRangeY());
      output.putDouble("HardRangeZ", this.document.hardRangeZ());
      if (this.document.hasSourceBinding()) {
         output.putString("SourceDimension", this.document.sourceDimension());
         if (this.document.sourceKind() != null) {
            output.putString("SourceKind", this.document.sourceKind().name());
         }

         output.putInt("SourceX", this.document.sourceX());
         output.putInt("SourceY", this.document.sourceY());
         output.putInt("SourceZ", this.document.sourceZ());
      }

      ListTag elements = new ListTag();

      for (ControlConsoleElement element : this.document.elements()) {
         CompoundTag child = new CompoundTag();
         child.putString("ElementId", element.elementId().toString());
         child.putBoolean("Locked", element.locked());
         child.putString("Type", element.type().name());
         child.putString("Name", element.name());
         child.putFloat("Distance", element.distance());
         child.putFloat("OffsetX", element.offsetX());
         child.putFloat("OffsetY", element.offsetY());
         child.putFloat("Height", element.height());
         child.putFloat("Aspect", element.aspect());
         child.putFloat("Yaw", element.yaw());
         child.putFloat("Pitch", element.pitch());
         child.putFloat("Roll", element.roll());
         child.putFloat("ScaleX", element.scaleX());
         child.putFloat("ScaleY", element.scaleY());
         child.putFloat("ScaleZ", element.scaleZ());
         child.putFloat("PivotX", element.pivotX());
         child.putFloat("PivotY", element.pivotY());
         child.putFloat("PivotZ", element.pivotZ());
         child.putFloat("SkewXByY", element.skewXByY());
         child.putFloat("SkewYByX", element.skewYByX());
         child.putFloat("Brightness", element.brightness());
         child.putString("ContentMode", element.contentMode());
         child.putString("Text", element.text());
         child.putBoolean("FollowLyrics", element.followLyrics());
         child.putBoolean("ShowTranslation", element.showTranslation());
         child.putFloat("TextScale", element.textScale());
         child.putInt("Color", element.color());
         child.putFloat("Volume", element.volume());
         child.putInt("ChannelIndex", element.channelIndex());
         child.putFloat("MaxDistance", element.maxDistance());
         child.putBoolean("AutoMixJoc", element.autoMixJoc());
         child.putInt("TranslationColor", element.translationColor());
         child.putInt("BackgroundColor", element.backgroundColor());
         child.putString("Alignment", element.alignment().name());
         child.putFloat("MaxWidth", element.maxWidth());
         child.putBoolean("Wrap", element.wrap());
         child.putBoolean("Enabled", element.enabled());
         elements.add(child);
      }

      output.put("Elements", elements);
   }

   protected void loadAdditional(CompoundTag input, Provider registries) {
      super.loadAdditional(input, registries);
      int schemaVersion = LinkHelper.getIntOr(input, "SchemaVersion", 1);
      this.loadedSchemaVersion = schemaVersion;
      if (schemaVersion > 7) {
         this.preservedFutureDocument = input.contains("Document", 10) ? input.getCompound("Document").copy() : null;
         this.document = ControlConsoleDocument.empty();
      } else {
         try {
            CompoundTag documentInput = schemaVersion >= 3 ? LinkHelper.childOrEmpty(input, "Document") : input;
            String sourceDimension = LinkHelper.getStringOr(documentInput, "SourceDimension", "");
            ControlConsoleDocument.SourceKind sourceKind = parseSourceKind(LinkHelper.getStringOr(documentInput, "SourceKind", ""));
            if (!sourceDimension.isBlank() && sourceKind == null && schemaVersion < 4) {
               sourceKind = this.inferLegacySourceKind(documentInput);
            }

            String dimension = this.level != null ? this.level.dimension().location().toString() : "unknown";
            List<ControlConsoleElement> elements = this.readElements(documentInput, dimension);
            ControlConsoleRangeMigration.Range migratedRange = ControlConsoleRangeMigration.migrate(
               schemaVersion,
               LinkHelper.getDoubleOr(documentInput, "HardRangeX", 64.0),
               LinkHelper.getDoubleOr(documentInput, "HardRangeY", 32.0),
               LinkHelper.getDoubleOr(documentInput, "HardRangeZ", 64.0)
            );
            this.document = new ControlConsoleDocument(
               7,
               parseUuid(LinkHelper.getStringOr(documentInput, "ConsoleId", "")) != null
                  ? parseUuid(LinkHelper.getStringOr(documentInput, "ConsoleId", ""))
                  : stableId("console", dimension + "|" + this.worldPosition.asLong()),
               LinkHelper.getLongOr(documentInput, "DocumentRevision", 0L),
               parseUuid(LinkHelper.getStringOr(documentInput, "OwnerId", "")),
               ControlConsoleDocument.AccessMode.parse(LinkHelper.getStringOr(documentInput, "AccessMode", "OWNER_ONLY")),
               readTrustedPlayers(documentInput),
               LinkHelper.getStringOr(documentInput, "DisplayName", "中控台"),
               sourceDimension.isBlank() ? null : sourceDimension,
               sourceKind,
               LinkHelper.getIntOr(documentInput, "SourceX", 0),
               LinkHelper.getIntOr(documentInput, "SourceY", 0),
               LinkHelper.getIntOr(documentInput, "SourceZ", 0),
               migratedRange.x(),
               migratedRange.y(),
               migratedRange.z(),
               elements
            );
            this.loadedSchemaVersion = 7;
            this.preservedFutureDocument = null;
         } catch (IllegalArgumentException var10) {
            this.document = ControlConsoleDocument.empty();
            this.loadedSchemaVersion = 7;
            this.preservedFutureDocument = null;
         }
      }
   }

   private List<ControlConsoleElement> readElements(CompoundTag input, String dimension) {
      List<ControlConsoleElement> elements = new ArrayList<>();
      int sourceIndex = 0;

      for (Tag childTag : LinkHelper.childrenListOrEmpty(input, "Elements")) {
         CompoundTag child = (CompoundTag)childTag;
         if (elements.size() >= 4096) {
            break;
         }

         int elementSourceIndex = sourceIndex++;

         try {
            ControlConsoleElement.Type type = ControlConsoleElement.Type.parse(LinkHelper.getStringOr(child, "Type", ""));
            String name = LinkHelper.getStringOr(child, "Name", "");
            ControlConsoleElement legacy = new ControlConsoleElement(
               type,
               name,
               LinkHelper.getFloatOr(child, "Distance", 0.0F),
               LinkHelper.getFloatOr(child, "OffsetX", 0.0F),
               LinkHelper.getFloatOr(child, "OffsetY", 0.0F),
               LinkHelper.getFloatOr(child, "Height", 1.0F),
               LinkHelper.getFloatOr(child, "Aspect", 1.0F),
               LinkHelper.getFloatOr(child, "Yaw", 0.0F),
               LinkHelper.getFloatOr(child, "Pitch", 0.0F),
               LinkHelper.getFloatOr(child, "Roll", 0.0F)
            );
            UUID elementId = parseUuid(LinkHelper.getStringOr(child, "ElementId", ""));
            if (elementId == null) {
               elementId = stableId("element", dimension + "|" + this.worldPosition.asLong() + "|" + elementSourceIndex);
            }

            elements.add(
               new ControlConsoleElement(
                  elementId,
                  type,
                  name,
                  LinkHelper.getFloatOr(child, "Distance", 0.0F),
                  LinkHelper.getFloatOr(child, "OffsetX", 0.0F),
                  LinkHelper.getFloatOr(child, "OffsetY", 0.0F),
                  LinkHelper.getFloatOr(child, "Height", 1.0F),
                  LinkHelper.getFloatOr(child, "Aspect", 1.0F),
                  LinkHelper.getFloatOr(child, "Yaw", 0.0F),
                  LinkHelper.getFloatOr(child, "Pitch", 0.0F),
                  LinkHelper.getFloatOr(child, "Roll", 0.0F),
                  LinkHelper.getStringOr(child, "ContentMode", legacy.contentMode()),
                  LinkHelper.getStringOr(child, "Text", ""),
                  LinkHelper.getBooleanOr(child, "FollowLyrics", legacy.followLyrics()),
                  LinkHelper.getBooleanOr(child, "ShowTranslation", legacy.showTranslation()),
                  LinkHelper.getFloatOr(child, "TextScale", legacy.textScale()),
                  LinkHelper.getIntOr(child, "Color", legacy.color()),
                  LinkHelper.getFloatOr(child, "Volume", legacy.volume()),
                  LinkHelper.getIntOr(child, "ChannelIndex", legacy.channelIndex()),
                  LinkHelper.getFloatOr(child, "MaxDistance", legacy.maxDistance()),
                  LinkHelper.getBooleanOr(child, "AutoMixJoc", legacy.autoMixJoc()),
                  LinkHelper.getIntOr(child, "TranslationColor", -4663041),
                  LinkHelper.getIntOr(child, "BackgroundColor", 1073741824),
                  parseAlignment(LinkHelper.getStringOr(child, "Alignment", "CENTER")),
                  LinkHelper.getFloatOr(child, "MaxWidth", 0.0F),
                  LinkHelper.getBooleanOr(child, "Wrap", false),
                  LinkHelper.getBooleanOr(child, "Enabled", legacy.enabled()),
                  LinkHelper.getBooleanOr(child, "Locked", false),
                  LinkHelper.getFloatOr(child, "ScaleX", 1.0F),
                  LinkHelper.getFloatOr(child, "ScaleY", 1.0F),
                  LinkHelper.getFloatOr(child, "ScaleZ", 1.0F),
                  LinkHelper.getFloatOr(child, "PivotX", 0.0F),
                  LinkHelper.getFloatOr(child, "PivotY", 0.0F),
                  LinkHelper.getFloatOr(child, "PivotZ", 0.0F),
                  LinkHelper.getFloatOr(child, "SkewXByY", 0.0F),
                  LinkHelper.getFloatOr(child, "SkewYByX", 0.0F),
                  LinkHelper.getFloatOr(child, "Brightness", 1.0F)
               )
            );
         } catch (IllegalArgumentException var13) {
         }
      }

      return List.copyOf(elements);
   }

   private static Set<UUID> readTrustedPlayers(CompoundTag input) {
      LinkedHashSet<UUID> players = new LinkedHashSet<>();

      for (Tag childTag : LinkHelper.childrenListOrEmpty(input, "TrustedPlayers")) {
         CompoundTag child = (CompoundTag)childTag;
         UUID playerId = parseUuid(LinkHelper.getStringOr(child, "PlayerId", ""));
         if (playerId != null) {
            players.add(playerId);
         }

         if (players.size() >= 256) {
            break;
         }
      }

      return Set.copyOf(players);
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

   private static ControlConsoleDocument.SourceKind parseSourceKind(String value) {
      if (value != null && !value.isBlank()) {
         try {
            return ControlConsoleDocument.SourceKind.valueOf(value);
         } catch (IllegalArgumentException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static ControlConsoleElement.Alignment parseAlignment(String value) {
      try {
         return ControlConsoleElement.Alignment.parse(value);
      } catch (NullPointerException | IllegalArgumentException var2) {
         return ControlConsoleElement.Alignment.CENTER;
      }
   }

   private ControlConsoleDocument.SourceKind inferLegacySourceKind(CompoundTag documentInput) {
      if (this.level != null) {
         BlockPos sourcePos = new BlockPos(
            LinkHelper.getIntOr(documentInput, "SourceX", 0),
            LinkHelper.getIntOr(documentInput, "SourceY", 0),
            LinkHelper.getIntOr(documentInput, "SourceZ", 0)
         );
         if (this.level.getBlockEntity(sourcePos) instanceof LiveStreamerBlockEntity) {
            return ControlConsoleDocument.SourceKind.LIVE_STREAMER;
         }
      }

      return ControlConsoleDocument.SourceKind.TURNTABLE;
   }

   private static UUID stableId(String namespace, String value) {
      return UUID.nameUUIDFromBytes((namespace + "|" + value).getBytes(StandardCharsets.UTF_8));
   }

   public static enum ReplaceResult {
      APPLIED,
      DUPLICATE,
      CONFLICT,
      READ_ONLY,
      REJECTED;
   }
}
