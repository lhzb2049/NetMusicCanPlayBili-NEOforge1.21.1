package com.zhongbai233.net_music_can_play_bili.media.codec;

public class Eac3JocDecoder {
   public Eac3JocDecoder.JocResult decode(byte[] payload, int qmfTimeslots) {
      Eac3BitReader bits = new Eac3BitReader(payload);
      Eac3JocDecoder.JocResult result = new Eac3JocDecoder.JocResult();
      Eac3JocDecoder.JocConfig cfg = result.config;
      cfg.downmixConfig = bits.read(3);
      if (cfg.downmixConfig > 4) {
         throw new IllegalArgumentException("unsupported joc_dmx_config_idx: " + cfg.downmixConfig);
      } else {
         cfg.channelCount = cfg.downmixConfig != 0 && cfg.downmixConfig != 3 ? 7 : 5;
         cfg.objectCount = bits.read(6) + 1;
         cfg.extConfig = bits.read(3);
         int gainPower = bits.read(3);
         cfg.gain = 1.0F + bits.read(5) / 32.0F * (float)Math.pow(2.0, gainPower - 4);
         cfg.sequence = bits.read(10);
         Eac3JocDecoder.JocObject[] objs = new Eac3JocDecoder.JocObject[cfg.objectCount];

         for (int i = 0; i < cfg.objectCount; i++) {
            Eac3JocDecoder.JocObject obj = new Eac3JocDecoder.JocObject();
            obj.active = bits.readBit();
            if (obj.active) {
               obj.bandsIndex = bits.read(3);
               obj.numBands = Eac3JocTables.NUM_BANDS[obj.bandsIndex];
               obj.sparse = bits.readBit();
               obj.quantizationTable = bits.read(1);
               obj.steepSlope = bits.readBit();
               obj.dataPoints = bits.read(1) + 1;
               if (obj.steepSlope) {
                  obj.timeslotOffsets = new int[obj.dataPoints];

                  for (int dp = 0; dp < obj.dataPoints; dp++) {
                     obj.timeslotOffsets[dp] = bits.read(5) + 1;
                  }
               }
            }

            objs[i] = obj;
         }

         result.objects = objs;
         int[][][][] raw = this.decodeData(bits, cfg, objs);
         result.mixMatrices = dequantize(cfg, objs, raw);
         result.interpolatedMatrices = interpolate(cfg, objs, result.mixMatrices, qmfTimeslots);
         return result;
      }
   }

   private static int huffmanDecode(int[][] table, Eac3BitReader bits) {
      int node = 0;

      do {
         int bit = bits.readBit() ? 1 : 0;
         node = table[node][bit];
      } while (node >= 0);

      return ~node;
   }

   private int[][][][] decodeData(Eac3BitReader bits, Eac3JocDecoder.JocConfig cfg, Eac3JocDecoder.JocObject[] objs) {
      int[][][][] raw = new int[cfg.objectCount][][][];

      for (int i = 0; i < cfg.objectCount; i++) {
         Eac3JocDecoder.JocObject obj = objs[i];
         if (!obj.active) {
            raw[i] = new int[0][][];
         } else {
            int[][][] objRaw = new int[obj.dataPoints][][];
            if (obj.sparse) {
               int[][] chanTable = cfg.channelCount == 7 ? Eac3JocTables.HUFF_7CH_IDX : Eac3JocTables.HUFF_5CH_IDX;
               int[][] vecTable = obj.quantizationTable == 1 ? Eac3JocTables.HUFF_FINE_SPARSE : Eac3JocTables.HUFF_COARSE_SPARSE;

               for (int dp = 0; dp < obj.dataPoints; dp++) {
                  int[] channels = new int[obj.numBands];
                  int[] vectors = new int[obj.numBands];
                  channels[0] = bits.read(3);

                  for (int pb = 1; pb < obj.numBands; pb++) {
                     channels[pb] = huffmanDecode(chanTable, bits);
                  }

                  for (int pb = 0; pb < obj.numBands; pb++) {
                     vectors[pb] = huffmanDecode(vecTable, bits);
                  }

                  int[][] dpMatrix = new int[cfg.channelCount][obj.numBands];
                  int offset = obj.quantizationTable * 50 + 50;
                  int maxVal = (obj.quantizationTable * 48 + 48) * 2;

                  for (int ch = 0; ch < cfg.channelCount; ch++) {
                     for (int pb = 0; pb < obj.numBands; pb++) {
                        int activeCh = pb == 0 ? channels[0] : (channels[pb - 1] + channels[pb]) % cfg.channelCount;
                        if (ch == activeCh) {
                           if (pb == 0) {
                              dpMatrix[ch][pb] = (offset + vectors[pb]) % maxVal;
                           } else {
                              dpMatrix[ch][pb] = (dpMatrix[ch][pb - 1] + vectors[pb]) % maxVal;
                           }
                        } else {
                           dpMatrix[ch][pb] = offset;
                        }
                     }
                  }

                  objRaw[dp] = dpMatrix;
               }
            } else {
               int[][] huffTable = obj.quantizationTable == 1 ? Eac3JocTables.HUFF_FINE : Eac3JocTables.HUFF_COARSE;

               for (int dp = 0; dp < obj.dataPoints; dp++) {
                  int[][] dpMatrix = new int[cfg.channelCount][obj.numBands];

                  for (int ch = 0; ch < cfg.channelCount; ch++) {
                     for (int pbx = 0; pbx < obj.numBands; pbx++) {
                        dpMatrix[ch][pbx] = huffmanDecode(huffTable, bits);
                     }
                  }

                  objRaw[dp] = dpMatrix;
               }
            }

            raw[i] = objRaw;
         }
      }

      return raw;
   }

