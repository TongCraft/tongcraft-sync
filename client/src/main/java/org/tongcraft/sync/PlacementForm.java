package org.tongcraft.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

final class PlacementForm {
  private PlacementForm() {}

  static void open(
      Screen parent,
      String title,
      String action,
      String nameLabel,
      JsonObject placement,
      Consumer<JsonObject> submit) {
    JsonArray pos = placement.getAsJsonArray("origin");
    List<FormScreen.Field> fields = new ArrayList<>();
    if (!placement.has("revision"))
      fields.add(new FormScreen.Field("name", nameLabel, Wire.string(placement, "name", "投影"), 128));
    fields.add(new FormScreen.Field("xyz", "原点 X Y Z", pos.get(0) + " " + pos.get(1) + " " + pos.get(2), 64));
    fields.add(new FormScreen.Field("rotation", "旋转角度", rotationDegrees(Wire.string(placement, "rotation", "NONE")), 3));
    fields.add(new FormScreen.Field("mirror", "镜像", Wire.string(placement, "mirror", "NONE"), 32));
    Minecraft.getInstance().gui.setScreen(
        new FormScreen(
            parent,
            title,
            "旋转：0 / 90 / 180 / 270；镜像：NONE / LEFT_RIGHT / FRONT_BACK",
            action,
            fields,
            values -> {
              JsonObject out = placement.deepCopy();
              if (values.containsKey("name")) {
                String name = values.get("name").trim();
                if (name.isEmpty()) throw new IllegalArgumentException("请填写投影名称");
                out.addProperty("name", name);
              }
              String[] xyz = values.get("xyz").trim().split("\\s+");
              if (xyz.length != 3) throw new IllegalArgumentException("请输入三个整数坐标");
              JsonArray origin = new JsonArray();
              try {
                for (String coordinate : xyz) origin.add(Integer.parseInt(coordinate));
              } catch (NumberFormatException error) {
                throw new IllegalArgumentException("坐标必须是有效整数");
              }
              out.add("origin", origin);
              out.addProperty(
                  "rotation",
                  switch (values.get("rotation").trim()) {
                    case "0" -> "NONE";
                    case "90" -> "CLOCKWISE_90";
                    case "180" -> "CLOCKWISE_180";
                    case "270" -> "COUNTERCLOCKWISE_90";
                    default -> throw new IllegalArgumentException("旋转角度必须是 0、90、180 或 270");
                  });
              String mirror = values.get("mirror").trim().toUpperCase(Locale.ROOT);
              if (!List.of("NONE", "LEFT_RIGHT", "FRONT_BACK").contains(mirror))
                throw new IllegalArgumentException("镜像值无效");
              out.addProperty("mirror", mirror);
              submit.accept(out);
            }));
  }

  private static String rotationDegrees(String rotation) {
    return switch (rotation) {
      case "CLOCKWISE_90" -> "90";
      case "CLOCKWISE_180" -> "180";
      case "COUNTERCLOCKWISE_90" -> "270";
      default -> "0";
    };
  }
}
