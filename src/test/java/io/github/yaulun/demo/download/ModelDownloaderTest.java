package io.github.yaulun.demo.download;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelDownloaderTest {
  @TempDir Path directory;
  private HttpServer server;
  private byte[] data;
  private ModelSpec spec;
  private ModelDownloader downloader;
  private URI uri;

  @BeforeEach
  void start() throws Exception {
    data = new byte[4096];
    for (int index = 0; index < data.length; index++) {
      data[index] = (byte) (index * 37);
    }
    spec =
        new ModelSpec(
            "test",
            "test.gguf",
            data.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    downloader =
        new ModelDownloader(
            ModelDownloader.defaultClient(), new PrintStream(OutputStream.nullOutputStream()));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void resumesValid206AndPublishesAfterDigestCheck() throws Exception {
    Files.write(directory.resolve("test.gguf.part"), Arrays.copyOf(data, 500));
    server.createContext(
        "/model",
        exchange -> {
          assertEquals("bytes=500-", exchange.getRequestHeaders().getFirst("Range"));
          exchange.getResponseHeaders().set("Content-Range", "bytes 500-4095/4096");
          exchange.sendResponseHeaders(206, 3596);
          try (var output = exchange.getResponseBody()) {
            output.write(data, 500, 3596);
          }
        });
    assertArrayEquals(
        data, Files.readAllBytes(downloader.ensureFromUris(spec, directory, List.of(uri))));
  }

  @Test
  void handlesIgnoredRangeByReplacingPartialContent() throws Exception {
    Files.write(directory.resolve("test.gguf.part"), new byte[400]);
    server.createContext(
        "/model",
        exchange -> {
          exchange.sendResponseHeaders(200, data.length);
          try (var output = exchange.getResponseBody()) {
            output.write(data);
          }
        });
    assertArrayEquals(
        data, Files.readAllBytes(downloader.ensureFromUris(spec, directory, List.of(uri))));
  }

  @Test
  void rejectsWrongContentRangeThenRetriesFromZero() throws Exception {
    Files.write(directory.resolve("test.gguf.part"), Arrays.copyOf(data, 500));
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/model",
        exchange -> {
          if (calls.getAndIncrement() == 0) {
            exchange.getResponseHeaders().set("Content-Range", "bytes 499-4095/4096");
            exchange.sendResponseHeaders(206, 3597);
            try (var output = exchange.getResponseBody()) {
              output.write(data, 499, 3597);
            }
          } else {
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
              output.write(data);
            }
          }
        });
    assertArrayEquals(
        data, Files.readAllBytes(downloader.ensureFromUris(spec, directory, List.of(uri))));
    assertEquals(2, calls.get());
  }

  @Test
  void resetsInvalid416AndAcceptsNextCompleteResponse() throws Exception {
    Files.write(directory.resolve("test.gguf.part"), new byte[4096]);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/model",
        exchange -> {
          if (calls.getAndIncrement() == 0) {
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
          } else {
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
              output.write(data);
            }
          }
        });
    assertTrue(downloader.verify(downloader.ensureFromUris(spec, directory, List.of(uri)), spec));
  }

  @Test
  void resumesAfterTruncatedConnection() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/model",
        exchange -> {
          if (calls.getAndIncrement() == 0) {
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
              output.write(data, 0, 1000);
            }
          } else {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int offset = Integer.parseInt(range.substring(6, range.length() - 1));
            exchange.getResponseHeaders().set("Content-Range", "bytes " + offset + "-4095/4096");
            exchange.sendResponseHeaders(206, data.length - offset);
            try (var output = exchange.getResponseBody()) {
              output.write(data, offset, data.length - offset);
            }
          }
        });
    assertTrue(downloader.verify(downloader.ensureFromUris(spec, directory, List.of(uri)), spec));
  }

  @Test
  void aggregates404AndDigestMismatchFailures() throws Exception {
    server.createContext(
        "/model",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    assertEquals(
        3,
        assertThrows(
                DownloadException.class,
                () -> downloader.ensureFromUris(spec, directory, List.of(uri)))
            .attempts()
            .size());
    server.removeContext("/model");
    server.createContext(
        "/model",
        exchange -> {
          exchange.sendResponseHeaders(200, data.length);
          try (var output = exchange.getResponseBody()) {
            output.write(new byte[data.length]);
          }
        });
    assertThrows(
        DownloadException.class, () -> downloader.ensureFromUris(spec, directory, List.of(uri)));
    assertThrows(IllegalArgumentException.class, () -> ModelCatalog.byQuant("Q2"));
    assertEquals(3, DownloadSource.parse("auto").size());
  }
}
