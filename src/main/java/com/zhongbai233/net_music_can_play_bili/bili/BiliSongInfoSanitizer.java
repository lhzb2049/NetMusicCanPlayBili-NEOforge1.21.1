package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

public final class BiliSongInfoSanitizer {
   private BiliSongInfoSanitizer() {
   }

   public static SongInfo sanitize(SongInfo song) {
      if (song != null && song.songUrl != null && !song.songUrl.isBlank()) {
         BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenient(song.songUrl);
         if (selection == null) {
            return song;
         } else {
            String stored = BiliApiClient.formatStoredVideoSelection(selection.videoId(), selection.page());
            if (stored.equals(song.songUrl)) {
               return song;
            } else {
               SongInfo normalized = new SongInfo(stored, song.songName == null ? "" : song.songName, Math.max(0, song.songTime), song.vip);
               normalized.readOnly = song.readOnly;
               normalized.artists = song.artists;
               return normalized;
            }
         }
      } else {
         return song;
      }
   }

   public static ItemStack sanitizeDisc(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         SongInfo song = ItemMusicCD.getSongInfo(stack);
         SongInfo sanitized = sanitize(song);
         return sanitized == song ? stack : ItemMusicCD.setSongInfo(Objects.requireNonNull(sanitized), Objects.requireNonNull(stack.copyWithCount(1)));
      } else {
         return stack == null ? ItemStack.EMPTY : stack;
      }
   }

   public static boolean isForbiddenBiliDirectUrl(String raw) {
      if (raw != null && !raw.isBlank() && BiliApiClient.extractVideoSelectionLenient(raw) == null) {
         try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
               String lower = host.toLowerCase(Locale.ROOT);
               return lower.endsWith("bilivideo.com")
                  || lower.endsWith("hdslb.com")
                  || lower.endsWith("bilibili.com")
                  || lower.endsWith("biliapi.net")
                  || lower.endsWith("biliapi.com");
            } else {
               return false;
            }
         } catch (IllegalArgumentException var4) {
            String lower = raw.toLowerCase(Locale.ROOT);
            return lower.contains("bilivideo.com") || lower.contains("hdslb.com") || lower.contains("bilibili.com/video/");
         }
      } else {
         return false;
      }
   }
}
