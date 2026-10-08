package org.tongcraft.sync;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class SyncScreen extends Screen {
  private final Screen parent;
  private final SyncClient sync = TongCraftClient.SYNC;
  private JsonObject seen;
  private EditBox search;
  private String query = "";
  private boolean currentDimension;
  private int page;
  private List<JsonObject> rows = List.of();
  private List<JsonObject> entries = List.of();
  private String seenEntries = "", seenSelection = "";
  private int ticks;

  public SyncScreen(Screen parent) {
    super(Component.literal("TongCraft · 投影管理"));
    this.parent = parent;
  }

  private Button button(String label, int x, int y, int w, Runnable action) {
    return addRenderableWidget(
        Button.builder(
                Component.literal(label),
                b -> {
                  try {
                    action.run();
                  } catch (Exception e) {
                    sync.message(e.getMessage() == null ? "操作失败" : e.getMessage());
                  }
                })
            .bounds(x, y, w, 18)
            .build());
  }

  @Override
  protected void init() {
    boolean searchFocused = search != null && search.isFocused();
    int cursor = search == null ? 0 : search.getCursorPosition();
    seen = sync.state();
    entries = sync.projections();
    seenEntries = entries.toString();
    seenSelection = sync.bridge().selectedKey();
    int x = 12, w = width - 24, quarter = (w - 12) / 4;
    button("连接设置", x, 24, quarter, this::settings);
    button("邀请码 / 登录", x + quarter + 4, 24, quarter, this::signIn);
    button("刷新", x + 2 * (quarter + 4), 24, quarter, sync::refresh);
    button("关闭", x + 3 * (quarter + 4), 24, quarter, this::onClose);
    search = new EditBox(font, x, 46, w - 100, 18, Component.literal("搜索投影"));
    search.setMaxLength(128);
    search.setValue(query);
    search.setHint(Component.literal("搜索名称、作者或个人别名"));
    search.setResponder(
        value -> {
          query = value;
          page = 0;
          minecraft.execute(this::rebuildWidgets);
        });
    addRenderableWidget(search);
    if (searchFocused) {
      setInitialFocus(search);
      search.setCursorPosition(Math.min(cursor, query.length()));
    }
    button(
        currentDimension ? "当前维度" : "全部维度",
        width - 104,
        46,
        92,
        () -> {
          currentDimension = !currentDimension;
          page = 0;
          rebuildWidgets();
        });
    List<JsonObject> filtered = filtered();
    int count = pageSize();
    page = Math.max(0, Math.min(page, Math.max(0, (filtered.size() - 1) / count)));
    rows =
        filtered.subList(
            Math.min(filtered.size(), page * count), Math.min(filtered.size(), (page + 1) * count));
    for (int i = 0; i < rows.size(); i++) {
      JsonObject p = rows.get(i);
      String name = Wire.string(p, "name", "");
      button(font.plainSubstrByWidth(name, w - 8), x, 70 + i * 28, w, () -> details(p));
    }
    button("上一页", x, height - 59, 68, () -> {
      page--;
      rebuildWidgets();
    }).active = page > 0;
    button("下一页", width - 80, height - 59, 68, () -> {
      page++;
      rebuildWidgets();
    }).active = (page + 1) * count < filtered.size();
    int actions = sync.admin() ? 3 : 2;
    int actionWidth = (w - (actions - 1) * 4) / actions;
    button("导入蓝图", x, height - 37, actionWidth, this::importSchematic);
    button("素材库", x + actionWidth + 4, height - 37, actionWidth,
        () -> minecraft.gui.setScreen(new LibraryScreen(this)));
    if (sync.admin())
      button("成员与邀请", x + 2 * (actionWidth + 4), height - 37, actionWidth,
          () -> minecraft.gui.setScreen(new AdminScreen(this)));
  }

  private int pageSize() {
    return Math.max(1, (height - 133) / 28);
  }

  private List<JsonObject> filtered() {
    List<JsonObject> result = new ArrayList<>();
    String q = query.toLowerCase(Locale.ROOT);
    String dim = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    JsonObject prefs = Wire.nested(sync.state(), "preferences");
    for (JsonObject p : entries) {
      String id = Wire.string(p, "id", "");
      if (currentDimension && !dim.equals(Wire.string(p, "dimension", ""))) continue;
      String haystack =
          Wire.string(p, "name", "")
              + " "
              + Wire.string(p, "ownerName", "")
              + " "
              + Wire.string(Wire.nested(prefs, id), "alias", "");
      if (haystack.toLowerCase(Locale.ROOT).contains(q)) result.add(p);
    }
    return result;
  }

  @Override
  public void tick() {
    if (seen != sync.state() || !seenSelection.equals(sync.bridge().selectedKey())
        || (++ticks % 5 == 0 && !seenEntries.equals(sync.projections().toString()))) rebuildWidgets();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, title, width / 2, 8, 0xffe6efff);
    for (int i = 0; i < rows.size(); i++) {
      JsonObject p = rows.get(i);
      String detail =
          Wire.string(p, "syncStatus", "未同步")
              + (Wire.bool(p, "hidden") ? " · 已隐藏" : "")
              + " · "
              + Wire.string(p, "ownerName", p.has("localId") ? "本地投影" : "")
              + " · "
              + dimensionName(Wire.string(p, "dimension", ""))
              + " · "
              + p.get("origin");
      g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, 89 + i * 28, 0xffaab8c8);
    }
    g.centeredText(
        font,
        (page + 1) + " / " + Math.max(1, (filtered().size() + pageSize() - 1) / pageSize()),
        width / 2,
        height - 54,
        0xffeeeeee);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 24), 12, height - 13, 0xffb6d9c5);
  }

  private void form(
      String title,
      String hint,
      String action,
      List<FormScreen.Field> fields,
      Consumer<Map<String, String>> submit) {
    minecraft.gui.setScreen(new FormScreen(this, title, hint, action, fields, submit));
  }

  private FormScreen.Field field(String key, String label, String value, int max) {
    return new FormScreen.Field(key, label, value, max);
  }

  private void settings() {
    form(
        "同步连接设置",
        "同步服务与 Minecraft 游戏服务器分别填写",
        "保存",
        List.of(
            field("endpoint", "同步服务 HTTPS 地址", sync.config().endpoint(), 256),
            field("game", "游戏服务器地址", sync.config().gameAddress(), 256)),
        values -> {
          try {
            sync.configure(new ClientConfig(values.get("endpoint"), values.get("game")));
            minecraft.gui.setScreen(this);
          } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
          }
        });
  }

  private void signIn() {
    form(
        "正版账号登录",
        "首次加入填写邀请码；已加入成员和初始管理员可留空",
        "验证并连接",
        List.of(field("invite", "一次性邀请码", "", 128)),
        values -> sync.connect(values.get("invite")));
  }

  private void details(JsonObject p) {
    minecraft.gui.setScreen(new PlacementScreen(this, p));
  }

  void publishSelected() {
    if (minecraft.level == null) throw new IllegalArgumentException("请先进入游戏服务器");
    JsonObject data = sync.bridge().selectedTransform();
    JsonObject shared = sync.bridge().sharedFor(data);
    if (shared.has("id")) data.addProperty("name", Wire.string(shared, "name", "投影"));
    Path file = Path.of(data.get("schematic").getAsString());
    form(
        shared.has("id") ? "同步此投影的修改" : "发布此投影",
        shared.has("id") ? "更新现有共享投影；公共名称保持不变" : "保留坐标、旋转、镜像和子区域设置，向全服共享",
        "发布",
        List.of(field("name", "公共投影名称", Wire.string(data, "name", "投影"), 128)),
        values -> {
          data.addProperty("name", values.get("name"));
          sync.publish(data, file, () -> minecraft.gui.setScreen(this));
        });
  }

  void selectProjection(JsonObject row) {
    sync.bridge().selectProjection(row);
  }

  private static String dimensionName(String dimension) {
    return switch (dimension) {
      case "minecraft:overworld" -> "主世界";
      case "minecraft:the_nether" -> "下界";
      case "minecraft:the_end" -> "末地";
      default -> dimension;
    };
  }

  void requestRemoval(JsonObject row, boolean deleteLocal) {
    if (row == null) throw new IllegalArgumentException("请先选中一个投影");
    if (!sync.canRemove(row)) throw new IllegalArgumentException("只有创建者或管理员可以撤回共享投影，请先登录");
    boolean shared = row.has("id");
    minecraft.gui.setScreen(new FormScreen(
        this,
        shared ? (deleteLocal ? "取消同步并删除投影" : "取消同步，保留本地") : "删除本地投影",
        shared ? "将从所有成员的共享列表移除；" + (deleteLocal ? "同时删除本地投影，蓝图文件保留" : "自己保留可调整的本地投影")
            : "只删除本地放置，蓝图文件保留",
        deleteLocal ? "确认删除" : "确认取消同步",
        deleteLocal ? List.of(field("confirm", "输入 DELETE 确认删除", "", 6)) : List.of(),
        values -> {
          if (deleteLocal && !"DELETE".equals(values.get("confirm"))) throw new IllegalArgumentException("请输入 DELETE");
          sync.removeProjection(row, deleteLocal, () -> minecraft.gui.setScreen(this));
        }));
  }

  private void importSchematic() {
    if (minecraft.player == null || minecraft.level == null)
      throw new IllegalArgumentException("请先进入世界再导入蓝图");
    String chosen =
        TinyFileDialogs.tinyfd_openFileDialog(
            "选择 .litematic 蓝图",
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir()
                .resolve("schematics")
                .toString(),
            (PointerBuffer) null,
            null,
            false);
    if (chosen == null) return;
    importSchematic(Path.of(chosen));
  }

  void importSchematic(Path file) {
    if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
      throw new IllegalArgumentException("请选择 .litematic 蓝图");
    JsonObject data = sync.bridge().localTransform(file);
    PlacementForm.open(
        this, "导入本地投影", "放置投影", "投影名称", data,
        values -> {
          sync.bridge().placeLocal(values);
          sync.message("已导入本地投影；调整完成后点击管理列表中的名称，再发布共享");
          minecraft.gui.setScreen(null);
        });
  }

  void placementForm(String title, JsonObject p, Consumer<JsonObject> submit) {
    PlacementForm.open(this, title, "保存", "公共名称", p, submit);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
