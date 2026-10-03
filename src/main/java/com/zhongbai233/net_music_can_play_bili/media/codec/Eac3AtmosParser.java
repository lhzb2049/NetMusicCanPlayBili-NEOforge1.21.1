package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.ArrayList;
import java.util.List;

public class Eac3AtmosParser {
   private static final int[][] STANDARD_BED_CHANNELS = new int[][]{{0, 1}, {2}, {3}, {4, 5}, {6, 7}, {8, 9}, {10, 11}, {12, 13}, {14, 15}, {16}};
   private static final int[] ISF_OBJECT_COUNT = new int[]{4, 8, 10, 14, 15, 30};
   private static final int[] RAMP_DURATION = new int[]{32, 64, 128, 256, 320, 480, 1000, 1001, 1024, 1600, 1601, 1602, 1920, 2000, 2002, 2048};
   private static final float[] DISTANCE_FACTORS = new float[]{
      1.1F, 1.3F, 1.6F, 2.0F, 2.5F, 3.2F, 4.0F, 5.0F, 6.3F, 7.9F, 10.0F, 12.6F, 15.8F, 20.0F, 25.1F, 50.1F
   };

   private Eac3AtmosParser() {
   }

   public static List<byte[]> findEmdfBlocks(byte[] frame) {
      List<byte[]> blocks = new ArrayList<>();
      int pos = 0;

      while (pos < frame.length - 4) {
         if ((frame[pos] & 255) == 88 && (frame[pos + 1] & 255) == 56) {
            int length = (frame[pos + 2] & 255) << 8 | frame[pos + 3] & 255;
            int total = length + 4;
            if (pos + total <= frame.length) {
               byte[] block = new byte[total];
               System.arraycopy(frame, pos, block, 0, total);
               blocks.add(block);
               pos += total;
               continue;
            }
         }

         pos++;
      }

      return blocks;
   }

   public static byte[] extractJocPayload(byte[] emdfBlock) {
      Eac3BitReader bits = new Eac3BitReader(emdfBlock);

      try {
         bits.skip(16);
         int length = bits.read(16);
         int blockEnd = bits.position() + length * 8;
         int version = bits.read(2);
         if (version == 3) {
            version += bits.variableBits(2);
         }

         int key = bits.read(3);
         if (key == 7) {
            key += bits.variableBits(3);
         }

         if (version != 0 || key != 0) {
            return null;
         }

         while (bits.position() < blockEnd) {
            int pid = bits.read(5);
            if (pid == 0) {
               break;
            }

            if (pid == 31) {
               pid += bits.variableBits(5);
            }

            boolean hasOffset = bits.readBit();
            if (hasOffset) {
               bits.skip(12);
            }

            if (bits.readBit()) {
               bits.variableBits(11);
            }

            if (bits.readBit()) {
               bits.variableBits(2);
            }

            if (bits.readBit()) {
               bits.skip(8);
            }

            if (!bits.readBit()) {
               boolean frameAligned = false;
               if (!hasOffset) {
                  frameAligned = bits.readBit();
                  if (frameAligned) {
                     bits.skip(2);
                  }
               }

               if (hasOffset || frameAligned) {
                  bits.skip(7);
               }
            }

            int payloadLen = bits.variableBits(8);
            int payloadStart = bits.position();
            int payloadEnd = payloadStart + payloadLen * 8;
            if (pid == 14) {
               return readBitsAsBytes(emdfBlock, payloadStart, payloadLen * 8);
            }

            bits.position(Math.min(payloadEnd, blockEnd));
         }
      } catch (IndexOutOfBoundsException var11) {
      }

      return null;
   }

   public static byte[] extractOamdPayload(byte[] emdfBlock) {
      Eac3BitReader bits = new Eac3BitReader(emdfBlock);

      try {
         bits.skip(16);
         int length = bits.read(16);
         int blockEnd = bits.position() + length * 8;
         int version = bits.read(2);
         if (version == 3) {
            version += bits.variableBits(2);
         }

         int key = bits.read(3);
         if (key == 7) {
            key += bits.variableBits(3);
         }

         if (version != 0 || key != 0) {
            return null;
         }

         while (bits.position() < blockEnd) {
            int pid = bits.read(5);
            if (pid == 0) {
               break;
            }

            if (pid == 31) {
               pid += bits.variableBits(5);
            }

            boolean hasOffset = bits.readBit();
            if (hasOffset) {
               bits.skip(12);
            }

            if (bits.readBit()) {
               bits.variableBits(11);
            }

            if (bits.readBit()) {
               bits.variableBits(2);
            }

            if (bits.readBit()) {
               bits.skip(8);
            }

            if (!bits.readBit()) {
               boolean frameAligned = false;
               if (!hasOffset) {
                  frameAligned = bits.readBit();
                  if (frameAligned) {
                     bits.skip(2);
                  }
               }

               if (hasOffset || frameAligned) {
                  bits.skip(7);
               }
            }

            int plen = bits.variableBits(8);
            int ps = bits.position();
            int pe = ps + plen * 8;
            if (pid == 11) {
               return readBitsAsBytes(emdfBlock, ps, plen * 8);
            }

            bits.position(Math.min(pe, blockEnd));
         }
      } catch (IndexOutOfBoundsException var11) {
      }

      return null;
   }

