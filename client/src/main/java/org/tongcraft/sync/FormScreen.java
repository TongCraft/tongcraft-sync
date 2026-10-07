package org.tongcraft.sync;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class FormScreen extends Screen {
  record Field(String key, String label, String value, int maxLength) {}

  private final Screen parent;
  private final List<Field> fields;
  private final Consumer<Map<String, String>> submit;
  private final List<EditBox> boxes = new ArrayList<>();
  private final String hint, action;
  private String error = "";

  FormScreen(
      Screen parent,
      String title,
      String hint,
      String action,
      List<Field> fields,
      Consumer<Map<String, String>> submit) {
    super(Component.literal(title));
    this.parent = parent;
    this.hint = hint;
    this.action = action;
    this.fields = fields;
    this.submit = submit;
  }

  @Override
  protected void init() {
    Map<String, String> previous = values();
    boxes.clear();
    int w = Math.min(420, width - 32), x = (width - w) / 2;
    int gap = Math.min(38, (height - 116) / Math.max(1, fields.size()));
    for (int i = 0; i < fields.size(); i++) {
      Field f = fields.get(i);
      EditBox box = new EditBox(font, x, 58 + i * gap, w, 20, Component.literal(f.label()));
      box.setMaxLength(f.maxLength());
      box.setValue(previous.getOrDefault(f.key(), f.value()));
      addRenderableWidget(box);
      boxes.add(box);
    }
    addRenderableWidget(
        Button.builder(
                Component.literal(action),
                b -> {
                  try {
                    submit.accept(values());
                  } catch (Exception e) {
                    error = e.getMessage() == null ? "操作失败" : e.getMessage();
                  }
                })
            .bounds(x, height - 30, (w - 8) / 2, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("返回"), b -> onClose())
            .bounds(x + (w + 8) / 2, height - 30, (w - 8) / 2, 20)
            .build());
    if (!boxes.isEmpty()) setInitialFocus(boxes.getFirst());
  }

  private Map<String, String> values() {
    Map<String, String> result = new HashMap<>();
    for (int i = 0; i < boxes.size(); i++) result.put(fields.get(i).key(), boxes.get(i).getValue());
    return result;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    super.extractRenderState(g, mx, my, delta);
    int w = Math.min(420, width - 32),
        x = (width - w) / 2,
        gap = Math.min(38, (height - 116) / Math.max(1, fields.size()));
    g.centeredText(font, title, width / 2, 12, 0xffeeeeee);
    g.text(font, font.plainSubstrByWidth(hint, w), x, 30, 0xffaab8c8);
    for (int i = 0; i < fields.size(); i++)
      g.text(font, fields.get(i).label(), x, 47 + i * gap, 0xffeeeeee);
    String msg = error.isEmpty() ? TongCraftClient.SYNC.status() : error;
    g.text(
        font,
        font.plainSubstrByWidth(msg, w),
        x,
        height - 48,
        error.isEmpty() ? 0xffaab8c8 : 0xffff7777);
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
