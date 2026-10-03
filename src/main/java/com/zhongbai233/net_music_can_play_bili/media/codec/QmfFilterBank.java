package com.zhongbai233.net_music_can_play_bili.media.codec;

public class QmfFilterBank {
   public static final int SUBBANDS = 64;
   public static final int INPUT_SIZE = 256;
   public static final int TIMESLOTS = 4;
   private static final float INV_SQRT_SUBBANDS = 1.0F / (float)Math.sqrt(64.0);

   public static float[][][] forward(float[] input) {
      float[][][] result = new float[4][64][2];

      for (int ts = 0; ts < 4; ts++) {
         for (int sb = 0; sb < 64; sb++) {
            float real = 0.0F;
            float imag = 0.0F;

            for (int n = 0; n < 64; n++) {
               int idx = ts * 64 + n;
               if (idx < 256) {
                  float x = input[idx];
                  double angle = 0.02454369260617026 * (2 * sb + 1) * (2 * n - 63);
                  real += (float)(x * Math.cos(angle));
                  imag += (float)(x * Math.sin(angle));
               }
            }

            result[ts][sb][0] = real * INV_SQRT_SUBBANDS;
            result[ts][sb][1] = imag * INV_SQRT_SUBBANDS;
         }
      }

      return result;
   }

   public static float[] inverse(float[][][] input) {
      float[] output = new float[256];

      for (int ts = 0; ts < 4 && ts < input.length; ts++) {
         for (int n = 0; n < 64; n++) {
            float sample = 0.0F;

            for (int sb = 0; sb < 64; sb++) {
               double angle = 0.02454369260617026 * (2 * sb + 1) * (2 * n - 63);
               float real = input[ts][sb][0];
               float imag = input[ts][sb][1];
               sample += (float)(real * Math.cos(angle) + imag * Math.sin(angle));
            }

            output[ts * 64 + n] = sample * INV_SQRT_SUBBANDS;
         }
      }

      return output;
   }

   public static float[][][][] applyMixingMatrix(float[][][][] downmixQmf, float[][][][] mixMatrix, int numChannels, int numObjects) {
      float[][][][] result = new float[numObjects][4][64][2];

      for (int ts = 0; ts < 4; ts++) {
         for (int sb = 0; sb < 64; sb++) {
            for (int obj = 0; obj < numObjects; obj++) {
               float realSum = 0.0F;
               float imagSum = 0.0F;
               int channels = Math.min(numChannels, Math.min(downmixQmf.length, mixMatrix[obj][ts].length));

               for (int ch = 0; ch < channels; ch++) {
                  float coeff = mixMatrix[obj][ts][ch][sb];
                  realSum += downmixQmf[ch][ts][sb][0] * coeff;
                  imagSum += downmixQmf[ch][ts][sb][1] * coeff;
               }

               result[obj][ts][sb][0] = realSum;
               result[obj][ts][sb][1] = imagSum;
            }
         }
      }

      return result;
   }

   public static float[][][][] forwardMulti(float[][] pcm) {
      int nch = pcm.length;
      float[][][][] result = new float[nch][][][];

      for (int ch = 0; ch < nch; ch++) {
         result[ch] = forward(pcm[ch]);
      }

      return result;
   }

   public static float[][] inverseMulti(float[][][][] objectQmf) {
      int nobj = objectQmf.length;
      float[][] result = new float[nobj][];

      for (int obj = 0; obj < nobj; obj++) {
         result[obj] = inverse(objectQmf[obj]);
      }

      return result;
   }
}
