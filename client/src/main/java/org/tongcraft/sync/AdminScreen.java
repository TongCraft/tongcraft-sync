package org.tongcraft.sync;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AdminScreen extends Screen {
  private final Screen parent;
  private final SyncClient sync = TongCraftClient.SYNC;
  private boolean invitations;
  private int page;
  private JsonObject seen;

  AdminScreen(Screen parent) {
    super(Component.literal("TongCraft · 成员与邀请"));
    this.parent = parent;
  }

  private void button(String label, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
  }

  @Override
  protected void init() {
    seen = sync.state();
    int w = width - 24, third = (w - 8) / 3;
    button(
        invitations ? "查看成员" : "查看邀请",
        12,
        32,
        third,
        () -> {
          invitations = !invitations;
          page = 0;
          rebuildWidgets();
        });
    button(
        "创建邀请码",
        16 + third,
        32,
        third,
        () ->
            minecraft.gui.setScreen(
                new FormScreen(
                    this,
                    "创建一次性邀请",
                    "邀请码有效 48 小时；创建后自动复制到剪贴板",
                    "创建",
                    List.of(
                        new FormScreen.Field("note", "备注，例如给小明", "", 128),
                        new FormScreen.Field("uuid", "绑定正版 UUID（可留空）", "", 36)),
                    values ->
                        sync.mutate(
                            "POST",
                            "/invites",
                            Wire.object(
                                "note", values.get("note"), "boundUuid", values.get("uuid")),
                            () -> minecraft.gui.setScreen(this)))));
    button("返回", 20 + 2 * third, 32, third, this::onClose);
    JsonArray entries = Wire.array(seen, invitations ? "invites" : "members");
    int count = Math.max(1, (height - 128) / 28);
    page = Math.max(0, Math.min(page, Math.max(0, (entries.size() - 1) / count)));
    for (int i = page * count; i < Math.min(entries.size(), (page + 1) * count); i++) {
      JsonObject e = entries.get(i).getAsJsonObject();
      int y = 64 + (i - page * count) * 28;
      if (invitations) {
        String label =
            Wire.string(e, "note", "")
                + " · "
                + (Wire.bool(e, "revoked")
                    ? "已撤销"
                    : e.has("used_by") && !e.get("used_by").isJsonNull()
                        ? "已使用"
                        : e.get("expires").getAsLong() < System.currentTimeMillis() ? "已过期" : "有效");
        button(font.plainSubstrByWidth(label, w - 88), 12, y, w - 84, () -> {});
        button(
            "撤销",
            width - 92,
            y,
            80,
            () -> sync.mutate("DELETE", "/invites/" + e.get("id").getAsString(), null, null));
      } else {
        boolean disabled = e.get("disabled").getAsInt() != 0;
        String id = e.get("uuid").getAsString();
        button(
            font.plainSubstrByWidth(
                Wire.string(e, "name", "") + " · " + Wire.string(e, "role", "") + " · " + id,
                w - 92),
            12,
            y,
            w - 84,
            () -> {
              minecraft.keyboardHandler.setClipboard(id);
              sync.message("正版 UUID 已复制");
            });
        if (!"admin".equals(Wire.string(e, "role", "")))
          button(
              disabled ? "恢复" : "停用",
              width - 92,
              y,
              80,
              () ->
                  sync.mutate("PATCH", "/members/" + id, Wire.object("disabled", !disabled), null));
      }
    }
    button(
        "上一页",
        12,
        height - 57,
        80,
        () -> {
          page--;
          rebuildWidgets();
        });
    button(
        "下一页",
        width - 92,
        height - 57,
        80,
        () -> {
          page++;
          rebuildWidgets();
        });
  }

  @Override
  public void tick() {
    if (!sync.admin()) {
      minecraft.gui.setScreen(parent);
      return;
    }
    if (seen != sync.state()) rebuildWidgets();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    g.centeredText(font, title, width / 2, 12, 0xffeeeeee);
    g.centeredText(font, "第 " + (page + 1) + " 页", width / 2, height - 51, 0xffeeeeee);
    g.text(font, font.plainSubstrByWidth(sync.status(), width - 24), 12, height - 24, 0xffb6d9c5);
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
