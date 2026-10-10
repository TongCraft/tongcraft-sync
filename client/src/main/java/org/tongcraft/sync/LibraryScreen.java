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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
  private JsonObject selected;
  private LibraryPreviews previews;
  private final List<Button> downloadButtons = new ArrayList<>();
  private EditBox search;
  private String query = "", status = "正在加载素材库…";
  private int page = 1, total, pageSize = 4, requestId;
  private int columns, rows, cardWidth, cardHeight;
  private long searchChangedAt;
  private boolean started, closed, downloading, showingLoginStatus;

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
    if (previews == null) previews = new LibraryPreviews(minecraft, base);
    downloadButtons.clear();
    if (selected != null) {
      initDetails();
      return;
    }
    boolean focused = search != null && search.isFocused();
    int cursor = search == null ? 0 : search.getCursorPosition();
    int x = 12, width = this.width - 24;
    columns = Math.max(1, Math.min(4, (width + 6) / 140));
    rows = Math.max(1, Math.min(6, (height - 126) / 50));
    cardWidth = (width - (columns - 1) * 6) / columns;
    cardHeight = Math.max(48, (height - 126) / rows - 6);
    int newSize = Math.min(24, columns * rows);
    if (newSize != pageSize) {
      pageSize = newSize;
      page = 1;
      if (started) refresh();
    }
    search = new EditBox(font, x, 28, width - 76, 18, Component.literal("搜索素材"));
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
    button("刷新", this.width - 76, 28, 64, this::refresh);

    for (int i = 0; i < Math.min(items.size(), pageSize); i++) {
      JsonObject item = items.get(i).getAsJsonObject();
      addRenderableWidget(new MaterialCard(item, x + (i % columns) * (cardWidth + 6),
          54 + (i / columns) * (cardHeight + 6)));
      previews.load(item);
    }
    button("上一页", x, height - 65, 64,
        () -> { if (page > 1) { page--; refresh(); } }).active = page > 1;
    button("下一页", this.width - 76, height - 65, 64,
        () -> { if (page * pageSize < total) { page++; refresh(); } }).active = page * pageSize < total;
    button("复制网页上传链接", x, height - 41, Math.max(100, width - 84), () -> {
      showingLoginStatus = true;
      sync.copyLibraryLoginLink();
    });
    button("返回", this.width - 90, height - 41, 78, this::onClose);
    if (!started) {
      started = true;
      refresh();
    }
  }

  private final class MaterialCard extends Button {
    private final JsonObject item;
    MaterialCard(JsonObject item, int x, int y) {
      super(x, y, cardWidth, cardHeight, Component.literal(Wire.string(item, "title", "未命名蓝图")),
          button -> { selected = item; rebuildWidgets(); }, DEFAULT_NARRATION);
      this.item = item;
      setTooltip(Tooltip.create(Component.literal(getMessage().getString() + "\n点击查看详情、下载或放置投影")));
    }
    @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
      int x = getX(), y = getY(), w = getWidth(), h = getHeight();
      g.fill(x, y, x + w, y + h, isHoveredOrFocused() ? 0xee354955 : 0xdd18272e);
      g.outline(x, y, w, h, isHoveredOrFocused() ? 0xffa6d6dc : 0xff536a72);
      previews.draw(g, item, x + 3, y + 3, w - 6, h - 29);
      g.text(font, font.plainSubstrByWidth(getMessage().getString(), w - 10), x + 5, y + h - 23, 0xffe6efff);
      String meta = Wire.string(Wire.nested(item, "owner"), "name", "?") + " · "
          + item.get("blocks").getAsInt() + " 方块";
      g.text(font, font.plainSubstrByWidth(meta, w - 10), x + 5, y + h - 12, 0xffaab8c8);
    }
  }

  private void initDetails() {
    int x = 12, w = width - 24, actionWidth = (w - 12) / 3;
    Button download = button("下载", x, height - 58, actionWidth, () -> download(selected, false));
    Button place = button("放置投影", x + actionWidth + 6, height - 58, actionWidth, () -> download(selected, true));
    download.active = !downloading;
    place.active = !downloading && minecraft.player != null && minecraft.level != null;
    downloadButtons.add(download);
    downloadButtons.add(place);
    if (minecraft.player == null || minecraft.level == null)
      place.setTooltip(Tooltip.create(Component.literal("请先进入世界再放置投影")));
    button("返回", x + 2 * (actionWidth + 6), height - 58, actionWidth, this::onClose);
    previews.load(selected);
  }

  @Override
  public void tick() {
    if (searchChangedAt > 0 && System.currentTimeMillis() - searchChangedAt >= 300) {
      searchChangedAt = 0;
      refresh();
    }
  }

  private void refresh() {
    showingLoginStatus = false;
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
                  Set<String> ids = new HashSet<>();
                  found.forEach(entry -> ids.add(Wire.string(entry.getAsJsonObject(), "id", "")));
                  if (selected != null) ids.add(Wire.string(selected, "id", ""));
                  previews.retain(ids);
                  if (!downloading) status = count == 0 ? "没有找到匹配的素材" : "点击卡片查看详情、下载或放置投影";
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
    showingLoginStatus = false;
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
                  if (place && minecraft.gui.screen() == this && selected != null
                      && id.equals(Wire.string(selected, "id", ""))) {
                    try {
                      JsonObject data = sync.bridge().localTransform(target, name);
                      PlacementForm.open(this, "放置本地投影", "放置投影", "投影名称", data,
                          values -> {
                            sync.bridge().placeLocal(values);
                            sync.message("已放置并选中本地投影；需要共享时使用“发布此投影”");
                            closed = true;
                            worker.shutdownNow();
                            previews.close();
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
    if (selected != null) {
      renderDetails(g);
      return;
    }
    g.centeredText(font, title, width / 2, 8, 0xffe6efff);
    g.centeredText(font, total + " 份 · " + page + " / " + Math.max(1, (total + pageSize - 1) / pageSize),
        width / 2, height - 59, 0xffeeeeee);
    g.text(font, font.plainSubstrByWidth(displayStatus(), width - 24), 12, height - 15, 0xffb6d9c5);
  }

  String displayStatus() { return showingLoginStatus ? sync.status() : status; }

  private void renderDetails(GuiGraphicsExtractor g) {
    g.centeredText(font, font.plainSubstrByWidth(Wire.string(selected, "title", "素材详情"), width - 24),
        width / 2, 12, 0xffe6efff);
    int imageWidth = Math.max(90, (width - 36) / 2), imageHeight = Math.max(30, height - 105);
    previews.draw(g, selected, 12, 34, imageWidth, imageHeight);
    int x = imageWidth + 24, w = width - x - 12, bottom = height - 70;
    g.enableScissor(x, 34, width - 12, bottom);
    String[] lines = {
      "作者：" + Wire.string(Wire.nested(selected, "owner"), "name", "?"),
      selected.get("blocks").getAsInt() + " 方块 · " + Math.max(1, selected.get("size").getAsInt() / 1024) + " KiB",
      "简介"
    };
    int y = 36;
    for (String line : lines) {
      g.text(font, font.plainSubstrByWidth(line, w), x, y, 0xffc9d8df);
      y += 14;
    }
    String description = Wire.string(selected, "description", "");
    for (var line : font.split(Component.literal(description.isBlank() ? "暂无简介" : description), w)) {
      if (y + 9 > bottom) break;
      g.text(font, line, x, y, 0xffaab8c8);
      y += 11;
    }
    g.disableScissor();
    g.text(font, font.plainSubstrByWidth(displayStatus(), width - 24), 12, height - 30, 0xffb6d9c5);
  }

  @Override
  public void onClose() {
    if (selected != null) {
      selected = null;
      rebuildWidgets();
      return;
    }
    closed = true;
    worker.shutdownNow();
    if (previews != null) previews.close();
    minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
