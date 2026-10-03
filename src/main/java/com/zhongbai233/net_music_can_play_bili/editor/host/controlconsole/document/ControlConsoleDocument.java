package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ControlConsoleDocument(
   int schemaVersion,
   UUID consoleId,
   long revision,
   UUID ownerId,
   ControlConsoleDocument.AccessMode accessMode,
   Set<UUID> trustedPlayerIds,
   String displayName,
   String sourceDimension,
   ControlConsoleDocument.SourceKind sourceKind,
   int sourceX,
   int sourceY,
   int sourceZ,
   double hardRangeX,
   double hardRangeY,
   double hardRangeZ,
   List<ControlConsoleElement> elements
) {
   public static final int CURRENT_SCHEMA_VERSION = 7;
   public static final double DEFAULT_HARD_RANGE_X = 64.0;
   public static final double DEFAULT_HARD_RANGE_Y = 32.0;
   public static final double DEFAULT_HARD_RANGE_Z = 64.0;
   public static final int MAX_ELEMENTS = 4096;
   public static final int MAX_TRUSTED_PLAYERS = 256;

   public ControlConsoleDocument(
      int schemaVersion,
      UUID consoleId,
      long revision,
      UUID ownerId,
      ControlConsoleDocument.AccessMode accessMode,
      Set<UUID> trustedPlayerIds,
      String displayName,
      String sourceDimension,
      ControlConsoleDocument.SourceKind sourceKind,
      int sourceX,
      int sourceY,
      int sourceZ,
      double hardRangeX,
      double hardRangeY,
      double hardRangeZ,
      List<ControlConsoleElement> elements
   ) {
      if (schemaVersion <= 0 || schemaVersion > 7) {
         throw new IllegalArgumentException("unsupported document schema version: " + schemaVersion);
      } else if (revision < 0L) {
         throw new IllegalArgumentException("revision must not be negative");
      } else {
         consoleId = Objects.requireNonNull(consoleId, "consoleId");
         if (sourceDimension == null) {
            sourceKind = null;
         } else if (sourceKind == null) {
            throw new IllegalArgumentException("sourceKind is required when sourceDimension is present");
         }

         accessMode = Objects.requireNonNull(accessMode, "accessMode");
         Set<UUID> suppliedTrustedPlayerIds = Objects.requireNonNull(trustedPlayerIds, "trustedPlayerIds");
         if (suppliedTrustedPlayerIds.size() <= 256 && !suppliedTrustedPlayerIds.stream().anyMatch(Objects::isNull)) {
            trustedPlayerIds = Set.copyOf(suppliedTrustedPlayerIds);
            displayName = Objects.requireNonNull(displayName, "displayName").trim();
            if (!displayName.isEmpty() && displayName.length() <= 64) {
               if (sourceDimension != null && sourceDimension.isBlank()) {
                  throw new IllegalArgumentException("sourceDimension must be non-blank when present");
               } else {
                  validateRange(hardRangeX, "hardRangeX");
                  validateRange(hardRangeY, "hardRangeY");
                  validateRange(hardRangeZ, "hardRangeZ");
                  elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
                  if (elements.size() > 4096) {
                     throw new IllegalArgumentException("elements exceed the transport safety limit");
                  } else if (elements.stream().anyMatch(Objects::isNull)) {
                     throw new IllegalArgumentException("elements must not contain null");
                  } else {
                     HashSet<UUID> elementIds = new HashSet<>();

                     for (ControlConsoleElement element : elements) {
                        if (!elementIds.add(element.elementId())) {
                           throw new IllegalArgumentException("elementId must be unique");
                        }
                     }

                     this.schemaVersion = schemaVersion;
                     this.consoleId = consoleId;
                     this.revision = revision;
                     this.ownerId = ownerId;
                     this.accessMode = accessMode;
                     this.trustedPlayerIds = trustedPlayerIds;
                     this.displayName = displayName;
                     this.sourceDimension = sourceDimension;
                     this.sourceKind = sourceKind;
                     this.sourceX = sourceX;
                     this.sourceY = sourceY;
                     this.sourceZ = sourceZ;
                     this.hardRangeX = hardRangeX;
                     this.hardRangeY = hardRangeY;
                     this.hardRangeZ = hardRangeZ;
                     this.elements = elements;
                  }
               }
            } else {
               throw new IllegalArgumentException("displayName must contain 1-64 characters");
            }
         } else {
            throw new IllegalArgumentException("trustedPlayerIds must contain at most 256 non-null UUIDs");
         }
      }
   }

   public ControlConsoleDocument(
      int schemaVersion,
      long revision,
      String displayName,
      String sourceDimension,
      int sourceX,
      int sourceY,
      int sourceZ,
      double hardRangeX,
      double hardRangeY,
      double hardRangeZ
   ) {
      this(
         schemaVersion,
         UUID.randomUUID(),
         revision,
         null,
         ControlConsoleDocument.AccessMode.OWNER_ONLY,
         Set.of(),
         displayName,
         sourceDimension,
         sourceDimension == null ? null : ControlConsoleDocument.SourceKind.TURNTABLE,
         sourceX,
         sourceY,
         sourceZ,
         hardRangeX,
         hardRangeY,
         hardRangeZ,
         List.of()
      );
   }

   public ControlConsoleDocument(
      int schemaVersion,
      long revision,
      String displayName,
      String sourceDimension,
      int sourceX,
      int sourceY,
      int sourceZ,
      double hardRangeX,
      double hardRangeY,
      double hardRangeZ,
      List<ControlConsoleElement> elements
   ) {
      this(
         schemaVersion,
         UUID.randomUUID(),
         revision,
         null,
         ControlConsoleDocument.AccessMode.OWNER_ONLY,
         Set.of(),
         displayName,
         sourceDimension,
         sourceDimension == null ? null : ControlConsoleDocument.SourceKind.TURNTABLE,
         sourceX,
         sourceY,
         sourceZ,
         hardRangeX,
         hardRangeY,
         hardRangeZ,
         elements
      );
   }

   public ControlConsoleDocument(
      int schemaVersion,
      long revision,
      UUID ownerId,
      ControlConsoleDocument.AccessMode accessMode,
      Set<UUID> trustedPlayerIds,
      String displayName,
      String sourceDimension,
      int sourceX,
      int sourceY,
      int sourceZ,
      double hardRangeX,
      double hardRangeY,
      double hardRangeZ,
      List<ControlConsoleElement> elements
   ) {
      this(
         schemaVersion,
         UUID.randomUUID(),
         revision,
         ownerId,
         accessMode,
         trustedPlayerIds,
         displayName,
         sourceDimension,
         sourceDimension == null ? null : ControlConsoleDocument.SourceKind.TURNTABLE,
         sourceX,
         sourceY,
         sourceZ,
         hardRangeX,
         hardRangeY,
         hardRangeZ,
         elements
      );
   }

   public static ControlConsoleDocument empty() {
      return new ControlConsoleDocument(
         7,
         UUID.randomUUID(),
         0L,
         null,
         ControlConsoleDocument.AccessMode.OWNER_ONLY,
         Set.of(),
         "中控台",
         null,
         null,
         0,
         0,
         0,
         64.0,
         32.0,
         64.0,
         List.of(ControlConsoleElement.defaultScreen())
      );
   }

   public ControlConsoleDocument withInitialScreenIfPristine() {
      return this.revision == 0L && this.elements.isEmpty()
         ? new ControlConsoleDocument(
            this.schemaVersion,
            this.consoleId,
            this.revision,
            this.ownerId,
            this.accessMode,
            this.trustedPlayerIds,
            this.displayName,
            this.sourceDimension,
            this.sourceKind,
            this.sourceX,
            this.sourceY,
            this.sourceZ,
            this.hardRangeX,
            this.hardRangeY,
            this.hardRangeZ,
            List.of(ControlConsoleElement.defaultScreen())
         )
         : this;
   }

   public boolean hasSourceBinding() {
      return this.sourceDimension != null;
   }

   public ControlConsoleDocument withRevision(long newRevision) {
      return new ControlConsoleDocument(
         this.schemaVersion,
         this.consoleId,
         newRevision,
         this.ownerId,
         this.accessMode,
         this.trustedPlayerIds,
         this.displayName,
         this.sourceDimension,
         this.sourceKind,
         this.sourceX,
         this.sourceY,
         this.sourceZ,
         this.hardRangeX,
         this.hardRangeY,
         this.hardRangeZ,
         this.elements
      );
   }

   public boolean canEdit(UUID playerId, boolean administrator) {
      if (administrator) {
         return true;
      } else {
         return playerId != null && this.ownerId != null
            ? this.ownerId.equals(playerId)
               || this.accessMode == ControlConsoleDocument.AccessMode.PUBLIC_EDIT
               || this.accessMode == ControlConsoleDocument.AccessMode.TRUSTED && this.trustedPlayerIds.contains(playerId)
            : false;
      }
   }

   public ControlConsoleDocument withOwnerIfAbsent(UUID playerId) {
      Objects.requireNonNull(playerId, "playerId");
      return this.ownerId != null
         ? this
         : new ControlConsoleDocument(
            this.schemaVersion,
            this.consoleId,
            this.revision,
            playerId,
            this.accessMode,
            this.trustedPlayerIds,
            this.displayName,
            this.sourceDimension,
            this.sourceKind,
            this.sourceX,
            this.sourceY,
            this.sourceZ,
            this.hardRangeX,
            this.hardRangeY,
            this.hardRangeZ,
            this.elements
         );
   }

   public ControlConsoleDocument withAccessControl(ControlConsoleDocument.AccessMode newAccessMode, Set<UUID> newTrustedPlayerIds) {
      return new ControlConsoleDocument(
         this.schemaVersion,
         this.consoleId,
         this.revision + 1L,
         this.ownerId,
         newAccessMode,
         newTrustedPlayerIds,
         this.displayName,
         this.sourceDimension,
         this.sourceKind,
         this.sourceX,
         this.sourceY,
         this.sourceZ,
         this.hardRangeX,
         this.hardRangeY,
         this.hardRangeZ,
         this.elements
      );
   }

   private static void validateRange(double value, String name) {
      if (!Double.isFinite(value) || value <= 0.0) {
         throw new IllegalArgumentException(name + " must be finite and positive");
      }
   }

   public static enum AccessMode {
      OWNER_ONLY,
      TRUSTED,
      PUBLIC_EDIT;

      public static ControlConsoleDocument.AccessMode parse(String value) {
         try {
            return valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var2) {
            throw new IllegalArgumentException("unsupported control console access mode: " + value, var2);
         }
      }
   }

   public static enum SourceKind {
      TURNTABLE,
      LIVE_STREAMER;
   }
}
