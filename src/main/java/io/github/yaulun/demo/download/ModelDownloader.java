package io.github.yaulun.demo.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumable downloader with fixed size/hash validation and atomic publication of completed files.
 */
public final class ModelDownloader {
  private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  private final HttpClient client;
  private final PrintStream progress;

  /**
   * Uses the supplied HTTP client and progress destination, allowing local HTTP integration tests.
   */
  public ModelDownloader(HttpClient client, PrintStream progress) {
    this.client = Objects.requireNonNull(client);
    this.progress = Objects.requireNonNull(progress);
  }

  /** Creates a redirect-following client with a 15-second connection timeout. */
  public static HttpClient defaultClient() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
  }

  /** Returns a validated existing file or downloads and validates a revision-pinned model. */
  public Path ensure(ModelSpec spec, Path directory, List<DownloadSource> sources) {
    return ensureFromUris(
        spec, directory, sources.stream().map(source -> source.uriFor(spec)).toList());
  }

  Path ensureFromUris(ModelSpec spec, Path directory, List<URI> sources) {
    List<String> attempts = new ArrayList<>();
    Path target = directory.toAbsolutePath().normalize().resolve(spec.fileName());
    Path part = target.resolveSibling(target.getFileName() + ".part");
    try {
      Files.createDirectories(directory);
      try (FileChannel channel =
              FileChannel.open(
                  target.resolveSibling(target.getFileName() + ".lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          FileLock lock = channel.tryLock()) {
        if (lock == null) {
          throw new IOException("Another downloader owns " + target);
        }
        if (Files.exists(target)) {
          if (verify(target, spec)) {
            return target;
          }
          Path isolated =
              target.resolveSibling(
                  target.getFileName() + ".invalid-" + System.currentTimeMillis());
          Files.move(target, isolated);
          progress.println("Existing file retained at " + isolated);
        }
        if (Files.exists(part) && Files.size(part) > spec.sizeBytes()) {
          Files.delete(part);
        }
        long present = Files.exists(part) ? Files.size(part) : 0;
        long needed = Math.addExact(spec.sizeBytes() - present, 200L * 1024 * 1024);
        long available = Files.getFileStore(directory).getUsableSpace();
        if (available < needed) {
          throw new IOException("Disk bytes required=" + needed + ", available=" + available);
        }
        for (URI source : sources) {
          for (int attempt = 1; attempt <= 3; attempt++) {
            try {
              if (!(Files.exists(part) && verify(part, spec))) {
                transfer(source, part, spec);
              }
              if (!verify(part, spec)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA-256 or size mismatch");
              }
              try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
              } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
              }
              return target;
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new DownloadException(
                  List.of("Download interrupted; partial data retained"), e);
            } catch (IOException e) {
              attempts.add(source.getHost() + " attempt " + attempt + ": " + e.getMessage());
            }
          }
        }
      }
    } catch (IOException | OverlappingFileLockException e) {
      attempts.add(e.getMessage());
      throw new DownloadException(attempts, e);
    }
    throw new DownloadException(attempts, null);
  }

  /** Streams a SHA-256 comparison after checking exact file length. */
  public boolean verify(Path file, ModelSpec spec) throws IOException {
    return Files.isRegularFile(file)
        && Files.size(file) == spec.sizeBytes()
        && sha256(file).equals(spec.sha256());
  }

  /** Calculates SHA-256 using a bounded 64 KiB buffer. */
  public static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, count);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("JDK SHA-256 required", e);
    }
  }

  private void transfer(URI source, Path part, ModelSpec spec)
      throws IOException, InterruptedException {
    long offset = Files.exists(part) ? Files.size(part) : 0;
    HttpRequest.Builder request =
        HttpRequest.newBuilder(source)
            .timeout(Duration.ofSeconds(60))
            .header("Accept-Encoding", "identity");
    if (offset > 0) {
      request.header("Range", "bytes=" + offset + "-");
    }
    HttpResponse<InputStream> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream input = response.body()) {
      int status = response.statusCode();
      if (status == 416) {
        if (verify(part, spec)) {
          return;
        }
        Files.deleteIfExists(part);
        throw new IOException("HTTP 416; partial data reset");
      }
      long expectedEnd = spec.sizeBytes() - 1;
      if (status == 206) {
        Matcher range =
            CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
        boolean valid;
        try {
          valid =
              range.matches()
                  && Long.parseLong(range.group(1)) == offset
                  && Long.parseLong(range.group(2)) >= offset
                  && Long.parseLong(range.group(2)) < spec.sizeBytes()
                  && Long.parseLong(range.group(3)) == spec.sizeBytes();
          if (valid) {
            expectedEnd = Long.parseLong(range.group(2));
          }
        } catch (NumberFormatException e) {
          valid = false;
        }
        if (!valid) {
          Files.deleteIfExists(part);
          throw new IOException("Invalid Content-Range; partial data reset");
        }
      } else if (status == 200) {
        offset = 0;
      } else {
        throw new IOException("HTTP " + status);
      }
      long expectedResponse = expectedEnd - offset + 1;
      long announced =
          response.headers().firstValueAsLong("Content-Length").orElse(expectedResponse);
      if (announced != expectedResponse) {
        throw new IOException("Content-Length mismatch: " + announced);
      }
      AtomicLong lastData = new AtomicLong(System.nanoTime());
      AtomicBoolean stalled = new AtomicBoolean();
      ScheduledExecutorService watchdog =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "model-download-watchdog");
                thread.setDaemon(true);
                return thread;
              });
      watchdog.scheduleAtFixedRate(
          () -> {
            if (System.nanoTime() - lastData.get() >= TimeUnit.SECONDS.toNanos(60)) {
              stalled.set(true);
              try {
                input.close();
              } catch (IOException e) {
                progress.println("Download stream close: " + e.getMessage());
              }
            }
          },
          1,
          1,
          TimeUnit.SECONDS);
      try (OutputStream output =
          Files.newOutputStream(
              part,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              offset > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
        long downloaded = offset;
        long lastPrint = 0;
        long started = System.nanoTime();
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) >= 0) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Download interrupted");
          }
          lastData.set(System.nanoTime());
          if (count == 0) {
            continue;
          }
          if (downloaded + count > expectedEnd + 1) {
            throw new IOException("Response exceeded declared range");
          }
          output.write(buffer, 0, count);
          downloaded += count;
          long now = System.nanoTime();
          if (now - lastPrint > TimeUnit.MILLISECONDS.toNanos(500)) {
            double speed = (downloaded - offset) * 1_000_000_000.0 / Math.max(1, now - started);
            progress.printf(
                Locale.ROOT,
                "\r%s %.1f%% %.1f/%.1f MiB %.1f MiB/s",
                spec.fileName(),
                100.0 * downloaded / spec.sizeBytes(),
                downloaded / 1048576.0,
                spec.sizeBytes() / 1048576.0,
                speed / 1048576.0);
            lastPrint = now;
          }
        }
        if (stalled.get()) {
          throw new IOException("No download data for 60 seconds");
        }
        if (downloaded != expectedEnd + 1 || downloaded != spec.sizeBytes()) {
          throw new IOException(
              "Incomplete response retained: " + downloaded + "/" + spec.sizeBytes());
        }
      } finally {
        watchdog.shutdownNow();
        progress.println();
      }
    }
  }
}
