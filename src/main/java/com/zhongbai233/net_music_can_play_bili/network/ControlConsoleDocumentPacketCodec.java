package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleSnapshotBudget;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class ControlConsoleDocumentPacketCodec {
   private static final int MAX_ENCODED_BYTES = 73728;
   private static final int MAX_DIMENSION_LENGTH = 256;

   private ControlConsoleDocumentPacketCodec() {
   }

   static ControlConsoleDocument decode(RegistryFriendlyByteBuf buf) {
      int startIndex = buf.readerIndex();
      int schemaVersion = buf.readVarInt();
      UUID consoleId = buf.readUUID();
      long revision = buf.readLong();
      UUID ownerId = buf.readBoolean() ? buf.readUUID() : null;
      ControlConsoleDocument.AccessMode accessMode = ControlConsoleDocument.AccessMode.parse(buf.readUtf(16));
      int trustedCount = buf.readVarInt();
      if (trustedCount >= 0 && trustedCount <= 256) {
         Set<UUID> trustedPlayerIds = new LinkedHashSet<>();

         for (int i = 0; i < trustedCount; i++) {
            if (!trustedPlayerIds.add(buf.readUUID())) {
               throw new IllegalArgumentException("duplicate trusted player id");
            }
         }

         String displayName = buf.readUtf(64);
         String sourceDimension = null;
         ControlConsoleDocument.SourceKind sourceKind = null;
         int sourceX = 0;
         int sourceY = 0;
         int sourceZ = 0;
         if (buf.readBoolean()) {
            sourceDimension = buf.readUtf(256);
            sourceKind = parseSourceKind(buf.readUtf(24));
            sourceX = buf.readInt();
            sourceY = buf.readInt();
            sourceZ = buf.readInt();
         }

         double hardRangeX = buf.readDouble();
         double hardRangeY = buf.readDouble();
         double hardRangeZ = buf.readDouble();
         int elementCount = buf.readVarInt();
         if (elementCount >= 0 && elementCount <= 4096) {
            List<ControlConsoleElement> elements = new ArrayList<>(elementCount);
            Set<UUID> elementIds = new HashSet<>();

            for (int ix = 0; ix < elementCount; ix++) {
               ControlConsoleElement element = ControlConsoleConfigPacket.readElement(buf);
               if (!elementIds.add(element.elementId())) {
                  throw new IllegalArgumentException("duplicate elementId");
               }

               elements.add(element);
               requireWithinLimit(buf.readerIndex() - startIndex);
            }

            requireWithinLimit(buf.readerIndex() - startIndex);
            ControlConsoleSnapshotBudget.requireWithinLimit(displayName, elements);
            return new ControlConsoleDocument(
               schemaVersion,
               consoleId,
               revision,
               ownerId,
               accessMode,
               trustedPlayerIds,
               displayName,
               sourceDimension,
               sourceKind,
               sourceX,
               sourceY,
               sourceZ,
               hardRangeX,
               hardRangeY,
               hardRangeZ,
               List.copyOf(elements)
            );
         } else {
            throw new IllegalArgumentException("invalid control console element count: " + elementCount);
         }
      } else {
         throw new IllegalArgumentException("invalid trusted player count: " + trustedCount);
      }
   }

   static void encode(RegistryFriendlyByteBuf buf, ControlConsoleDocument document) {
      ControlConsoleSnapshotBudget.requireWithinLimit(document.displayName(), document.elements());
      int startIndex = buf.writerIndex();
      buf.writeVarInt(document.schemaVersion());
      buf.writeUUID(document.consoleId());
      buf.writeLong(document.revision());
      buf.writeBoolean(document.ownerId() != null);
      if (document.ownerId() != null) {
         buf.writeUUID(document.ownerId());
      }

      buf.writeUtf(document.accessMode().name(), 16);
      buf.writeVarInt(document.trustedPlayerIds().size());
      document.trustedPlayerIds().forEach(buf::writeUUID);
      buf.writeUtf(document.displayName(), 64);
      buf.writeBoolean(document.hasSourceBinding());
      if (document.hasSourceBinding()) {
         buf.writeUtf(document.sourceDimension(), 256);
         buf.writeUtf(document.sourceKind().name(), 24);
         buf.writeInt(document.sourceX());
         buf.writeInt(document.sourceY());
         buf.writeInt(document.sourceZ());
      }

      buf.writeDouble(document.hardRangeX());
      buf.writeDouble(document.hardRangeY());
      buf.writeDouble(document.hardRangeZ());
      buf.writeVarInt(document.elements().size());

      for (ControlConsoleElement element : document.elements()) {
         ControlConsoleConfigPacket.writeElement(buf, element);
         requireWithinLimit(buf.writerIndex() - startIndex);
      }

      requireWithinLimit(buf.writerIndex() - startIndex);
   }

   private static ControlConsoleDocument.SourceKind parseSourceKind(String value) {
      try {
         return ControlConsoleDocument.SourceKind.valueOf(value);
      } catch (IllegalArgumentException var2) {
         throw new IllegalArgumentException("unsupported control console source kind: " + value, var2);
      }
   }

   private static void requireWithinLimit(int encodedBytes) {
      if (encodedBytes > 73728) {
         throw new IllegalArgumentException("authoritative control console document exceeds transport limit");
      }
   }
}
