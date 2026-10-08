package org.tongcraft.sync;

import com.google.gson.*;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
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

  public SyncScreen(Screen parent) {
    super(Component.literal("TongCraft · 共享投影"));
    this.parent = parent;
  }

  private void button(String label, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(
                Component.literal(label),
                b -> {
                  try {
                    action.run();
                  } catch (Exception e) {
                    sync.message(e.getMessage() == null ? "操作失败" : e.getMessage());
                  }
                })
            .bounds(x, y, w, 20)
            .build());
  }

  @Override
  protected void init() {
    boolean searchFocused = search != null && search.isFocused();
    int cursor = search == null ? 0 : search.getCursorPosition();
    seen = sync.state();
    int x = 12, w = width - 24, quarter = (w - 12) / 4;
    button("连接设置", x, 30, quarter, this::settings);
    button("邀请码 / 登录", x + quarter + 4, 30, quarter, this::signIn);
    button("刷新", x + 2 * (quarter + 4), 30, quarter, sync::refresh);
    button("关闭", x + 3 * (quarter + 4), 30, quarter, this::onClose);
    search = new EditBox(font, x, 60, w - 100, 20, Component.literal("搜索投影"));
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
        60,
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
    JsonObject preferences = Wire.nested(seen, "preferences");
    for (int i = 0; i < rows.size(); i++) {
      JsonObject p = rows.get(i);
      String id = Wire.string(p, "id", "");
      JsonObject pref = Wire.nested(preferences, id);
      String name = Wire.string(pref, "alias", "");
      if (name.isEmpty()) name = Wire.string(p, "name", "");
      int y = 92 + i * 36;
      button(font.plainSubstrByWidth(name, w - 144), x, y, w - 140, () -> details(p));
      button(
          Wire.bool(pref, "hidden") ? "显示" : "隐藏",
          width - 144,
          y,
          60,
          () ->
              sync.mutate(
                  "PUT",
                  "/preferences/" + id,
                  Wire.object(
                      "hidden",
                      !Wire.bool(pref, "hidden"),
                      "alias",
                      Wire.string(pref, "alias", "")),
                  null));
      button(
          "选中",
          width - 80,
          y,
          68,
          () -> {
            sync.bridge().select(id);
            sync.message("已选中已加载的投影；打印机请主动开启");
          });
    }
    button(
        "上一页",
        x,
        height - 83,
        68,
        () -> {
          page--;
          rebuildWidgets();
        });
    button(
        "下一页",
        width - 80,
        height - 83,
        68,
        () -> {
          page++;
          rebuildWidgets();
        });
    int actions = sync.admin() ? 4 : 3;
    int actionWidth = (w - (actions - 1) * 4) / actions;
    button("发布选中投影", x, height - 55, actionWidth, this::publishSelected);
    button("导入蓝图", x + actionWidth + 4, height - 55, actionWidth, this::importSchematic);
    button("素材库", x + 2 * (actionWidth + 4), height - 55, actionWidth,
        () -> minecraft.gui.setScreen(new LibraryScreen(this)));
    if (sync.admin())
      button("成员与邀请", x + 3 * (actionWidth + 4), height - 55, actionWidth,
          () -> minecraft.gui.setScreen(new AdminScreen(this)));
  }

  private int pageSize() {
    return Math.max(1, (height - 183) / 36);
  }

  private List<JsonObject> filtered() {
    List<JsonObject> result = new ArrayList<>();
    String q = query.toLowerCase(Locale.ROOT);
    String dim = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    JsonObject prefs = Wire.nested(sync.state(), "preferences");
    for (var e : Wire.array(sync.state(), "placements")) {
      JsonObject p = e.getAsJsonObject();
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
    if (seen != sync.state()) rebuildWidgets();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, title, width / 2, 12, 0xffe6efff);
    for (int i = 0; i < rows.size(); i++) {
      JsonObject p = rows.get(i);
      String detail =
          Wire.string(p, "ownerName", "")
              + " · "
              + Wire.string(p, "dimension", "")
              + " · "
              + p.get("origin")
              + " · "
              + sync.bridge().loadingStatus(p, sync.bridge().cacheDir());
      g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, 115 + i * 36, 0xffaab8c8);
    }
    g.centeredText(
        font,
        (page + 1) + " / " + Math.max(1, (filtered().size() + pageSize() - 1) / pageSize()),
        width / 2,
        height - 77,
        0xffeeeeee);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 24), 12, height - 22, 0xffb6d9c5);
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

  private void publishSelected() {
    if (minecraft.level == null) throw new IllegalArgumentException("请先进入游戏服务器");
    JsonObject data = sync.bridge().selectedTransform();
    Path file = Path.of(data.get("schematic").getAsString());
    form(
        "发布选中投影",
        "保留坐标、旋转、镜像和子区域设置，向全服共享",
        "发布",
        List.of(field("name", "公共投影名称", Wire.string(data, "name", "投影"), 128)),
        values -> {
          data.addProperty("name", values.get("name"));
          sync.publish(data, file, () -> minecraft.gui.setScreen(this));
        });
  }

  private void importSchematic() {
    if (!sync.authenticated() || minecraft.player == null)
      throw new IllegalArgumentException("请先进入服务器并登录同步服务");
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
    Path file = Path.of(chosen);
    if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
      throw new IllegalArgumentException("请选择 .litematic 蓝图");
    LitematicaSchematic schematic =
        LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());
    if (schematic == null) throw new IllegalArgumentException("无法读取这份蓝图");
    SchematicPlacement placement =
        SchematicPlacement.createTemporary(schematic, minecraft.player.blockPosition());
    JsonObject data = placement.toJson();
    if (data == null) throw new IllegalArgumentException("蓝图没有文件路径");
    data.addProperty("name", schematic.getMetadata().getName());
    data.addProperty("dimension", minecraft.level.dimension().identifier().toString());
    placementForm(
        "放置并共享蓝图", data, values -> sync.publish(values, file, () -> minecraft.gui.setScreen(this)));
  }

  void placementForm(String title, JsonObject p, Consumer<JsonObject> submit) {
    JsonArray pos = p.getAsJsonArray("origin");
    List<FormScreen.Field> fields = new ArrayList<>();
    if (!p.has("revision")) fields.add(field("name", "公共名称", Wire.string(p, "name", "投影"), 128));
    fields.add(field("xyz", "原点 X Y Z", pos.get(0) + " " + pos.get(1) + " " + pos.get(2), 64));
    fields.add(field("rotation", "旋转角度", rotationDegrees(Wire.string(p, "rotation", "NONE")), 3));
    fields.add(field("mirror", "镜像", Wire.string(p, "mirror", "NONE"), 32));
    form(
        title,
        "旋转：0 / 90 / 180 / 270；镜像：NONE / LEFT_RIGHT / FRONT_BACK",
        "保存",
        fields,
        values -> {
          JsonObject out = p.deepCopy();
          if (values.containsKey("name")) out.addProperty("name", values.get("name"));
          String[] xyz = values.get("xyz").trim().split("\\s+");
          if (xyz.length != 3) throw new IllegalArgumentException("请输入三个整数坐标");
          JsonArray origin = new JsonArray();
          for (String s : xyz) origin.add(Integer.parseInt(s));
          out.add("origin", origin);
          out.addProperty(
              "rotation",
              switch (values.get("rotation")) {
                case "0" -> "NONE";
                case "90" -> "CLOCKWISE_90";
                case "180" -> "CLOCKWISE_180";
                case "270" -> "COUNTERCLOCKWISE_90";
                default -> throw new IllegalArgumentException("旋转角度必须是 0、90、180 或 270");
              });
          String mirror = values.get("mirror").toUpperCase(Locale.ROOT);
          if (!List.of("NONE", "LEFT_RIGHT", "FRONT_BACK").contains(mirror))
            throw new IllegalArgumentException("镜像值无效");
          out.addProperty("mirror", mirror);
          submit.accept(out);
        });
  }

  private static String rotationDegrees(String rot) {
    return switch (rot) {
      case "CLOCKWISE_90" -> "90";
      case "CLOCKWISE_180" -> "180";
      case "COUNTERCLOCKWISE_90" -> "270";
      default -> "0";
    };
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
