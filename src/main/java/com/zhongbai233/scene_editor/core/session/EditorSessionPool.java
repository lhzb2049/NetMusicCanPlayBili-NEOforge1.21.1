package com.zhongbai233.scene_editor.core.session;

import com.zhongbai233.scene_editor.core.command.CommandStack;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

public final class EditorSessionPool<K, S> {
   private final int maximumSessions;
   private final int maximumCommands;
   private final long ttlNanos;
   private final LongSupplier ticker;
   private final LinkedHashMap<K, EditorSessionPool.Entry<S>> entries = new LinkedHashMap<>(16, 0.75F, true);

   public EditorSessionPool(int maximumSessions, int maximumCommands, long ttlNanos) {
      this(maximumSessions, maximumCommands, ttlNanos, System::nanoTime);
   }

   EditorSessionPool(int maximumSessions, int maximumCommands, long ttlNanos, LongSupplier ticker) {
      if (maximumSessions > 0 && maximumCommands > 0 && ttlNanos > 0L) {
         this.maximumSessions = maximumSessions;
         this.maximumCommands = maximumCommands;
         this.ttlNanos = ttlNanos;
         this.ticker = Objects.requireNonNull(ticker, "ticker");
      } else {
         throw new IllegalArgumentException("session pool limits must be positive");
      }
   }

   public synchronized Optional<EditorSessionPool.Session<S>> take(K key, S currentDocument, BiPredicate<? super S, ? super S> matches) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(currentDocument, "currentDocument");
      Objects.requireNonNull(matches, "matches");
      this.pruneExpired(this.ticker.getAsLong());
      EditorSessionPool.Entry<S> entry = this.entries.remove(key);
      return entry != null && matches.test(entry.document(), currentDocument)
         ? Optional.of(new EditorSessionPool.Session<>(entry.document(), entry.history()))
         : Optional.empty();
   }

   public synchronized void put(K key, S document, CommandStack<S> history) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(document, "document");
      Objects.requireNonNull(history, "history");
      long now = this.ticker.getAsLong();
      this.pruneExpired(now);
      this.entries.remove(key);
      if (history.size() != 0) {
         this.entries.put(key, new EditorSessionPool.Entry<>(document, history, now));
         this.enforceLimits();
      }
   }

   public synchronized void discard(K key) {
      this.entries.remove(Objects.requireNonNull(key, "key"));
   }

   public synchronized void clear() {
      this.entries.clear();
   }

   public synchronized int sessionCount() {
      this.pruneExpired(this.ticker.getAsLong());
      return this.entries.size();
   }

   public synchronized int commandCount() {
      this.pruneExpired(this.ticker.getAsLong());
      return this.totalCommands();
   }

   private void pruneExpired(long now) {
      this.entries.entrySet().removeIf(entry -> now - entry.getValue().lastAccessNanos() >= this.ttlNanos);
   }

   private void enforceLimits() {
      Iterator<Map.Entry<K, EditorSessionPool.Entry<S>>> iterator = this.entries.entrySet().iterator();

      while ((this.entries.size() > this.maximumSessions || this.totalCommands() > this.maximumCommands) && iterator.hasNext()) {
         iterator.next();
         iterator.remove();
      }
   }

   private int totalCommands() {
      int result = 0;

      for (EditorSessionPool.Entry<S> entry : this.entries.values()) {
         result += entry.history().size();
      }

      return result;
   }

   private record Entry<S>(S document, CommandStack<S> history, long lastAccessNanos) {
   }

   public record Session<S>(S document, CommandStack<S> history) {
      public Session(S document, CommandStack<S> history) {
         Objects.requireNonNull(document, "document");
         Objects.requireNonNull(history, "history");
         this.document = document;
         this.history = history;
      }
   }
}
