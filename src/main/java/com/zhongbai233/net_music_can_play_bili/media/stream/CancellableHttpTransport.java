package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellableHttpTransport {
   private CancellableHttpTransport() {
   }

   public static CancellableHttpTransport.Response send(HttpClient client, HttpRequest request, HttpRequestCloseDiagnostics diagnostics, long operationId) throws IOException {
      CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request, BodyHandlers.ofInputStream());

      HttpResponse<InputStream> response;
      try {
         response = future.get();
      } catch (InterruptedException var10) {
         diagnostics.cancelRequested(operationId);
         closeLateResponse(future);
         future.cancel(true);
         diagnostics.terminal(operationId, false, 0L, System.nanoTime());
         Thread.currentThread().interrupt();
         throw new IOException("HTTP request interrupted", var10);
      } catch (ExecutionException var11) {
         diagnostics.terminal(operationId, false, 0L, System.nanoTime());
         Throwable cause = var11.getCause();
         if (cause instanceof IOException io) {
            throw io;
         }

         throw new IOException("HTTP request failed", cause);
      }

      diagnostics.headers(operationId, response.statusCode());
      diagnostics.bodyPublished(operationId);
      return new CancellableHttpTransport.Response(
         response.statusCode(), response.headers(), new CancellableHttpTransport.DiagnosticInputStream(response.body(), diagnostics, operationId)
      );
   }

   private static void closeLateResponse(CompletableFuture<HttpResponse<InputStream>> future) {
      future.whenComplete((lateResponse, failure) -> {
         if (lateResponse != null) {
            try {
               lateResponse.body().close();
            } catch (IOException var3) {
            }
         }
      });
   }

   private static final class DiagnosticInputStream extends FilterInputStream {
      private final HttpRequestCloseDiagnostics diagnostics;
      private final long operationId;
      private final AtomicBoolean terminal = new AtomicBoolean();
      private long bytes;
      private boolean eof;

      private DiagnosticInputStream(InputStream delegate, HttpRequestCloseDiagnostics diagnostics, long operationId) {
         super(delegate);
         this.diagnostics = diagnostics;
         this.operationId = operationId;
      }

      @Override
      public int read() throws IOException {
         try {
            int value = super.read();
            if (value < 0) {
               this.complete(true);
            } else {
               this.bytes++;
            }

            return value;
         } catch (IOException var2) {
            this.complete(false);
            throw var2;
         }
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
         try {
            int count = super.read(buffer, offset, length);
            if (count < 0) {
               this.complete(true);
            } else {
               this.bytes += count;
            }

            return count;
         } catch (IOException var5) {
            this.complete(false);
            throw var5;
         }
      }

      @Override
      public void close() throws IOException {
         if (!this.eof) {
            this.diagnostics.cancelRequested(this.operationId);
         }

         try {
            super.close();
            this.complete(true);
         } catch (IOException var2) {
            this.complete(false);
            throw var2;
         }
      }

      private void complete(boolean success) {
         if (this.terminal.compareAndSet(false, true)) {
            this.eof = success;
            this.diagnostics.terminal(this.operationId, success, this.bytes, System.nanoTime());
         }
      }
   }

   public record Response(int statusCode, HttpHeaders headers, InputStream body) {
   }
}
