package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

public final class PlaybackSync {
   private static final String SESSION_KEY = "nmb_session=";
   private static final String SOURCE_KEY = "nmb_source=";
   private static final String ELAPSED_MS_KEY = "nmb_elapsed_ms=";
   private static final String TOTAL_MS_KEY = "nmb_total_ms=";
   private static final String REQUEST_KEY = "nmb_request=";
   private static final String MINECART_ENTITY_KEY = "nmb_minecart_entity=";
   private static final String MINECART_UUID_KEY = "nmb_minecart_uuid=";

   private PlaybackSync() {
   }

   public static String withSync(String value, String sessionId, long elapsedMillis) {
      return withSync(value, sessionId, elapsedMillis, 0L);
   }

   public static String withSync(String value, PlaybackSessionId sessionId, long elapsedMillis) {
      return withSync(value, sessionId, elapsedMillis, 0L);
   }

   public static String withSync(String value, String sessionId, long elapsedMillis, long totalMillis) {
      return PlaybackSessionId.parse(sessionId).map(parsed -> withSync(value, parsed, elapsedMillis, totalMillis)).orElse(value);
   }

   public static String withSync(String value, PlaybackSessionId sessionId, long elapsedMillis, long totalMillis) {
      if (value != null && !value.isBlank() && sessionId != null) {
         String clean = strip(value);
         long elapsed = Math.max(0L, elapsedMillis);
         long total = Math.max(0L, totalMillis);
         String sync = clean + "#nmb_session=" + sessionId.value() + "&nmb_elapsed_ms=" + elapsed;
         return total > 0L ? sync + "&nmb_total_ms=" + total : sync;
      } else {
         return value;
      }
   }

   public static String transferSync(String source, String target) {
      PlaybackSync.Metadata sync = parse(source);
      if (!sync.hasSession()) {
         return target;
      } else {
         String result = withSync(target, sync.sessionId(), sync.elapsedMillis(), sync.totalMillis());
         PlaybackSourceId sourceId = parsePlaybackSourceId(source).orElse(null);
         if (sourceId != null) {
            result = withSourceId(result, sourceId);
         }

         PlaybackSync.MinecartAnchor anchor = parseMinecartAnchor(source);
         return anchor != null ? withMinecartAnchor(result, anchor.entityId(), anchor.entityUuid()) : result;
      }
   }

   public static String withSourceId(String value, PlaybackSourceId sourceId) {
      if (value != null && !value.isBlank() && sourceId != null) {
         String separator = value.indexOf(35) >= 0 ? "&" : "#";
         return value + separator + "nmb_source=" + sourceId;
      } else {
         return value;
      }
   }

   public static Optional<PlaybackSourceId> parsePlaybackSourceId(String value) {
      if (value == null) {
         return Optional.empty();
      } else {
         int hash = value.indexOf(35);
         if (hash >= 0 && hash != value.length() - 1) {
            for (String part : value.substring(hash + 1).split("&")) {
               if (part.startsWith("nmb_source=")) {
                  return PlaybackSourceId.parse(part.substring("nmb_source=".length()));
               }
            }

            return Optional.empty();
         } else {
            return Optional.empty();
         }
      }
   }

   public static String withMinecartAnchor(String value, int entityId, UUID entityUuid) {
      if (value != null && !value.isBlank() && entityId >= 0 && entityUuid != null) {
         String separator = value.indexOf(35) >= 0 ? "&" : "#";
         return value + separator + "nmb_minecart_entity=" + entityId + "&nmb_minecart_uuid=" + entityUuid;
      } else {
         return value;
      }
   }

   public static String withRequestToken(String value, String requestToken) {
      return MediaRequestToken.parse(requestToken).map(token -> withRequestToken(value, token)).orElse(value);
   }

   public static String withRequestToken(String value, MediaRequestToken requestToken) {
      if (value != null && !value.isBlank() && requestToken != null) {
         String separator = value.indexOf(35) >= 0 ? "&" : "#";
         return value + separator + "nmb_request=" + requestToken.value();
      } else {
         return value;
      }
   }

   public static String parseRequestToken(String value) {
      return parseMediaRequestToken(value).map(token -> token.value()).orElse("");
   }

   public static Optional<MediaRequestToken> parseMediaRequestToken(String value) {
      if (value == null) {
         return Optional.empty();
      } else {
         int hash = value.indexOf(35);
         if (hash >= 0 && hash != value.length() - 1) {
            for (String part : value.substring(hash + 1).split("&")) {
               if (part.startsWith("nmb_request=")) {
                  return MediaRequestToken.parse(part.substring("nmb_request=".length()));
               }
            }

            return Optional.empty();
         } else {
            return Optional.empty();
         }
      }
   }

