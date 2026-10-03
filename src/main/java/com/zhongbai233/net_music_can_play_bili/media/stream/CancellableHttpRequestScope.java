package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellableHttpRequestScope implements AutoCloseable {
   private final HttpRequestCloseDiagnostics diagnostics;
   private final ConcurrentHashMap<CompletableFuture<?>, Long> requests = new ConcurrentHashMap<>();
   private final AtomicBoolean closed = new AtomicBoolean();
   private final Object lifecycleLock = new Object();

   public CancellableHttpRequestScope(HttpRequestCloseDiagnostics diagnostics) {
      this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
   }

   public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpClient client, HttpRequest request, BodyHandler<T> bodyHandler, String kind) {
      Objects.requireNonNull(client, "client");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(bodyHandler, "bodyHandler");
      if (this.closed.get()) {
         return CompletableFuture.failedFuture(new CancellationException("HTTP request scope is closed"));
      } else {
         long operationId = this.diagnostics.begin(kind, safeHost(request.uri()), -1L, -1L, System.nanoTime());

         CompletableFuture<HttpResponse<T>> future;
         try {
            future = client.sendAsync(request, bodyHandler);
         } catch (Error | RuntimeException var11) {
            this.diagnostics.terminal(operationId, false, 0L, System.nanoTime());
            throw var11;
         }

         boolean cancelImmediately;
         synchronized (this.lifecycleLock) {
            cancelImmediately = this.closed.get();
            if (!cancelImmediately) {
               this.requests.put(future, operationId);
            }
         }

         future.whenComplete((response, error) -> {
            try {
               if (response != null) {
                  this.diagnostics.headers(operationId, response.statusCode());
                  this.diagnostics.bodyPublished(operationId);
               }

               this.diagnostics.terminal(operationId, error == null, responseBytes((HttpResponse<?>)response), System.nanoTime());
            } finally {
               this.requests.remove(future, operationId);
            }
         });
         if (cancelImmediately) {
            this.cancel(future, operationId);
         }

         return future;
      }
   }

   public <T> HttpResponse<T> sendBlocking(HttpClient client, HttpRequest request, BodyHandler<T> bodyHandler, String kind) throws IOException, InterruptedException {
      CompletableFuture<HttpResponse<T>> future = this.sendAsync(client, request, bodyHandler, kind);

      try {
         return future.get();
      } catch (InterruptedException var9) {
         Long operationId = this.requests.get(future);
         if (operationId != null) {
            this.cancel(future, operationId);
         } else {
            future.cancel(true);
         }

         Thread.currentThread().interrupt();
         throw var9;
      } catch (ExecutionException var10) {
         Throwable cause = var10.getCause();
         if (cause instanceof IOException io) {
            throw io;
         } else if (cause instanceof RuntimeException runtime) {
            throw runtime;
         } else if (cause instanceof Error error) {
            throw error;
         } else {
            throw new IOException("HTTP request failed", cause);
         }
      }
   }

   public static <T> HttpResponse<T> sendOneBlocking(
      HttpClient client, HttpRequest request, BodyHandler<T> bodyHandler, HttpRequestCloseDiagnostics diagnostics, String kind
   ) throws IOException, InterruptedException {
      HttpResponse var6;
      try (CancellableHttpRequestScope scope = new CancellableHttpRequestScope(diagnostics)) {
         var6 = scope.sendBlocking(client, request, bodyHandler, kind);
      }

      return var6;
   }

   public boolean isClosed() {
      return this.closed.get();
   }

   public int activeRequests() {
      return this.requests.size();
   }

   @Override
   public void close() {
      Map<CompletableFuture<?>, Long> toCancel;
      synchronized (this.lifecycleLock) {
         if (!this.closed.compareAndSet(false, true)) {
            return;
         }

         toCancel = new HashMap<>(this.requests);
         this.requests.clear();
      }

      toCancel.forEach((future, operationId) -> this.cancel((CompletableFuture<?>)future, operationId));
   }

   private void cancel(CompletableFuture<?> future, long operationId) {
      this.diagnostics.cancelRequested(operationId);
      future.cancel(true);
   }

   private static long responseBytes(HttpResponse<?> response) {
      if (response != null && response.body() != null) {
         Object body = response.body();
         if (body instanceof byte[] bytes) {
            return bytes.length;
         } else {
            return body instanceof String text ? text.getBytes(StandardCharsets.UTF_8).length : 0L;
         }
      } else {
         return 0L;
      }
   }

   private static String safeHost(URI uri) {
      String host = uri != null ? uri.getHost() : null;
      return host != null && !host.isBlank() ? host : "<unknown>";
   }
}
