package org.tongcraft.sync;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class PlacementScreen extends Screen {
  private final SyncScreen parent;
  private JsonObject p;
  private final String key;
  private String seen = "";
  private final SyncClient sync = TongCraftClient.SYNC;
  private record Action(String label, Runnable run, boolean enabled) {}

  PlacementScreen(SyncScreen parent, JsonObject p) {
    super(Component.literal(Wire.string(p, "name", "投影操作")));
    this.parent = parent;
    this.p = p.deepCopy();
    this.key = LitematicaBridge.rowKey(p);
  }

  private Button button(String label, int x, int y, int w, Runnable action) {
    return addRenderableWidget(Button.builder(Component.literal(label), b -> {
      try { action.run(); }
      catch (Exception e) { sync.message(e.getMessage() == null ? "操作失败" : e.getMessage()); }
    }).bounds(x, y, w, 20).build());
  }

  private void toggleVisibility() {
    if (!p.has("id") || (!sync.authenticated() && p.has("localId"))) {
      sync.bridge().toggleLocal(p);
      rebuildWidgets();
    } else {
      String id = Wire.string(p, "id", "");
      JsonObject pref = Wire.nested(Wire.nested(sync.state(), "preferences"), id);
      sync.mutate("PUT", "/preferences/" + id,
          Wire.object("hidden", !Wire.bool(p, "hidden"), "alias", Wire.string(pref, "alias", "")), null);
    }
  }

  @Override
  protected void init() {
    JsonObject latest = sync.projections().stream()
        .filter(row -> LitematicaBridge.rowKey(row).equals(key)).findFirst().orElse(null);
    if (latest != null) p = latest.deepCopy();
    seen = p.toString() + sync.authenticated();
    List<Action> actions = new ArrayList<>();
    actions.add(new Action("选中并返回游戏", () -> {
      parent.selectProjection(p);
      sync.message("已选中此投影；打印机请主动开启");
      minecraft.gui.setScreen(null);
    }, true));
    actions.add(new Action(Wire.bool(p, "hidden") ? "显示投影" : "隐藏投影", this::toggleVisibility, true));
    if (p.has("localId")) {
      actions.add(new Action("编辑本地位置 / 旋转 / 镜像", () -> {
        JsonObject local = sync.bridge().captureLocal(p);
        PlacementForm.open(this, "编辑本地投影", "保存", "投影名称", local, values -> {
          sync.bridge().updateLocal(p, values);
          sync.message("本地设置已保存；发布后才会同步修改");
          minecraft.gui.setScreen(parent);
        });
      }, !sync.bridge().busy(key)));
    }
    if (!p.has("id") || sync.editable(p)) {
      actions.add(new Action(p.has("id") ? "同步此投影的修改" : "发布此投影", () -> {
        parent.selectProjection(p);
        parent.publishSelected();
      }, !sync.bridge().busy(key)));
    }
    if (p.has("id")) {
      String id = p.get("id").getAsString();
      actions.add(new Action("设置个人别名", () -> {
        JsonObject pref = Wire.nested(Wire.nested(sync.state(), "preferences"), id);
        minecraft.gui.setScreen(new FormScreen(this, "个人别名", "只改变你的显示名称；留空恢复公共名称", "保存",
            List.of(new FormScreen.Field("alias", "个人别名", Wire.string(pref, "alias", ""), 128)),
            values -> sync.mutate("PUT", "/preferences/" + id,
                Wire.object("hidden", Wire.bool(pref, "hidden"), "alias", values.get("alias")),
                () -> minecraft.gui.setScreen(parent))));
      }, sync.authenticated()));
      actions.add(new Action("复制到新的共享位置", () -> {
        JsonObject copy = publicPlacement();
        copy.remove("revision");
        parent.placementForm("创建新的共享放置实例", copy,
            data -> sync.mutate("POST", "/placements", data, () -> minecraft.gui.setScreen(parent)));
      }, sync.authenticated()));
      if (sync.editable(p)) {
        actions.add(new Action("编辑公共位置 / 旋转 / 镜像",
            () -> parent.placementForm("编辑共享投影", publicPlacement(),
                data -> sync.mutate("PUT", "/placements/" + id, data, () -> minecraft.gui.setScreen(parent))),
            !sync.bridge().busy(key)));
      }
      actions.add(new Action("取消同步（保留本地）", () -> parent.requestRemoval(p, false), sync.canRemove(p)));
    }
    actions.add(new Action(p.has("id") ? "取消同步并删除" : "删除本地投影",
        () -> parent.requestRemoval(p, true), sync.canRemove(p)));
    int w = Math.min(620, width - 32), col = (w - 8) / 2, x = (width - w) / 2;
    for (int i = 0; i < actions.size(); i++) {
      Action action = actions.get(i);
      button(action.label(), x + (i % 2) * (col + 8), 64 + (i / 2) * 24, col, action.run())
          .active = action.enabled();
    }
    button("返回", x, height - 30, w, this::onClose);
  }

  private JsonObject publicPlacement() {
    JsonObject data = p.deepCopy();
    if (data.has("publicName")) data.add("name", data.get("publicName").deepCopy());
    return data;
  }

  @Override
  public void tick() {
    JsonObject latest = sync.projections().stream()
        .filter(row -> LitematicaBridge.rowKey(row).equals(key)).findFirst().orElse(null);
    if (latest == null) { minecraft.gui.setScreen(parent); return; }
    if (!(latest.toString() + sync.authenticated()).equals(seen)) rebuildWidgets();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, font.plainSubstrByWidth(Wire.string(p, "name", "投影操作"), width - 32),
        width / 2, 12, 0xffeeeeee);
    g.centeredText(font, font.plainSubstrByWidth(Wire.string(p, "dimension", "") + " " + p.get("origin"), width - 32),
        width / 2, 32, 0xffaab8c8);
    g.centeredText(font, Wire.string(p, "syncStatus", "未同步"), width / 2, 48, 0xffb6d9c5);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 32), 16, height - 48, 0xffb6d9c5);
  }

  @Override public void onClose() { minecraft.gui.setScreen(parent); }
  @Override public boolean isPauseScreen() { return false; }
}
