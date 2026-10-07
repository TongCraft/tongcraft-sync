package org.tongcraft.sync;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class PlacementScreen extends Screen {
  private final SyncScreen parent;
  private final JsonObject p;
  private final SyncClient sync = TongCraftClient.SYNC;

  PlacementScreen(SyncScreen parent, JsonObject p) {
    super(Component.literal(Wire.string(p, "name", "投影详情")));
    this.parent = parent;
    this.p = p.deepCopy();
  }

  private void button(String label, int y, Runnable action) {
    int w = Math.min(360, width - 32);
    addRenderableWidget(
        Button.builder(
                Component.literal(label),
                b -> {
                  try {
                    action.run();
                  } catch (Exception e) {
                    sync.message(e.getMessage());
                  }
                })
            .bounds((width - w) / 2, y, w, 20)
            .build());
  }

  @Override
  protected void init() {
    String id = p.get("id").getAsString();
    button(
        "设置个人别名（留空恢复公共名称）",
        62,
        () -> {
          JsonObject pref = Wire.nested(Wire.nested(sync.state(), "preferences"), id);
          minecraft.gui.setScreen(
              new FormScreen(
                  this,
                  "个人别名",
                  "只改变你的显示名称",
                  "保存",
                  List.of(
                      new FormScreen.Field("alias", "个人别名", Wire.string(pref, "alias", ""), 128)),
                  values ->
                      sync.mutate(
                          "PUT",
                          "/preferences/" + id,
                          Wire.object(
                              "hidden", Wire.bool(pref, "hidden"), "alias", values.get("alias")),
                          () -> minecraft.gui.setScreen(parent))));
        });
    button(
        "复制投影，在新位置放置",
        88,
        () -> {
          JsonObject copy = p.deepCopy();
          copy.remove("revision");
          parent.placementForm(
              "创建新的共享放置实例",
              copy,
              data ->
                  sync.mutate("POST", "/placements", data, () -> minecraft.gui.setScreen(parent)));
        });
    if (sync.editable(p)) {
      button(
          "编辑公共坐标 / 旋转 / 镜像",
          114,
          () ->
              parent.placementForm(
                  "编辑共享投影",
                  p,
                  data ->
                      sync.mutate(
                          "PUT",
                          "/placements/" + id,
                          data,
                          () -> minecraft.gui.setScreen(parent))));
      button(
          "从当前选中投影提交修改（含子区域）",
          140,
          () -> {
            JsonObject selected = sync.bridge().selectedTransform();
            selected.addProperty("hash", p.get("hash").getAsString());
            selected.addProperty("revision", p.get("revision").getAsInt());
            selected.addProperty("name", p.get("name").getAsString());
            if (!id.equals(Wire.string(selected, "hash_code", "")))
              throw new IllegalArgumentException("请先在管理列表选中此共享投影，解锁编辑后再提交");
            sync.mutate(
                "PUT", "/placements/" + id, selected, () -> minecraft.gui.setScreen(parent));
          });
      button(
          "删除共享投影…",
          166,
          () ->
              minecraft.gui.setScreen(
                  new FormScreen(
                      this,
                      "删除共享投影",
                      "将从所有成员的投影列表移除，输入 DELETE 确认",
                      "删除",
                      List.of(new FormScreen.Field("confirm", "确认删除", "", 6)),
                      values -> {
                        if (!"DELETE".equals(values.get("confirm")))
                          throw new IllegalArgumentException("请输入 DELETE");
                        sync.mutate(
                            "DELETE",
                            "/placements/" + id,
                            Wire.object("revision", p.get("revision").getAsInt()),
                            () -> minecraft.gui.setScreen(parent));
                      })));
    }
    button("返回", height - 30, this::onClose);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, title, width / 2, 12, 0xffeeeeee);
    g.centeredText(
        font,
        font.plainSubstrByWidth(
            Wire.string(p, "dimension", "") + " " + p.get("origin"), width - 32),
        width / 2,
        32,
        0xffaab8c8);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 32), 16, height - 48, 0xffb6d9c5);
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
