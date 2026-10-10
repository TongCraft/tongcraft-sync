package org.tongcraft.sync;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Fetches saved PNGs only; no schematics or world rendering are needed to browse. */
final class LibraryPreviews implements AutoCloseable {
  private static final int MAX_BYTES = 512 * 1024;
  private final Minecraft minecraft;
  private final String base;
  private final Path directory;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
    Thread thread = new Thread(task, "TongCraft Library Preview");
    thread.setDaemon(true);
    return thread;
  });
  private record Preview(Identifier texture, int width, int height) {}
  private final Map<String, Preview> images = new HashMap<>();
  private final Set<String> requested = new HashSet<>();
  private boolean closed;

  LibraryPreviews(Minecraft minecraft, String base) {
    this.minecraft = minecraft;
    this.base = base;
    this.directory = FabricLoader.getInstance().getGameDir().resolve(".cache/tongcraft-library")
        .resolve(java.util.UUID.nameUUIDFromBytes(base.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
  }

  void load(JsonObject item) {
    String id = Wire.string(item, "id", "");
    if (closed || !id.matches("[a-f0-9-]{36}")
        || !Wire.string(item, "previewUrl", "").equals("/api/items/" + id + "/preview")
        || !requested.add(id)) return;
    worker.execute(() -> {
      try {
        Path path = directory.resolve(id + ".png");
        byte[] bytes = null;
        if (Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES) {
          byte[] cached = Files.readAllBytes(path);
          try { validate(cached); bytes = cached; }
          catch (Exception ignored) { Files.deleteIfExists(path); }
        }
        if (bytes == null) {
          HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/items/" + id + "/preview"))
              .timeout(Duration.ofSeconds(20)).GET().build();
          HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
          try (InputStream stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
          if (response.statusCode() != 200
              || !response.headers().firstValue("Content-Type").orElse("").startsWith("image/png"))
            throw new IllegalStateException("预览图暂不可用");
          validate(bytes);
          Files.createDirectories(directory);
          Path tmp = Files.createTempFile(directory, id, ".part");
          try {
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
          } finally { Files.deleteIfExists(tmp); }
        }
        NativeImage image;
        try { image = NativeImage.read(bytes); }
        catch (Exception error) { Files.deleteIfExists(path); throw error; }
        minecraft.execute(() -> {
          if (closed || !requested.contains(id)) { image.close(); return; }
          Identifier texture = Identifier.fromNamespaceAndPath("tongcraft_sync", "library/" + id);
          minecraft.getTextureManager().register(texture, new DynamicTexture(() -> "TongCraft " + id, image));
          images.put(id, new Preview(texture, image.getWidth(), image.getHeight()));
        });
      } catch (Exception ignored) {
        // An unavailable image must not prevent reading or downloading its schematic.
      }
    });
  }

  private static void validate(byte[] bytes) {
    if (bytes.length < 33 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("PNG size");
    ByteBuffer data = ByteBuffer.wrap(bytes);
    if (data.getLong(0) != 0x89504e470d0a1a0aL || data.getInt(8) != 13 || data.getInt(12) != 0x49484452)
      throw new IllegalArgumentException("PNG header");
    int width = data.getInt(16), height = data.getInt(20);
    if (width < 1 || height < 1 || width > 1024 || height > 1024)
      throw new IllegalArgumentException("PNG dimensions");
  }

  void retain(Set<String> ids) {
    requested.retainAll(ids);
    for (String id : Set.copyOf(images.keySet())) {
      if (!ids.contains(id)) {
        minecraft.getTextureManager().release(images.remove(id).texture());
      }
    }
  }

  void draw(GuiGraphicsExtractor g, JsonObject item, int x, int y, int width, int height) {
    g.fill(x, y, x + width, y + height, 0xffe5e8e4);
    Preview image = images.get(Wire.string(item, "id", ""));
    if (image != null) {
      double scale = Math.min((double) width / image.width(), (double) height / image.height());
      int w = Math.max(1, (int) (image.width() * scale)), h = Math.max(1, (int) (image.height() * scale));
      int dx = x + (width - w) / 2, dy = y + (height - h) / 2;
      g.blit(image.texture(), dx, dy, dx + w, dy + h, 0, 1, 0, 1);
    } else {
      String text = item.has("previewUrl") ? "预览图加载中 / 暂不可用" : "暂无渲染图";
      g.centeredText(minecraft.font, minecraft.font.plainSubstrByWidth(text, width - 8),
          x + width / 2, y + (height - 9) / 2, 0xff52616a);
    }
  }

  @Override public void close() {
    closed = true;
    worker.shutdownNow();
    images.values().forEach(image -> minecraft.getTextureManager().release(image.texture()));
    images.clear();
  }
}