   public static Optional<PlaybackSessionId> parsePlaybackSessionId(String value) {
      if (value == null) {
         return Optional.empty();
      } else {
         int hash = value.indexOf(35);
         if (hash >= 0 && hash != value.length() - 1) {
            for (String part : value.substring(hash + 1).split("&")) {
               if (part.startsWith("nmb_session=")) {
                  return PlaybackSessionId.parse(part.substring("nmb_session=".length()));
               }
            }

            return Optional.empty();
         } else {
            return Optional.empty();
         }
      }
   }

   public static PlaybackSync.MinecartAnchor parseMinecartAnchor(String value) {
      if (value == null) {
         return null;
      } else {
         int hash = value.indexOf(35);
         if (hash >= 0 && hash != value.length() - 1) {
            int entityId = -1;
            UUID entityUuid = null;

            for (String part : value.substring(hash + 1).split("&")) {
               if (part.startsWith("nmb_minecart_entity=")) {
                  try {
                     entityId = Integer.parseInt(part.substring("nmb_minecart_entity=".length()));
                  } catch (NumberFormatException var10) {
                     entityId = -1;
                  }
               } else if (part.startsWith("nmb_minecart_uuid=")) {
                  try {
                     entityUuid = UUID.fromString(part.substring("nmb_minecart_uuid=".length()));
                  } catch (IllegalArgumentException var9) {
                     entityUuid = null;
                  }
               }
            }

            return entityId >= 0 && entityUuid != null ? new PlaybackSync.MinecartAnchor(entityId, entityUuid) : null;
         } else {
            return null;
         }
      }
   }

   public static PlaybackSync.Metadata parse(String value) {
      if (value == null) {
         return PlaybackSync.Metadata.empty();
      } else {
         int hash = value.indexOf(35);
         if (hash >= 0 && hash != value.length() - 1) {
            PlaybackSessionId sessionId = null;
            long elapsedMillis = 0L;
            long totalMillis = 0L;

            for (String part : value.substring(hash + 1).split("&")) {
               if (part.startsWith("nmb_session=")) {
                  sessionId = PlaybackSessionId.parse(part.substring("nmb_session=".length())).orElse(null);
               } else if (part.startsWith("nmb_elapsed_ms=")) {
                  try {
                     elapsedMillis = Math.max(0L, Long.parseLong(part.substring("nmb_elapsed_ms=".length())));
                  } catch (NumberFormatException var13) {
                     elapsedMillis = 0L;
                  }
               } else if (part.startsWith("nmb_total_ms=")) {
                  try {
                     totalMillis = Math.max(0L, Long.parseLong(part.substring("nmb_total_ms=".length())));
                  } catch (NumberFormatException var12) {
                     totalMillis = 0L;
                  }
               }
            }

            return sessionId == null ? PlaybackSync.Metadata.empty() : new PlaybackSync.Metadata(sessionId.value(), elapsedMillis, totalMillis);
         } else {
            return PlaybackSync.Metadata.empty();
         }
      }
   }

   public static String strip(String value) {
      if (value == null) {
         return null;
      } else {
         int hash = value.indexOf(35);
         if (hash < 0) {
            return value;
         } else {
            String fragment = value.substring(hash + 1);
            return !fragment.contains("nmb_session=")
                  && !fragment.contains("nmb_source=")
                  && !fragment.contains("nmb_elapsed_ms=")
                  && !fragment.contains("nmb_total_ms=")
                  && !fragment.contains("nmb_minecart_entity=")
                  && !fragment.contains("nmb_minecart_uuid=")
                  && !fragment.contains("nmb_request=")
               ? value
               : value.substring(0, hash);
         }
      }
   }

   public static URL strip(URL url) throws MalformedURLException {
      String original = url.toString();
      String clean = strip(original);
      return original.equals(clean) ? url : URI.create(clean).toURL();
   }

   public record Metadata(String sessionId, long elapsedMillis, long totalMillis) {
      public Metadata(String sessionId, long elapsedMillis, long totalMillis) {
         sessionId = PlaybackSessionId.parse(sessionId).map(parsed -> parsed.value()).orElse("");
         this.sessionId = sessionId;
         this.elapsedMillis = elapsedMillis;
         this.totalMillis = totalMillis;
      }

      static PlaybackSync.Metadata empty() {
         return new PlaybackSync.Metadata("", 0L, 0L);
      }

      public boolean hasSession() {
         return !this.sessionId.isBlank();
      }

      public Optional<PlaybackSessionId> playbackSessionId() {
         return PlaybackSessionId.parse(this.sessionId);
      }

      public int elapsedSeconds() {
         return (int)Math.min(2147483647L, this.elapsedMillis / 1000L);
      }
   }

   public record MinecartAnchor(int entityId, UUID entityUuid) {
   }
}
