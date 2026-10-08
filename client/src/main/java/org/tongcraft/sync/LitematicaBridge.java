package org.tongcraft.sync;

import com.google.gson.*;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Only called on Minecraft's main thread. Remote-only placements are transient; published local
 * placements retain their native saved identity.
 */
public final class LitematicaBridge {
  private final Map<String, SchematicPlacement> live = new HashMap<>();
  private final Map<String, String> signatures = new HashMap<>();
  private final Map<String, String> errors = new HashMap<>();
  private final Map<String, String> operations = new HashMap<>();
  private final Map<String, Boolean> localVisibility = new HashMap<>();
  private final ProjectionLinks links = new ProjectionLinks(cacheDir().resolve("projection-links.json"));
  private final Map<String, String> localFileHashes = new HashMap<>();

  public void clear() {
    var manager = DataManager.getSchematicPlacementManager();
    for (var p : live.values()) if (!p.shouldBeSaved()) manager.removeSchematicPlacement(p);
    refreshRendering();
    live.clear();
    signatures.clear();
    errors.clear();
    operations.clear();
    localVisibility.clear();
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
      SchematicPlacement local = localBySharedId(id);
      if (local == null && dimension.equals(Wire.string(p, "dimension", ""))
          && Wire.string(p, "owner", "").equals(Wire.string(Wire.nested(state, "member"), "uuid", "?")))
        local = recoverAssociation(p);
      if (local != null && dimension.equals(Wire.string(p, "dimension", ""))) {
        wanted.add(id);
        try {
          SchematicPlacement old = live.get(id);
          if (old != null && old != local && !old.shouldBeSaved()) manager.removeSchematicPlacement(old);
          removeRemoteCopies(id, local);
          String localId = local.getHashId().toString();
          JsonObject previous = links.get(TongCraftClient.SYNC.config(), localId);
          if (!sameSettings(previous, p) || !Wire.string(previous, "hash", "").equals(Wire.string(p, "hash", ""))) {
            JsonObject updated = local.toJson();
            for (String key : List.of("origin", "rotation", "mirror", "placements", "ignore_entities"))
              if (p.has(key)) updated.add(key, p.get(key).deepCopy());
            for (var region : Wire.array(updated, "placements")) {
              JsonObject r = region.getAsJsonObject();
              r.getAsJsonObject("placement").addProperty("name", r.get("name").getAsString());
            }
            if (!Wire.string(previous, "hash", "").equals(Wire.string(p, "hash", "")))
              updated.addProperty("schematic", cache.resolve(Wire.string(p, "hash", "") + ".litematic").toString());
            local = replaceLocal(local, updated);
          }
          links.put(TongCraftClient.SYNC.config(), localId, p);
          // Apply changes to personal preferences, while preserving later native visibility edits.
          if (pref.has("hidden")) {
            boolean hidden = Wire.bool(pref, "hidden");
            if (!Objects.equals(localVisibility.get(id), hidden)) local = setVisibility(local, hidden);
            localVisibility.put(id, hidden);
          } else localVisibility.remove(id);
          live.put(id, local);
          errors.remove(id);
        } catch (Exception error) {
          errors.put(id, "加载失败：" + error.getMessage());
        }
        continue;
      }
      if (!dimension.equals(Wire.string(p, "dimension", "")) || Wire.bool(pref, "hidden")) {
        removeRemoteCopies(id, null);
        continue;
      }
      wanted.add(id);
      String signature = p.toString() + pref;
      removeRemoteCopies(id, live.get(id));
      if (signature.equals(signatures.get(id))
          && live.containsKey(id)
          && manager.getAllSchematicsPlacements().contains(live.get(id))) continue;
      if (live.containsKey(id)) manager.removeSchematicPlacement(live.remove(id));
      removeRemoteCopies(id, null);
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
        SchematicPlacement removed = live.remove(id);
        if (!removed.shouldBeSaved()) manager.removeSchematicPlacement(removed);
        refreshRendering();
        signatures.remove(id);
        errors.remove(id);
        localVisibility.remove(id);
      }
  }

  private void removeRemoteCopies(String sharedId, SchematicPlacement keep) {
    var manager = DataManager.getSchematicPlacementManager();
    for (var placement : List.copyOf(manager.getAllSchematicsPlacements())) {
      // Shared UUIDs identify our transient instances; never merge distinct saved local placements
      // just because they use the same schematic or coordinates.
      if (placement != keep && !placement.shouldBeSaved()
          && sharedId.equals(placement.getHashId().toString())) {
        manager.removeSchematicPlacement(placement);
        refreshRendering();
      }
    }
  }

  public void select(String id) {
    SchematicPlacement p = live.get(id);
    if (p != null) DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(p);
  }

  private SchematicPlacement localById(String localId) {
    return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
        .filter(p -> p.shouldBeSaved() && p.getHashId().toString().equals(localId))
        .findFirst().orElse(null);
  }

  private SchematicPlacement localBySharedId(String sharedId) {
    return localById(links.localId(TongCraftClient.SYNC.config(), sharedId));
  }

  private SchematicPlacement recoverAssociation(JsonObject shared) {
    List<SchematicPlacement> candidates = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
        .filter(p -> p.shouldBeSaved() && p.toJson() != null && sameSettings(p.toJson(), shared)
            && links.get(TongCraftClient.SYNC.config(), p.getHashId().toString()).isEmpty()).toList();
    if (candidates.size() != 1) return null;
    SchematicPlacement candidate = candidates.getFirst();
    try {
      Path file = candidate.getSchematic().getFile();
      if (Files.size(file) > 32 * 1024 * 1024) return null;
      String fingerprint = file.toAbsolutePath() + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file);
      String hash = localFileHashes.get(fingerprint);
      if (hash == null) {
        hash = Wire.hash(Files.readAllBytes(file));
        localFileHashes.put(fingerprint, hash);
      }
      if (!hash.equals(Wire.string(shared, "hash", ""))) return null;
      links.put(TongCraftClient.SYNC.config(), candidate.getHashId().toString(), shared);
      return candidate;
    } catch (Exception ignored) { return null; }
  }

  private static boolean sameSettings(JsonObject a, JsonObject b) {
    return settings(a).equals(settings(b));
  }

  private static JsonObject settings(JsonObject input) {
    JsonObject result = new JsonObject(), regions = new JsonObject();
    for (String key : List.of("origin", "rotation", "mirror"))
      if (input.has(key)) result.add(key, input.get(key).deepCopy());
    result.addProperty("ignore_entities", Wire.bool(input, "ignore_entities"));
    for (var item : Wire.array(input, "placements")) {
      JsonObject region = item.getAsJsonObject(), transform = Wire.nested(region, "placement");
      JsonObject value = new JsonObject();
      for (String key : List.of("pos", "rotation", "mirror"))
        if (transform.has(key)) value.add(key, transform.get(key).deepCopy());
      for (String key : List.of("enabled", "rendering_enabled"))
        value.addProperty(key, !transform.has(key) || Wire.bool(transform, key));
      value.addProperty("ignore_entities", Wire.bool(transform, "ignore_entities"));
      regions.add(Wire.string(region, "name", ""), value);
    }
    result.add("regions", regions);
    return result;
  }

  public static String rowKey(JsonObject row) {
    String localId = Wire.string(row, "localId", "");
    return localId.isEmpty() ? "shared:" + Wire.string(row, "id", "") : "local:" + localId;
  }

  public void operation(String key, String status) {
    if (status.isEmpty()) operations.remove(key); else operations.put(key, status);
  }

  public boolean busy(String key) {
    return operations.getOrDefault(key, "").startsWith("正在");
  }

  public void bind(String localId, JsonObject shared) {
    if (localById(localId) != null) links.put(TongCraftClient.SYNC.config(), localId, shared);
  }

  public void acceptState(JsonObject state) {
    links.acceptState(TongCraftClient.SYNC.config(), state);
    for (var entry : Wire.array(state, "placements")) {
      String id = Wire.string(entry.getAsJsonObject(), "id", "");
      String localId = links.localId(TongCraftClient.SYNC.config(), id);
      String key = localId.isEmpty() ? "shared:" + id : "local:" + localId;
      if (!busy(key)) operations.remove(key);
    }
  }

  public JsonObject sharedFor(JsonObject transform) {
    String localId = Wire.string(transform, "hash_code", "");
    JsonObject linked = links.get(TongCraftClient.SYNC.config(), localId);
    String sharedId = Wire.string(linked, "id", localId);
    for (var entry : Wire.array(TongCraftClient.SYNC.state(), "placements"))
      if (sharedId.equals(Wire.string(entry.getAsJsonObject(), "id", ""))) return entry.getAsJsonObject().deepCopy();
    return linked;
  }

  public List<JsonObject> projections(JsonObject state, boolean connected) {
    List<JsonObject> rows = new ArrayList<>();
    Set<String> represented = new HashSet<>();
    var mc = Minecraft.getInstance();
    if (mc.level != null) {
      for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
        if (!placement.shouldBeSaved()) continue;
        String localId = placement.getHashId().toString();
        JsonObject row = placement.toJson();
        if (row == null) row = Wire.object("name", placement.getName(), "origin", List.of(
            placement.getOrigin().getX(), placement.getOrigin().getY(), placement.getOrigin().getZ()));
        row.addProperty("localId", localId);
        row.addProperty("dimension", mc.level.dimension().identifier().toString());
        row.addProperty("hidden", !placement.isEnabled() || !placement.isRenderingEnabled());
        JsonObject shared = sharedFor(row);
        String status = "未同步 · 本地";
        if (shared.has("id")) {
          represented.add(shared.get("id").getAsString());
          for (String key : List.of("id", "owner", "ownerName", "revision", "hash"))
            if (shared.has(key)) row.add(key, shared.get(key).deepCopy());
          row.addProperty("publicName", Wire.string(shared, "name", "投影"));
          String alias = Wire.string(Wire.nested(Wire.nested(state, "preferences"), shared.get("id").getAsString()), "alias", "");
          row.addProperty("name", alias.isEmpty() ? row.get("publicName").getAsString() : alias);
          status = connected ? "已同步" : "已发布 · 未连接";
          if (!sameSettings(row, shared)) status += " · 有本地修改";
          if (errors.containsKey(shared.get("id").getAsString())) status += " · " + errors.get(shared.get("id").getAsString());
        }
        row.addProperty("syncStatus", operations.getOrDefault(rowKey(row), status));
        rows.add(row);
      }
    }
    JsonObject preferences = Wire.nested(state, "preferences");
    for (var entry : Wire.array(state, "placements")) {
      JsonObject row = entry.getAsJsonObject().deepCopy();
      String id = Wire.string(row, "id", "");
      if (represented.contains(id)) continue;
      JsonObject preference = Wire.nested(preferences, id);
      row.addProperty("publicName", Wire.string(row, "name", "投影"));
      String alias = Wire.string(preference, "alias", "");
      if (!alias.isEmpty()) row.addProperty("name", alias);
      row.addProperty("hidden", Wire.bool(preference, "hidden"));
      row.addProperty("syncStatus", operations.getOrDefault(rowKey(row),
          (connected ? "已同步" : "已发布 · 未连接") + " · " + loadingStatus(row, cacheDir())));
      rows.add(row);
    }
    return rows;
  }

  public void selectProjection(JsonObject row) {
    SchematicPlacement placement = row.has("localId") ? localById(row.get("localId").getAsString())
        : live.get(Wire.string(row, "id", ""));
    if (placement == null) throw new IllegalArgumentException("投影尚未在当前维度加载");
    DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
  }

  public String selectedKey() {
    var selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
    if (selected == null) return "";
    return (selected.shouldBeSaved() ? "local:" : "shared:") + selected.getHashId();
  }

  public void toggleLocal(JsonObject row) {
    SchematicPlacement local = localById(Wire.string(row, "localId", ""));
    if (local == null) throw new IllegalArgumentException("本地投影已被删除");
    setVisibility(local, local.isEnabled() && local.isRenderingEnabled());
  }

  private SchematicPlacement setVisibility(SchematicPlacement placement, boolean hidden) {
    JsonObject data = placement.toJson();
    if (data == null) throw new IllegalArgumentException("请先保存蓝图文件");
    data.addProperty("enabled", !hidden);
    data.addProperty("enable_render", !hidden);
    // Remove the still-enabled instance before adding its disabled form. Native flag changes can
    // leave touched-chunk registrations and meshes behind on multi-chunk placements in 26.2.
    return replaceLocal(placement, data);
  }

  private void refreshRendering() {
    var manager = DataManager.getSchematicPlacementManager();
    manager.setVisibleSubChunksNeedsUpdate();
    var renderer = LitematicaRenderer.getInstance().getWorldRenderer();
    if (!renderer.hasWorld()) return;
    // 26.2 can retain both filled schematic chunks and compiled meshes after native disable events.
    // Rebuild the disposable schematic world, keeping the placement objects and their settings.
    PlacementManagerDaemonHandler.INSTANCE.clearAllTasks();
    SchematicWorldHandler.INSTANCE.recreateSchematicWorld(true);
    SchematicWorldHandler.INSTANCE.recreateSchematicWorld(false);
    for (var placement : manager.getAllSchematicsPlacements())
      if (placement.isEnabled()) manager.markChunksForRebuild(placement);
  }

  private SchematicPlacement replaceLocal(SchematicPlacement old, JsonObject data) {
    SchematicPlacement replacement = SchematicPlacement.fromJson(data);
    if (replacement == null) throw new IllegalArgumentException("Litematica 无法读取蓝图");
    replacement.setShouldBeSaved(true);
    var manager = DataManager.getSchematicPlacementManager();
    boolean selected = old == manager.getSelectedSchematicPlacement();
    manager.removeSchematicPlacement(old);
    manager.addSchematicPlacement(replacement, false);
    live.replaceAll((id, placement) -> placement == old ? replacement : placement);
    refreshRendering();
    if (selected) manager.setSelectedSchematicPlacement(replacement);
    return replacement;
  }

  public void updateLocal(JsonObject row, JsonObject data) {
    SchematicPlacement local = localById(Wire.string(row, "localId", ""));
    if (local == null) throw new IllegalArgumentException("本地投影已被删除");
    replaceLocal(local, data);
  }

  public JsonObject captureLocal(JsonObject row) {
    SchematicPlacement loaded = row.has("localId") ? localById(row.get("localId").getAsString())
        : live.get(Wire.string(row, "id", ""));
    JsonObject data = loaded == null ? row.deepCopy() : loaded.toJson();
    if (data == null) throw new IllegalArgumentException("请先将蓝图保存为文件");
    if (!data.has("schematic")) data.addProperty("schematic",
        cacheDir().resolve(Wire.string(row, "hash", "") + ".litematic").toString());
    if (!Files.isRegularFile(Path.of(data.get("schematic").getAsString())))
      throw new IllegalArgumentException("蓝图尚未下载，请加载后再保留本地投影");
    if (loaded == null && SchematicPlacement.fromJson(data) == null)
      throw new IllegalArgumentException("无法读取蓝图，请成功加载后再保留本地投影");
    data.addProperty("dimension", Wire.string(row, "dimension", ""));
    return data;
  }

  public void finishRemoval(JsonObject row, JsonObject retained) {
    var manager = DataManager.getSchematicPlacementManager();
    String id = Wire.string(row, "id", "");
    SchematicPlacement local = localById(Wire.string(row, "localId", ""));
    SchematicPlacement shared = live.remove(id);
    if (shared != null && shared != local && !shared.shouldBeSaved()) manager.removeSchematicPlacement(shared);
    if (!id.isEmpty()) links.remove(TongCraftClient.SYNC.config(), id);
    signatures.remove(id);
    errors.remove(id);
    localVisibility.remove(id);
    if (retained == null) {
      if (local != null) manager.removeSchematicPlacement(local);
    } else if (local == null) {
      retained.remove("hash_code");
      placeLocal(retained);
    } else {
      JsonObject latest = local.toJson();
      if (latest != null) retained = latest;
      retained.addProperty("locked", false);
      retained.addProperty("locked_coords", 0);
      SchematicPlacement kept = replaceLocal(local, retained);
      manager.setSelectedSchematicPlacement(kept);
    }
    operation(rowKey(row), "");
    refreshRendering();
  }

  public JsonObject localTransform(Path file) {
    return localTransform(file, null);
  }

  public JsonObject localTransform(Path file, String name) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null)
      throw new IllegalArgumentException("请先进入世界再放置投影");
    LitematicaSchematic schematic =
        LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());
    if (schematic == null) throw new IllegalArgumentException("Litematica 无法读取这份蓝图");
    if (name == null) name = schematic.getMetadata().getName();
    if (name == null || name.isBlank()) name = file.getFileName().toString();
    SchematicPlacement placement =
        SchematicPlacement.createFor(schematic, mc.player.blockPosition(), name, true, true);
    JsonObject data = placement.toJson();
    if (data == null) throw new IllegalArgumentException("蓝图没有文件路径");
    data.addProperty("dimension", mc.level.dimension().identifier().toString());
    return data;
  }

  public void placeLocal(JsonObject data) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null)
      throw new IllegalArgumentException("请先进入世界再放置投影");
    if (!mc.level.dimension().identifier().toString().equals(Wire.string(data, "dimension", "")))
      throw new IllegalArgumentException("维度已改变，请重新选择放置位置");
    JsonObject local = data.deepCopy();
    local.addProperty("enabled", true);
    local.addProperty("enable_render", true);
    local.addProperty("locked", false);
    SchematicPlacement placement = SchematicPlacement.fromJson(local);
    if (placement == null) throw new IllegalArgumentException("Litematica 无法放置这份蓝图");
    placement.setShouldBeSaved(true);
    var manager = DataManager.getSchematicPlacementManager();
    SchematicHolder.getInstance().addSchematic(placement.getSchematic(), true);
    manager.addSchematicPlacement(placement, true);
    manager.setSelectedSchematicPlacement(placement);
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
