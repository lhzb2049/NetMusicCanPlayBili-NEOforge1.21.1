package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * 只监听回环地址的极小 HTTP/1.1 文件服务：给本地媒体一个**真 http 源**。
 *
 * <p>为什么需要它：模组里视频侧（{@code Fmp4VideoStreamSeeker}/{@code ChunkPrefetchInputStream}）与音频侧
 * （{@code HttpAudioStreamHandler} → {@code Fmp4AudioStreamSeeker}）都只吃 http(s) URL，并且都靠
 * **Range 请求**做分块预取与定位。与其把这两条成熟链路改成支持 {@code file:}，不如把本地文件
 * 通过 127.0.0.1 回环暴露成 http —— 链路一行不用改，行为与播放 CDN 流完全一致。
 *
 * <p>安全边界（刻意如此）：
 * <ul>
 *   <li>只绑 {@code 127.0.0.1}，局域网/外网不可达；
 *   <li>路径里只有一次性随机 token，**没有文件路径**：token 查不到就 404，不存在目录穿越面；
 *   <li>token 有过期时间，本地媒体停止/切世界时整体回收。
 * </ul>
 *
 * <p>只实现了产品真正会发的请求：{@code GET}/{@code HEAD} + 有界或开放式 {@code Range}。
 * 不支持的写法（多段 Range、非法语法）退化为整文件 200，与 CDN 的宽容行为一致 ——
 * 客户端本来就按「206 才用 Content-Range、200 就从 offset 0 顺序读」处理。
 */
