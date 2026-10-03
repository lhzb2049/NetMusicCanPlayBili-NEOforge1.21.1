package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PadMapCellDiskCodec {
   private PadMapCellDiskCodec() {
   }

   static void write(PadMapCellDiskCodec.Snapshot snapshot) {
      if (snapshot != null) {
         try {
            Files.createDirectories(snapshot.path().getParent());

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
               out.writeInt(PadMapDiskCacheFormat.CELLS.magic());
               out.writeInt(PadMapDiskCacheFormat.CELLS.version());
               out.writeInt(snapshot.entries().size());

               for (PadMapCellDiskCodec.Entry entry : snapshot.entries()) {
                  out.writeUTF(entry.dimension());
                  out.writeInt(entry.cellSize());
                  out.writeInt(entry.cellX());
                  out.writeInt(entry.cellZ());
                  out.writeByte(PadMapDiskCacheFormat.encodeTile(entry.kind()));
               }
            }
         } catch (IOException var6) {
         }
      }
   }

   static List<PadMapCellDiskCodec.Entry> read(Path path, int entryLimit) {
      if (!Files.isRegularFile(path)) {
         return null;
      } else {
         try {
            Object var16;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
               if (!PadMapDiskCacheFormat.CELLS.matches(in.readInt(), in.readInt())) {
                  return null;
               }

               int count = Math.max(0, Math.min(in.readInt(), Math.max(0, entryLimit)));
               List<PadMapCellDiskCodec.Entry> entries = new ArrayList<>(count);

               for (int i = 0; i < count; i++) {
                  String dimension = in.readUTF();
                  int cellSize = in.readInt();
                  int cellX = in.readInt();
                  int cellZ = in.readInt();
                  PadMapTileKind kind = PadMapDiskCacheFormat.decodeTile(in.readUnsignedByte());
                  if (kind == null) {
                     return null;
                  }

                  if (kind != PadMapTileKind.UNKNOWN) {
                     entries.add(new PadMapCellDiskCodec.Entry(dimension, cellSize, cellX, cellZ, kind));
                  }
               }

               var16 = entries;
            }

            return (List<PadMapCellDiskCodec.Entry>)var16;
         } catch (IOException var14) {
            return null;
         }
      }
   }

   record Entry(String dimension, int cellSize, int cellX, int cellZ, PadMapTileKind kind) {
   }

   record Snapshot(Path path, List<PadMapCellDiskCodec.Entry> entries) {
   }
}
