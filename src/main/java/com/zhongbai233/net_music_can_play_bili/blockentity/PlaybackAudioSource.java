package com.zhongbai233.net_music_can_play_bili.blockentity;

public interface PlaybackAudioSource {
   boolean isPlaying();

   float getVolume();

   long getPlaybackElapsedMillis();
}
