package org.tongcraft.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Public catalogue browsing is independent of the currently connected game server. */
public final class LibraryScreen extends Screen {
  private static final String BASE =
      System.getProperty("tongcraft.libraryUrl", "https://library.weiuou.top").replaceAll("/+$", "");
  private final Screen parent;
  private final String base;
  private final SyncClient sync = TongCraftClient.SYNC;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "TongCraft Library");
            thread.setDaemon(true);
            return thread;
          });
  private JsonArray items = new JsonArray();
  private final List<Button> downloadButtons = new ArrayList<>();
  private EditBox search;
  private String query = "", status = "正在加载素材库…";
  private int page = 1, total, pageSize = 4, requestId;
  private long searchChangedAt;
  private boolean started, closed, downloading;

  public LibraryScreen(Screen parent) {
    this(parent, BASE);
  }

  LibraryScreen(Screen parent, String base) {
    super(Component.literal("TongCraft · 投影素材库"));
    this.parent = parent;
    this.base = base.replaceAll("/+$", "");
  }

  private Button button(String label, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(Component.literal(label), b -> action.run())
            .bounds(x, y, width, 20)
            .build());
  }

  @Override
  protected void init() {
    boolean focused = search != null && search.isFocused();
    int cursor = search == null ? 0 : search.getCursorPosition();
    int x = 12, width = this.width - 24;
    int newSize = Math.max(1, Math.min(6, (height - 185) / 50));
    if (newSize != pageSize) {
      pageSize = newSize;
      page = 1;
      if (started) refresh();
    }
    search = new EditBox(font, x, 36, width - 76, 20, Component.literal("搜索素材"));
    search.setMaxLength(80);
    search.setValue(query);
    search.setHint(Component.literal("搜索名称、介绍或作者"));
    search.setResponder(
        value -> {
          query = value;
          page = 1;
          searchChangedAt = System.currentTimeMillis();
        });
    addRenderableWidget(search);
    if (focused) {
      setInitialFocus(search);
      search.setCursorPosition(Math.min(cursor, query.length()));
    }
    button("刷新", this.width - 76, 36, 64, this::refresh);

    downloadButtons.clear();
    for (int i = 0; i < items.size(); i++) {
      JsonObject item = items.get(i).getAsJsonObject();
      String title = Wire.string(item, "title", "未命名蓝图");
      int y = 70 + i * 50;
      button(font.plainSubstrByWidth(title, width - 143), x, y, width - 136,
          () -> status = title + " · " + Wire.string(item, "description", ""));
      Button download = button("下载", this.width - 142, y, 48, () -> download(item, false));
      Button place = button("放置投影", this.width - 90, y, 78, () -> download(item, true));
      download.active = !downloading;
      place.active = !downloading && minecraft.player != null && minecraft.level != null;
      downloadButtons.add(download);
      downloadButtons.add(place);
    }
    button("上一页", x, height - 80, 70,
        () -> { if (page > 1) { page--; refresh(); } });
    button("下一页", x + 76, height - 80, 70,
        () -> { if (page * pageSize < total) { page++; refresh(); } });
    button("复制网页上传链接", x, height - 51, Math.max(100, width - 90), sync::copyLibraryLoginLink);
    button("返回", this.width - 90, height - 51, 78, this::onClose);
    if (!started) {
      started = true;
      refresh();
    }
  }

  @Override
  public void tick() {
    if (searchChangedAt > 0 && System.currentTimeMillis() - searchChangedAt >= 300) {
      searchChangedAt = 0;
      refresh();
    }
  }

  private void refresh() {
    int current = ++requestId;
    int requestedPage = page, requestedSize = pageSize;
    String requestedQuery = query;
    status = "正在读取素材…";
    worker.execute(
        () -> {
          try {
            String path = "/api/items?limit=" + requestedSize + "&page=" + requestedPage
                + "&q=" + URLEncoder.encode(requestedQuery, StandardCharsets.UTF_8);
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
              body = stream.readNBytes(1_000_001);
            }
            if (response.statusCode() != 200 || body.length > 1_000_000)
              throw new IllegalStateException("素材目录暂时不可用");
            JsonObject result =
                JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray found = Wire.array(result, "items");
            int count = result.get("total").getAsInt();
            minecraft.execute(
                () -> {
                  if (closed || current != requestId) return;
                  items = found;
                  total = count;
                  if (!downloading) status = count + " 份素材 · 第 " + page + " 页";
                  rebuildWidgets();
                });
          } catch (Exception error) {
            minecraft.execute(
                () -> {
                  if (!closed && current == requestId)
                    status = "读取失败：" + error.getMessage();
                });
          }
        });
  }

  private void download(JsonObject item, boolean place) {
    if (downloading || closed) return;
    if (place && (minecraft.player == null || minecraft.level == null)) {
      status = "请先进入世界再放置投影";
      return;
    }
    String id = Wire.string(item, "id", "");
    String hash = Wire.string(item, "sha256", "");
    String name = Wire.string(item, "title", "蓝图");
    if (!id.matches("[a-f0-9-]{36}") || !hash.matches("[a-f0-9]{64}")) {
      status = "素材目录返回了无效蓝图";
      return;
    }
    downloading = true;
    downloadButtons.forEach(button -> button.active = false);
    status = "正在下载：" + name;
    worker.execute(
        () -> {
          try {
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(base + "/api/items/" + id + "/file"))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
              bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
            }
            if (response.statusCode() != 200 || bytes.length == 0 || bytes.length > 16 * 1024 * 1024)
              throw new IllegalStateException("蓝图下载失败或超过大小限制");
            if (!Wire.hash(bytes).equals(hash)) throw new IllegalStateException("蓝图哈希校验失败");
            Path directory = FabricLoader.getInstance().getGameDir().resolve("schematics");
            Files.createDirectories(directory);
            Path target = directory.resolve("TongCraft-" + id + ".litematic");
            Path tmp = directory.resolve("TongCraft-" + id + ".part");
            try {
              Files.write(tmp, bytes);
              Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
              Files.deleteIfExists(tmp);
            }
            minecraft.execute(
                () -> {
                  if (closed) return;
                  downloading = false;
                  rebuildWidgets();
                  status = "已保存到 schematics：" + target.getFileName();
                  if (place && minecraft.gui.screen() == this) {
                    try {
                      JsonObject data = sync.bridge().localTransform(target, name);
                      PlacementForm.open(this, "放置本地投影", "放置投影", "投影名称", data,
                          values -> {
                            sync.bridge().placeLocal(values);
                            sync.message("已放置并选中本地投影；需要共享时使用“发布此投影”");
                            closed = true;
                            worker.shutdownNow();
                            minecraft.gui.setScreen(null);
                          });
                    } catch (Exception error) {
                      status = "放置失败：" + error.getMessage();
                    }
                  }
                });
          } catch (Exception error) {
            minecraft.execute(
                () -> {
                  if (!closed) {
                    downloading = false;
                    rebuildWidgets();
                    status = "下载失败：" + error.getMessage();
                  }
                });
          }
        });
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, title, width / 2, 12, 0xffe6efff);
    for (int i = 0; i < items.size(); i++) {
      JsonObject item = items.get(i).getAsJsonObject();
      JsonObject owner = Wire.nested(item, "owner");
      String detail = Wire.string(owner, "name", "?") + " · "
          + item.get("blocks").getAsInt() + " 方块 · "
          + Math.max(1, item.get("size").getAsInt() / 1024) + " KiB";
      g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, 96 + i * 50, 0xffaab8c8);
    }
    g.text(font, font.plainSubstrByWidth(status, width - 24), 12, height - 105, 0xffb6d9c5);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 24), 12, height - 24, 0xff8daaa5);
  }

  @Override
  public void onClose() {
    closed = true;
    worker.shutdownNow();
    minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