   private static byte[] readBitsAsBytes(byte[] data, int bitPos, int bitCount) {
      if (bitCount <= 0) {
         return new byte[0];
      } else {
         int byteLen = (bitCount + 7) / 8;
         byte[] result = new byte[byteLen];

         for (int i = 0; i < bitCount; i++) {
            int srcByte = data[bitPos + i >> 3] & 255;
            int bit = srcByte >> 7 - (bitPos + i & 7) & 1;
            int dstIdx = i >> 3;
            result[dstIdx] = (byte)(result[dstIdx] << 1 | bit);
         }

         int remaining = bitCount & 7;
         if (remaining != 0) {
            result[byteLen - 1] = (byte)(result[byteLen - 1] << 8 - remaining);
         }

         return result;
      }
   }

   public static Eac3AtmosParser.OamdConfig parseOamd(byte[] payload) {
      Eac3BitReader bits = new Eac3BitReader(payload);
      Eac3AtmosParser.OamdConfig cfg = new Eac3AtmosParser.OamdConfig();

      try {
         cfg.version = bits.read(2);
         if (cfg.version == 3) {
            cfg.version = cfg.version + bits.read(3);
         }

         cfg.objectCount = bits.read(5) + 1;
         if (cfg.objectCount == 32) {
            cfg.objectCount = cfg.objectCount + bits.read(7);
         }

         cfg.dynamicOnly = bits.readBit();
         cfg.beds = 0;
         cfg.isfObjects = 0;
         if (cfg.dynamicOnly) {
            if (bits.readBit()) {
               cfg.beds = 1;
            }
         } else {
            int contentDesc = bits.read(4);
            if ((contentDesc & 1) != 0) {
               bits.read(1);
               int bedInstances = bits.readBit() ? bits.read(3) + 2 : 1;

               for (int bi = 0; bi < bedInstances; bi++) {
                  if (bits.readBit()) {
                     cfg.beds++;
                  } else if (bits.readBit()) {
                     for (int s = 0; s < 10; s++) {
                        if (bits.readBit()) {
                           cfg.beds = cfg.beds + STANDARD_BED_CHANNELS[s].length;
                        }
                     }
                  } else {
                     for (int n = 0; n < 17; n++) {
                        if (bits.readBit()) {
                           cfg.beds++;
                        }
                     }
                  }
               }
            }

            if ((contentDesc & 2) != 0) {
               int isfIdx = bits.read(3);
               cfg.isfObjects = isfIdx < ISF_OBJECT_COUNT.length ? ISF_OBJECT_COUNT[isfIdx] : -1;
            }

            if ((contentDesc & 4) != 0 && bits.read(5) == 31) {
               bits.skip(7);
            }

            if ((contentDesc & 8) != 0) {
               bits.skip((bits.read(4) + 1) * 8);
            }
         }

         boolean altObjPresent = bits.readBit();
         cfg.elementCount = bits.read(4);
         if (cfg.elementCount == 15) {
            cfg.elementCount = cfg.elementCount + bits.read(5);
         }

         int bedOrIsf = cfg.beds + cfg.isfObjects;

         for (int e = 0; e < cfg.elementCount; e++) {
            int elIdx = bits.read(4);
            int elEnd = bits.position() + bitsReadLimited(bits, 4, 4) + 1;
            bits.skip(altObjPresent ? 5 : 1);
            if (elIdx == 1) {
               cfg.objectElement = parseObjectElement(bits, cfg.objectCount, bedOrIsf);
            }

            bits.position(Math.min(elEnd, bits.limit()));
         }
      } catch (IndexOutOfBoundsException var8) {
      }

      return cfg;
   }

   private static int bitsReadLimited(Eac3BitReader bits, int groupBits, int limit) {
      int value = 0;

      while (true) {
         value += bits.read(groupBits);
         if (limit == 0) {
            return value;
         }

         limit--;
         if (!bits.readBit()) {
            return value;
         }

         value = value + 1 << groupBits;
      }
   }

