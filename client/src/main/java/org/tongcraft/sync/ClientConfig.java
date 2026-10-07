package org.tongcraft.sync;

import com.google.gson.GsonBuilder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

public record ClientConfig(String endpoint, String gameAddress) {
  private static final Path FILE =
      FabricLoader.getInstance().getConfigDir().resolve("tongcraft-sync.json");

  public static ClientConfig load() {
    try {
      return validated(
          new GsonBuilder().create().fromJson(Files.readString(FILE), ClientConfig.class));
    } catch (Exception e) {
      return new ClientConfig("", "mc.tongcraft.cn");
    }
  }

  public static ClientConfig validated(ClientConfig config) {
    if (config == null) throw new IllegalArgumentException("请填写同步服务地址和游戏服务器地址");
    URI uri = URI.create(config.endpoint.trim());
    boolean local =
        "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost())
            || "[::1]".equals(uri.getHost());
    if (uri.getHost() == null
        || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
      throw new IllegalArgumentException("同步地址必须是 HTTPS 域名（本机测试允许 HTTP），不含路径或账号信息");
    }
    if (config.gameAddress == null
        || config.gameAddress.isBlank()
        || config.gameAddress.contains("/")
        || config.gameAddress.contains(" "))
      throw new IllegalArgumentException("请填写游戏服务器地址，例如 mc.tongcraft.cn");
    return new ClientConfig(
        config.endpoint.trim().replaceAll("/+$", ""), normalizeAddress(config.gameAddress));
  }

  public static String normalizeAddress(String address) {
    return address.trim().toLowerCase(Locale.ROOT).replaceAll(":25565$", "");
  }

  public void save() throws Exception {
    Files.createDirectories(FILE.getParent());
    Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
    Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(this));
    Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
  }
}
