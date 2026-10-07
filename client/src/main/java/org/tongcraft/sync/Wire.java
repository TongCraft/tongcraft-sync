package org.tongcraft.sync;

import com.google.gson.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Wire {
  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public static JsonObject object(Object... pairs) {
    JsonObject obj = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2)
      obj.add(pairs[i].toString(), GSON.toJsonTree(pairs[i + 1]));
    return obj;
  }

  public static String string(JsonObject obj, String key, String fallback) {
    return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
  }

  public static boolean bool(JsonObject obj, String key) {
    return obj.has(key) && obj.get(key).getAsBoolean();
  }

  public static JsonArray array(JsonObject obj, String key) {
    return obj.has(key) ? obj.getAsJsonArray(key) : new JsonArray();
  }

  public static JsonObject nested(JsonObject obj, String key) {
    return obj.has(key) ? obj.getAsJsonObject(key) : new JsonObject();
  }

  public static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  public static JsonObject sharedPlacement(JsonObject input) {
    JsonObject result = new JsonObject();
    for (String key :
        new String[] {
          "name",
          "hash",
          "dimension",
          "origin",
          "rotation",
          "mirror",
          "placements",
          "ignore_entities",
          "revision"
        }) if (input.has(key)) result.add(key, input.get(key).deepCopy());
    return result;
  }

  private Wire() {}
}