   private static float[][][][] dequantize(Eac3JocDecoder.JocConfig cfg, Eac3JocDecoder.JocObject[] objs, int[][][][] raw) {
      float[][][][] result = new float[cfg.objectCount][][][];

      for (int i = 0; i < cfg.objectCount; i++) {
         Eac3JocDecoder.JocObject obj = objs[i];
         if (!obj.active) {
            result[i] = new float[0][][];
         } else {
            float[][][] objMix = new float[obj.dataPoints][][];
            int quantCenter = obj.quantizationTable * 48 + 48;
            float gainStep = 0.2F - obj.quantizationTable * 0.1F;
            float centerFloat = quantCenter * gainStep;
            float maxVal = centerFloat * 2.0F;

            for (int dp = 0; dp < obj.dataPoints; dp++) {
               int[][] dpRaw = raw[i][dp];
               float[][] dpMix = new float[cfg.channelCount][obj.numBands];

               for (int ch = 0; ch < cfg.channelCount; ch++) {
                  float prev = 0.0F;

                  for (int pb = 0; pb < obj.numBands; pb++) {
                     float val = pb == 0 ? (centerFloat + dpRaw[ch][pb] * gainStep) % maxVal : (prev + dpRaw[ch][pb] * gainStep) % maxVal;
                     prev = val;
                     dpMix[ch][pb] = (val - centerFloat) * gainStep;
                  }
               }

               objMix[dp] = dpMix;
            }

            result[i] = objMix;
         }
      }

      return result;
   }

   private static float[][][][] interpolate(Eac3JocDecoder.JocConfig cfg, Eac3JocDecoder.JocObject[] objs, float[][][][] mix, int qmfTs) {
      float[][][][] result = new float[cfg.objectCount][][][];

      for (int i = 0; i < cfg.objectCount; i++) {
         Eac3JocDecoder.JocObject obj = objs[i];
         if (!obj.active) {
            float[][][] objInterp = new float[qmfTs][cfg.channelCount][64];
            result[i] = objInterp;
         } else {
            float[][][] objInterp = new float[qmfTs][][];
            int[] pbMap = Eac3JocTables.SUBBAND_TO_BAND[obj.bandsIndex];

            for (int ts = 0; ts < qmfTs; ts++) {
               float[][] tsMatrix = new float[cfg.channelCount][64];

               for (int ch = 0; ch < cfg.channelCount; ch++) {
                  for (int sb = 0; sb < 64; sb++) {
                     int pb = pbMap[sb];
                     if (obj.dataPoints == 1) {
                        tsMatrix[ch][sb] = mix[i][0][ch][pb];
                     } else {
                        float lerp = (float)ts / qmfTs;
                        float from = mix[i][0][ch][pb];
                        float to = mix[i].length > 1 ? mix[i][1][ch][pb] : from;
                        tsMatrix[ch][sb] = from + (to - from) * lerp;
                     }
                  }
               }

               objInterp[ts] = tsMatrix;
            }

            result[i] = objInterp;
         }
      }

      return result;
   }

   public static class JocConfig {
      public int downmixConfig;
      public int channelCount;
      public int objectCount;
      public int extConfig;
      public float gain = 1.0F;
      public int sequence;
   }

   public static class JocObject {
      public boolean active;
      public int bandsIndex;
      public int numBands;
      public boolean sparse;
      public int quantizationTable;
      public boolean steepSlope;
      public int dataPoints;
      public int[] timeslotOffsets;
   }

   public static class JocResult {
      public Eac3JocDecoder.JocConfig config = new Eac3JocDecoder.JocConfig();
      public Eac3JocDecoder.JocObject[] objects;
      public float[][][][] mixMatrices;
      public float[][][][] interpolatedMatrices;
   }
}
