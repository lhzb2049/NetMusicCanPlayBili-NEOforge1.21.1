package com.zhongbai233.net_music_can_play_bili.media.codec;

public class Eac3BitReader {
   private final byte[] data;
   private int pos;
   private final int limit;

   public Eac3BitReader(byte[] data) {
      this.data = data;
      this.pos = 0;
      this.limit = data.length * 8;
   }

   public int read(int count) {
      int value = 0;

      for (int i = 0; i < count; i++) {
         if (this.pos >= this.limit) {
            throw new IndexOutOfBoundsException("EOF at bit " + this.pos + "/" + this.limit);
         }

         int byteVal = this.data[this.pos >> 3] & 255;
         int bit = byteVal >> 7 - (this.pos & 7) & 1;
         value = value << 1 | bit;
         this.pos++;
      }

      return value;
   }

   public boolean readBit() {
      return this.read(1) != 0;
   }

   public void skip(int count) {
      if (this.pos + count > this.limit) {
         throw new IndexOutOfBoundsException("Skip past EOF: " + this.pos + "+" + count + " > " + this.limit);
      } else {
         this.pos += count;
      }
   }

   public int variableBits(int bits) {
      int value = 0;

      while (true) {
         value += this.read(bits);
         if (!this.readBit()) {
            return value;
         }

         value = value + 1 << bits;
      }
   }

   public int position() {
      return this.pos;
   }

   public void position(int newPos) {
      if (newPos >= 0 && newPos <= this.limit) {
         this.pos = newPos;
      } else {
         throw new IndexOutOfBoundsException("position out of range: " + newPos);
      }
   }

   public int limit() {
      return this.limit;
   }

   public int remaining() {
      return this.limit - this.pos;
   }

   public int readSigned(int count) {
      int value = this.read(count);
      int signBit = 1 << count - 1;
      if ((value & signBit) != 0) {
         value -= 1 << count;
      }

      return value;
   }
}
