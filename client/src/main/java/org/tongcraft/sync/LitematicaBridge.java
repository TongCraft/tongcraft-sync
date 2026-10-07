package org.tongcraft.sync;

import com.google.gson.*;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Only called on Minecraft's main thread. Shared placements never enter Litematica's saved local
 * placements.
 */
public final class LitematicaBridge {
  private final Map<String, SchematicPlacement> live = new HashMap<>();
  private final Map<String, String> signatures = new HashMap<>();
  private final Map<String, String> errors = new HashMap<>();

  public void clear() {
    var manager = DataManager.getSchematicPlacementManager();
    for (var p : live.values()) manager.removeSchematicPlacement(p);
    live.clear();
    signatures.clear();
    errors.clear();
  }

  public String loadingStatus(JsonObject p, Path cache) {
    String id = Wire.string(p, "id", "");
    if (errors.containsKey(id)) return errors.get(id);
    if (live.containsKey(id)) return "已加载";
    if (!Files.exists(cache.resolve(Wire.string(p, "hash", "") + ".litematic"))) return "等待下载";
    return "已缓存";
  }

  public void reconcile(JsonObject state, Path cache) {
    var mc = Minecraft.getInstance();
    if (mc.level == null) return;
    var manager = DataManager.getSchematicPlacementManager();
    String dimension = mc.level.dimension().identifier().toString();
    JsonObject preferences = Wire.nested(state, "preferences");
    Set<String> wanted = new HashSet<>();
    for (var el : Wire.array(state, "placements")) {
      JsonObject p = el.getAsJsonObject();
      String id = Wire.string(p, "id", "");
      JsonObject pref = Wire.nested(preferences, id);
      if (!dimension.equals(Wire.string(p, "dimension", "")) || Wire.bool(pref, "hidden")) continue;
      wanted.add(id);
      String signature = p.toString() + pref;
      if (signature.equals(signatures.get(id))
          && live.containsKey(id)
          && manager.getAllSchematicsPlacements().contains(live.get(id))) continue;
      if (live.containsKey(id)) manager.removeSchematicPlacement(live.remove(id));
      Path file = cache.resolve(Wire.string(p, "hash", "") + ".litematic");
      if (!Files.exists(file)) continue;
      if (signature.equals(signatures.get(id)) && errors.containsKey(id)) continue;
      try {
        JsonObject nativeJson = p.deepCopy();
        nativeJson.addProperty("schematic", file.toAbsolutePath().toString());
        String alias = Wire.string(pref, "alias", "");
        nativeJson.addProperty("name", alias.isEmpty() ? Wire.string(p, "name", "") : alias);
        nativeJson.addProperty("hash_code", id);
        nativeJson.addProperty("enabled", true);
        nativeJson.addProperty("enable_render", true);
        nativeJson.addProperty("locked", true);
        for (var region : Wire.array(nativeJson, "placements")) {
          JsonObject r = region.getAsJsonObject();
          r.getAsJsonObject("placement").addProperty("name", r.get("name").getAsString());
        }
        SchematicPlacement placement = SchematicPlacement.fromJson(nativeJson);
        if (placement == null) throw new IllegalArgumentException("Litematica 无法读取蓝图");
        placement.setShouldBeSaved(false);
        manager.addSchematicPlacement(placement, false);
        live.put(id, placement);
        errors.remove(id);
      } catch (Exception e) {
        errors.put(id, "加载失败：" + e.getMessage());
      }
      signatures.put(id, signature);
    }
    for (String id : new HashSet<>(live.keySet()))
      if (!wanted.contains(id)) {
        manager.removeSchematicPlacement(live.remove(id));
        signatures.remove(id);
        errors.remove(id);
      }
  }

  public void select(String id) {
    SchematicPlacement p = live.get(id);
    if (p != null) DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(p);
  }

  public JsonObject selectedTransform() {
    SchematicPlacement p =
        DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
    if (p == null) throw new IllegalArgumentException("请先在 Litematica 中选中一个投影");
    JsonObject obj = p.toJson();
    if (obj == null) throw new IllegalArgumentException("请先把蓝图保存为 .litematic 文件");
    obj.addProperty("dimension", Minecraft.getInstance().level.dimension().identifier().toString());
    return obj;
  }

  public Path cacheDir() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getGameDir()
        .resolve("tongcraft-sync-cache");
  }
}