   private static Eac3AtmosParser.ObjectElement parseObjectElement(Eac3BitReader bits, int objCount, int bedOrIsf) {
      Eac3AtmosParser.ObjectElement el = new Eac3AtmosParser.ObjectElement();
      int mode = bits.read(2);
      int sampleOffset;
      if (mode == 0) {
         sampleOffset = 0;
      } else if (mode == 1) {
         sampleOffset = new int[]{8, 16, 18, 24}[bits.read(2)];
      } else {
         sampleOffset = bits.read(5);
      }

      el.blockCount = bits.read(3) + 1;
      el.offsets = new int[el.blockCount];
      el.ramps = new int[el.blockCount];

      for (int b = 0; b < el.blockCount; b++) {
         el.offsets[b] = bits.read(6) + sampleOffset;
         int rc = bits.read(2);
         if (rc == 3) {
            if (bits.readBit()) {
               el.ramps[b] = RAMP_DURATION[bits.read(4)];
            } else {
               el.ramps[b] = bits.read(11);
            }
         } else {
            el.ramps[b] = new int[]{0, 512, 1536}[rc];
         }
      }

      if (!bits.readBit()) {
         bits.skip(5);
      }

      List<Eac3AtmosParser.ObjectPosition> positions = new ArrayList<>();
      int valid = 0;
      int dynamicObjs = Math.max(0, objCount - bedOrIsf);

      for (int obj = 0; obj < objCount; obj++) {
         boolean isBedOrIsf = obj < bedOrIsf;

         for (int blk = 0; blk < el.blockCount; blk++) {
            boolean inactive = bits.readBit();
            int basicStatus = inactive ? 0 : (blk == 0 ? 1 : bits.read(2));
            if ((basicStatus & 1) != 0) {
               skipObjectBasicInfo(bits, basicStatus == 1);
            }

            int renderStatus = 0;
            if (!inactive && !isBedOrIsf) {
               renderStatus = blk == 0 ? 1 : bits.read(2);
            }

            if ((renderStatus & 1) != 0) {
               Eac3AtmosParser.ObjectPosition pos = parseObjectRenderInfo(bits, blk, renderStatus == 1);
               valid++;
               if (!isBedOrIsf) {
                  pos.objectIndex = obj - bedOrIsf;
                  pos.blockIndex = blk;
                  positions.add(pos);
               }
            }

            if (bits.readBit()) {
               bits.skip((bits.read(4) + 1) * 8);
            }
         }
      }

      el.dynamicObjects = dynamicObjs;
      el.validPositionUpdates = valid;
      el.firstPositions = positions.toArray(new Eac3AtmosParser.ObjectPosition[0]);
      return el;
   }

   private static void skipObjectBasicInfo(Eac3BitReader bits, boolean readAll) {
      int blocks = readAll ? 3 : bits.read(2);
      if ((blocks & 2) != 0 && bits.read(2) == 2) {
         bits.read(6);
      }

      if ((blocks & 1) != 0 && !bits.readBit()) {
         bits.skip(5);
      }
   }

   private static Eac3AtmosParser.ObjectPosition parseObjectRenderInfo(Eac3BitReader bits, int blk, boolean readAll) {
      Eac3AtmosParser.ObjectPosition pos = new Eac3AtmosParser.ObjectPosition();
      int blocks = readAll ? 15 : bits.read(4);
      if ((blocks & 1) != 0) {
         pos.differential = blk != 0 && bits.readBit();
         if (pos.differential) {
            pos.x = bits.readSigned(3) / 62.0F;
            pos.y = bits.readSigned(3) / 62.0F;
            pos.z = bits.readSigned(3) / 15.0F;
         } else {
            pos.x = Math.min(1.0F, bits.read(6) / 62.0F);
            pos.y = Math.min(1.0F, bits.read(6) / 62.0F);
            pos.z = ((bits.read(1) << 1) - 1) * bits.read(4) / 15.0F;
         }

         if (bits.readBit()) {
            if (bits.readBit()) {
               pos.distance = Float.POSITIVE_INFINITY;
            } else {
               pos.distance = DISTANCE_FACTORS[bits.read(4)];
            }
         }
      }

      if ((blocks & 2) != 0) {
         bits.skip(4);
      }

      if ((blocks & 4) != 0) {
         int sm = bits.read(2);
         if (sm == 1) {
            bits.skip(5);
         } else if (sm == 2) {
            bits.skip(15);
         }
      }

      if ((blocks & 8) != 0 && bits.readBit()) {
         bits.skip(5);
      }

      bits.skip(1);
      return pos;
   }

   public static class OamdConfig {
      public int version;
      public int objectCount;
      public boolean dynamicOnly;
      public int beds;
      public int isfObjects;
      public int elementCount;
      public Eac3AtmosParser.ObjectElement objectElement;
   }

   public static class ObjectElement {
      public int blockCount;
      public int[] offsets;
      public int[] ramps;
      public int dynamicObjects;
      public int validPositionUpdates;
      public Eac3AtmosParser.ObjectPosition[] firstPositions;
   }

   public static class ObjectPosition {
      public int objectIndex;
      public int blockIndex;
      public float x;
      public float y;
      public float z;
      public boolean differential;
      public float distance;
   }
}
