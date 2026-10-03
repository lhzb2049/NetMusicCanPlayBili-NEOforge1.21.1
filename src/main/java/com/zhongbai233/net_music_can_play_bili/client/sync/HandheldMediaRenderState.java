package com.zhongbai233.net_music_can_play_bili.client.sync;

public record HandheldMediaRenderState(boolean videoDecodeEnabled, int videoQualityCeiling, boolean allowAiSubtitle) {
   public static final HandheldMediaRenderState DISABLED = new HandheldMediaRenderState(false, 0, false);
}
