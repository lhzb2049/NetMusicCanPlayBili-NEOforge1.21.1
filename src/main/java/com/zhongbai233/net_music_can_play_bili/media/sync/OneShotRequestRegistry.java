package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class OneShotRequestRegistry<T> {
   private final ConcurrentHashMap<MediaRequestToken, OneShotRequestRegistry.Entry<T>> entries = new ConcurrentHashMap<>();
   private final LongSupplier clock;
   private final Supplier<MediaRequestToken> tokenFactory;

   public OneShotRequestRegistry() {
      this(System::currentTimeMillis, MediaRequestToken::random);
   }

   OneShotRequestRegistry(LongSupplier clock, Supplier<MediaRequestToken> tokenFactory) {
      this.clock = Objects.requireNonNull(clock, "clock");
      this.tokenFactory = Objects.requireNonNull(tokenFactory, "tokenFactory");
   }

   public String register(T value, long expiresAtMillis) {
      return this.registerToken(value, expiresAtMillis).value();
   }

   public MediaRequestToken registerToken(T value, long expiresAtMillis) {
      Objects.requireNonNull(value, "value");
      this.cleanupExpired();

      MediaRequestToken token;
      do {
         token = Objects.requireNonNull(this.tokenFactory.get(), "token");
      } while (this.entries.putIfAbsent(token, new OneShotRequestRegistry.Entry(value, expiresAtMillis)) != null);

      return token;
   }

   public T consume(String token) {
      return MediaRequestToken.parse(token).map(this::consumeToken).orElse(null);
   }

   public T consumeToken(MediaRequestToken token) {
      if (token == null) {
         return null;
      } else {
         OneShotRequestRegistry.Entry<T> entry = this.entries.remove(token);
         return entry != null && entry.expiresAtMillis() >= this.clock.getAsLong() ? entry.value() : null;
      }
   }

   public boolean contains(String token) {
      return MediaRequestToken.parse(token).map(this::containsToken).orElse(false);
   }

   public boolean containsToken(MediaRequestToken token) {
      if (token == null) {
         return false;
      } else {
         OneShotRequestRegistry.Entry<T> entry = this.entries.get(token);
         if (entry == null) {
            return false;
         } else if (entry.expiresAtMillis() >= this.clock.getAsLong()) {
            return true;
         } else {
            this.entries.remove(token, entry);
            return false;
         }
      }
   }

   public void cancel(String token) {
      MediaRequestToken.parse(token).ifPresent(this::cancelToken);
   }

   public void cancelToken(MediaRequestToken token) {
      if (token != null) {
         this.entries.remove(token);
      }
   }

   public void cleanupExpired() {
      long now = this.clock.getAsLong();
      this.entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
   }

   public void clear() {
      this.entries.clear();
   }

   private record Entry<T>(T value, long expiresAtMillis) {
   }
}