public final class LocalMediaHttpServer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final LocalMediaHttpServer INSTANCE = new LocalMediaHttpServer();
   private static final String PATH_PREFIX = "/ncpb/";
   private static final int MAX_REQUEST_HEADER_BYTES = 8192;
   private static final int COPY_BUFFER_BYTES = 64 * 1024;
   private static final int MAX_CONNECTIONS = 8;
   private static final int WORKER_THREADS = 4;
   private static final int SOCKET_READ_TIMEOUT_MILLIS = 10_000;
   private static final int SOCKET_WRITE_TIMEOUT_MILLIS = 30_000;

   private final Map<String, LocalMediaHttpServer.Entry> entries = new ConcurrentHashMap<>();
   private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
   private final AtomicInteger workerSequence = new AtomicInteger();
   private volatile ServerSocket socket;
   private volatile ExecutorService workers;
   private volatile Thread acceptor;
   private volatile boolean closed;

   private LocalMediaHttpServer() {
   }

   public static LocalMediaHttpServer instance() {
      return INSTANCE;
   }

   /** 一次发布的结果：可直接交给音频/视频链路的 http 地址。 */
   public record Published(String token, String url, long length) {
   }

   private record Entry(Path file, String contentType, long length, long expiresAtMillis, long ttlMillis) {
   }

   /**
    * 发布一个文件（需要时惰性启动服务）并返回它的回环地址。
    *
    * @param token       调用方生成的随机 token（只允许 {@code [A-Za-z0-9_-]}）
    * @param displayName URL 里展示用的文件名（不参与定位，仅便于日志/调试）
    */
   public synchronized Published publish(String token, Path file, String contentType, String displayName, long ttlMillis) throws IOException {
      if (this.closed) {
         throw new IOException("本地媒体回环服务已关闭");
      }

      String safeToken = requireToken(token);
      long length = Files.size(file);
      long ttl = Math.max(1000L, ttlMillis);
      long expiresAt = ttlMillis > 0L ? System.currentTimeMillis() + ttlMillis : Long.MAX_VALUE;
      this.entries.put(safeToken, new Entry(file, contentType, length, expiresAt, ttl));
      this.pruneExpired();
      this.ensureStarted();
      String url = "http://127.0.0.1:" + this.port() + PATH_PREFIX + safeToken + "/" + safeName(displayName);
      return new Published(safeToken, url, length);
   }

   public void revoke(String token) {
      if (token != null) {
         this.entries.remove(token);
      }
   }

   public void clear() {
      this.entries.clear();
   }

   public boolean isRunning() {
      ServerSocket current = this.socket;
      return current != null && !current.isClosed();
   }

   public int port() {
      ServerSocket current = this.socket;
      return current != null ? current.getLocalPort() : 0;
   }

   public int publishedCount() {
      this.pruneExpired();
      return this.entries.size();
   }

   /** 关服务并回收所有发布项（离线验证与关服清理用）。 */
   public synchronized void stop() {
      this.closed = true;
      this.entries.clear();
      ServerSocket current = this.socket;
      this.socket = null;
      if (current != null) {
         try {
            current.close();
         } catch (IOException error) {
            LOGGER.debug("关闭本地媒体回环服务失败: {}", error.getMessage());
         }
      }

      ExecutorService pool = this.workers;
      this.workers = null;
      if (pool != null) {
         pool.shutdownNow();
      }

      Thread thread = this.acceptor;
      this.acceptor = null;
      if (thread != null) {
         thread.interrupt();
      }
   }

   private void ensureStarted() throws IOException {
      if (this.socket == null || this.socket.isClosed()) {
         ServerSocket created = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
         this.socket = created;
         this.workers = Executors.newFixedThreadPool(
            WORKER_THREADS, NetMusicThreadFactory.daemon("ncpb-local-http-" + this.workerSequence.incrementAndGet())
         );
         Thread thread = NetMusicThreadFactory.daemonThread("ncpb-local-http-accept", this::acceptLoop);
         this.acceptor = thread;
         thread.start();
         LOGGER.debug("本地媒体回环服务已启动: 127.0.0.1:{}", created.getLocalPort());
      }
   }

   private void acceptLoop() {
      while (!this.closed) {
         ServerSocket current = this.socket;
         if (current == null) {
            return;
         }

         Socket client;
         try {
            client = current.accept();
         } catch (IOException error) {
            if (!this.closed) {
               LOGGER.debug("本地媒体回环服务接受连接失败: {}", error.getMessage());
            }

            return;
         }

         if (!this.connectionSlots.tryAcquire()) {
            closeQuietly(client);
            continue;
         }

         ExecutorService pool = this.workers;
         if (pool == null) {
            this.connectionSlots.release();
            closeQuietly(client);
            continue;
         }

         try {
            pool.execute(() -> {
               try {
                  this.handle(client);
               } finally {
                  this.connectionSlots.release();
               }
            });
         } catch (RuntimeException error) {
            this.connectionSlots.release();
            closeQuietly(client);
         }
      }
   }

   private void handle(Socket client) {
      try (Socket socket = client) {
         socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);
         InputStream rawIn = socket.getInputStream();
         OutputStream rawOut = socket.getOutputStream();
         BufferedInputStream in = new BufferedInputStream(rawIn, 4096);
         BufferedOutputStream out = new BufferedOutputStream(rawOut, COPY_BUFFER_BYTES);
         String requestLine = readLine(in, MAX_REQUEST_HEADER_BYTES);
         if (requestLine == null) {
            return;
         }

         String rangeHeader = null;
         int headerBytes = requestLine.length();

         while (true) {
            String line = readLine(in, MAX_REQUEST_HEADER_BYTES);
            if (line == null || line.isEmpty()) {
               break;
            }

            headerBytes += line.length();
            if (headerBytes > MAX_REQUEST_HEADER_BYTES) {
               respondError(out, 431, "请求头过大");
               return;
            }

            int colon = line.indexOf(58);
            if (colon <= 0) {
               continue;
            }

            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("range".equals(name) && rangeHeader == null) {
               rangeHeader = value;
            }
         }

         String[] parts = requestLine.split(" ");
         if (parts.length < 2) {
            respondError(out, 400, "请求行非法");
            return;
         }

         String method = parts[0].toUpperCase(Locale.ROOT);
         String target = parts[1];
         boolean headOnly = "HEAD".equals(method);
         if (!headOnly && !"GET".equals(method)) {
            respondError(out, 405, "只支持 GET/HEAD");
            return;
         }

         String token = tokenOf(target);
         Entry entry = token == null ? null : this.lookup(token);
         if (entry == null) {
            respondError(out, 404, "未发布的本地媒体地址");
            return;
         }

         long length = entry.length();
         long start = 0L;
         long end = length - 1L;
         boolean partial = false;
         if (rangeHeader != null && length > 0L) {
            long[] parsed = parseRange(rangeHeader, length);
            if (parsed != null) {
               if (parsed[0] < 0L) {
                  socket.setSoTimeout(SOCKET_WRITE_TIMEOUT_MILLIS);
                  writeHead(out, 416, entry.contentType(), 0L, "bytes */" + length);
                  out.flush();
                  return;
               }

               start = parsed[0];
               end = parsed[1];
               partial = true;
            }
         }

         long bodyLength = length <= 0L ? 0L : Math.max(0L, end - start + 1L);
         socket.setSoTimeout(SOCKET_WRITE_TIMEOUT_MILLIS);
         writeHead(
            out,
            partial ? 206 : 200,
            entry.contentType(),
            bodyLength,
            partial ? "bytes %d-%d/%d".formatted(start, end, length) : null
         );
         if (!headOnly && bodyLength > 0L) {
            copyRange(entry.file(), start, bodyLength, out);
         }

         out.flush();
      } catch (IOException error) {
         LOGGER.debug("本地媒体回环请求处理失败: {}", error.getMessage());
      }
   }

   private Entry lookup(String token) {
      Entry entry = this.entries.get(token);
      if (entry == null) {
         return null;
      }

      long now = System.currentTimeMillis();
      if (entry.expiresAtMillis() < now) {
         this.entries.remove(token, entry);
         return null;
      }

      // 滑动过期：正在播放的媒体每被请求一次就续期，长视频（超过 token TTL）不会播到一半变 404。
      // 只有剩余有效期不足一半时才重写条目，避免每个 Range 请求都动 map。
      long remaining = entry.expiresAtMillis() - now;
      if (entry.expiresAtMillis() != Long.MAX_VALUE && remaining < Math.max(1000L, entry.ttlMillis() / 2L)) {
         Entry refreshed = new Entry(
            entry.file(), entry.contentType(), entry.length(), now + entry.ttlMillis(), entry.ttlMillis()
         );
         this.entries.put(token, refreshed);
         return refreshed;
      }

      return entry;
   }

   private void pruneExpired() {
      long now = System.currentTimeMillis();
      this.entries.entrySet().removeIf(item -> item.getValue().expiresAtMillis() < now);
   }

   private void writeHead(OutputStream out, int status, String contentType, long length, String contentRange) throws IOException {
      StringBuilder head = new StringBuilder(256);
      head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
      head.append("Content-Type: ").append(contentType).append("\r\n");
      head.append("Content-Length: ").append(length).append("\r\n");
      head.append("Accept-Ranges: bytes\r\n");
      if (contentRange != null) {
         head.append("Content-Range: ").append(contentRange).append("\r\n");
      }

      head.append("Cache-Control: no-store\r\n");
      head.append("Connection: close\r\n\r\n");
      out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
   }

   private void respondError(OutputStream out, int status, String message) throws IOException {
      byte[] body = message.getBytes(StandardCharsets.UTF_8);
      StringBuilder head = new StringBuilder(160);
      head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
      head.append("Content-Type: text/plain; charset=utf-8\r\n");
      head.append("Content-Length: ").append(body.length).append("\r\n");
      head.append("Connection: close\r\n\r\n");
      out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
      out.flush();
   }

   private static String reason(int status) {
      return switch (status) {
         case 200 -> "OK";
         case 206 -> "Partial Content";
         case 400 -> "Bad Request";
         case 404 -> "Not Found";
         case 405 -> "Method Not Allowed";
         case 416 -> "Range Not Satisfiable";
         case 431 -> "Request Header Fields Too Large";
         default -> "Error";
      };
   }

   private static void copyRange(Path file, long start, long length, OutputStream out) throws IOException {
      ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
      long remaining = length;

      try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
         channel.position(start);

         while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int)Math.min((long)buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0) {
               return;
            }

            out.write(buffer.array(), 0, read);
            remaining -= read;
         }
      }
   }

   /**
    * 解析单段 Range：返回 {@code [start, endInclusive]}，不可满足返回 {@code [-1, -1]}，语法不认识返回 null。
    * 只支持 {@code bytes=a-b} / {@code bytes=a-} / {@code bytes=-n} 三种写法（产品只会发前两种）。
    */
   static long[] parseRange(String header, long length) {
      if (header == null) {
         return null;
      }

      String value = header.trim();
      if (!value.regionMatches(true, 0, "bytes=", 0, 6) || value.indexOf(44) >= 0) {
         return null;
      }

      String spec = value.substring(6).trim();
      int dash = spec.indexOf(45);
      if (dash < 0) {
         return null;
      }

      String startText = spec.substring(0, dash).trim();
      String endText = spec.substring(dash + 1).trim();

      try {
         if (startText.isEmpty()) {
            if (endText.isEmpty()) {
               return null;
            }

            long suffix = Long.parseLong(endText);
            if (suffix <= 0L) {
               return new long[]{-1L, -1L};
            }

            long start = Math.max(0L, length - suffix);
            return new long[]{start, length - 1L};
         }

         long start = Long.parseLong(startText);
         if (start < 0L || start >= length) {
            return new long[]{-1L, -1L};
         }

         long end = endText.isEmpty() ? length - 1L : Long.parseLong(endText);
         if (end < start) {
            return new long[]{-1L, -1L};
         }

         return new long[]{start, Math.min(end, length - 1L)};
      } catch (NumberFormatException error) {
         return null;
      }
   }

   private static String tokenOf(String target) {
      int query = target.indexOf(63);
      String path = query >= 0 ? target.substring(0, query) : target;
      if (!path.startsWith(PATH_PREFIX)) {
         return null;
      }

      String rest = path.substring(PATH_PREFIX.length());
      int slash = rest.indexOf(47);
      String token = slash >= 0 ? rest.substring(0, slash) : rest;
      return token.isEmpty() ? null : token;
   }

   private static String requireToken(String token) throws IOException {
      if (token == null || token.isEmpty() || token.length() > 64) {
         throw new IOException("本地媒体 token 非法");
      }

      for (int i = 0; i < token.length(); i++) {
         char c = token.charAt(i);
         boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-';
         if (!allowed) {
            throw new IOException("本地媒体 token 含非法字符: " + c);
         }
      }

      return token;
   }

   private static String safeName(String displayName) {
      if (displayName == null || displayName.isBlank()) {
         return "media.bin";
      }

      StringBuilder out = new StringBuilder(displayName.length());

      for (int i = 0; i < displayName.length(); i++) {
         char c = displayName.charAt(i);
         boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-';
         out.append(allowed ? c : '_');
      }

      return out.length() == 0 ? "media.bin" : out.toString();
   }

   private static String readLine(InputStream in, int limit) throws IOException {
      StringBuilder line = new StringBuilder(64);

      while (line.length() <= limit) {
         int read = in.read();
         if (read < 0) {
            return line.length() == 0 ? null : line.toString();
         }

         if (read == 10) {
            int length = line.length();
            if (length > 0 && line.charAt(length - 1) == '\r') {
               line.setLength(length - 1);
            }

            return line.toString();
         }

         line.append((char)read);
      }

      throw new IOException("HTTP 请求行过长");
   }

   private static void closeQuietly(Socket socket) {
      try {
         socket.close();
      } catch (IOException ignored) {
      }
   }

   static {
      Runtime.getRuntime().addShutdownHook(new Thread(LocalMediaHttpServer.INSTANCE::stop, "ncpb-local-http-shutdown"));
   }

   /** 供验证工具等待端口就绪（正常路径下 publish 返回即可用）。 */
   public boolean awaitReady(long timeoutMillis) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));

      while (System.nanoTime() < deadline) {
         if (this.isRunning()) {
            return true;
         }

         try {
            Thread.sleep(5L);
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
         }
      }

      return this.isRunning();
   }
}
