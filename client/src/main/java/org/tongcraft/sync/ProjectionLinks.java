package org.tongcraft.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Persists associations with native Litematica placement UUIDs, scoped to the sync service. */
final class ProjectionLinks {
  private final Path file;
  private JsonObject scopes = new JsonObject();

  ProjectionLinks(Path file) {
    this.file = file;
    try {
      if (Files.exists(file)) scopes = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger("TongCraft Sync").warn("Cannot read projection associations", error);
    }
  }

  private String scope(ClientConfig config) {
    return config.endpoint() + "|" + config.gameAddress();
  }

  JsonObject get(ClientConfig config, String localId) {
    return Wire.nested(Wire.nested(scopes, scope(config)), localId).deepCopy();
  }

  String localId(ClientConfig config, String sharedId) {
    for (var entry : Wire.nested(scopes, scope(config)).entrySet())
      if (sharedId.equals(Wire.string(entry.getValue().getAsJsonObject(), "id", "")))
        return entry.getKey();
    return "";
  }

  void put(ClientConfig config, String localId, JsonObject shared) {
    if (localId.isEmpty() || shared.equals(get(config, localId))) return;
    String scope = scope(config);
    if (!scopes.has(scope)) scopes.add(scope, new JsonObject());
    scopes.getAsJsonObject(scope).add(localId, shared.deepCopy());
    save();
  }

  void remove(ClientConfig config, String sharedId) {
    JsonObject links = Wire.nested(scopes, scope(config));
    boolean changed = links.entrySet().removeIf(entry ->
        sharedId.equals(Wire.string(entry.getValue().getAsJsonObject(), "id", "")));
    if (changed) save();
  }

  void acceptState(ClientConfig config, JsonObject state) {
    Set<String> present = new HashSet<>();
    for (var placement : Wire.array(state, "placements"))
      present.add(Wire.string(placement.getAsJsonObject(), "id", ""));
    JsonObject links = Wire.nested(scopes, scope(config));
    boolean changed = links.entrySet().removeIf(entry ->
        !present.contains(Wire.string(entry.getValue().getAsJsonObject(), "id", "")));
    if (changed) save();
  }

  private void save() {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, Wire.GSON.toJson(scopes));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger("TongCraft Sync").warn("Cannot save projection associations", error);
      TongCraftClient.SYNC.message("投影同步关系保存失败，请检查目录写入权限");
    }
  }
}
