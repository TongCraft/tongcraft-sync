package org.tongcraft.sync;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Network/disk work is serialized off the game thread. Every result is scoped to a connection
 * generation.
 */
public final class SyncClient {
  private final Minecraft mc = Minecraft.getInstance();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "TongCraft Sync");
            t.setDaemon(true);
            return t;
          });
  private final HttpClient http =
      HttpClient.newBuilder()
          // The independent Node service speaks HTTP/1.1; avoid an h2c upgrade being
          // mistaken for a WebSocket upgrade during localhost authentication.
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final AtomicBoolean refreshPending = new AtomicBoolean();
  private final AtomicBoolean refreshDirty = new AtomicBoolean();
  private final java.util.Map<String, String> verifiedFiles = new java.util.HashMap<>();
  private final Path cache =
      FabricLoader.getInstance().getGameDir().resolve("tongcraft-sync-cache");
  private final LitematicaBridge bridge = new LitematicaBridge();
  private volatile int generation;
  private volatile String token = "";
  private volatile long expires;
  private volatile WebSocket socket;
  private volatile boolean socketConnecting;
  private volatile ClientConfig config = ClientConfig.load();
  private JsonObject state = new JsonObject();
  private String status = "尚未连接", activeAddress = "";
  private boolean connecting, needsInvitation;
  private long nextRetry, nextPoll;
  private int failures;

  public JsonObject state() {
    return state;
  }

  public String status() {
    return status;
  }

  public ClientConfig config() {
    return config;
  }

  public boolean authenticated() {
    return !token.isEmpty() && !needsInvitation;
  }

  public boolean admin() {
    return "admin".equals(Wire.string(Wire.nested(state, "member"), "role", ""));
  }

  public boolean editable(JsonObject p) {
    return admin()
        || Wire.string(p, "owner", "")
            .equals(Wire.string(Wire.nested(state, "member"), "uuid", "?"));
  }

  public LitematicaBridge bridge() {
    return bridge;
  }

  public java.util.List<JsonObject> projections() {
    return bridge.projections(state, authenticated());
  }

  public boolean canRemove(JsonObject row) {
    return !bridge.busy(LitematicaBridge.rowKey(row))
        && (!row.has("id") || (authenticated() && editable(row) && row.has("revision")));
  }

  public void removeProjection(JsonObject row, boolean deleteLocal, Runnable success) {
    if (!canRemove(row)) throw new IllegalArgumentException("请登录；只有创建者或管理员可以撤回共享投影");
    if (!row.has("id")) {
      if (!deleteLocal) return;
      bridge.finishRemoval(row, null);
      status = "已删除本地投影，蓝图文件保留";
      if (success != null) success.run();
      return;
    }
    JsonObject retained = deleteLocal ? null : bridge.captureLocal(row);
    int gen = generation;
    ClientConfig cfg = config;
    String auth = token, id = row.get("id").getAsString(), key = LitematicaBridge.rowKey(row);
    bridge.operation(key, "正在取消同步");
    status = "正在撤回共享投影…";
    worker.execute(() -> {
      boolean accepted = false;
      try {
        if (gen != generation) return;
        request(cfg, "DELETE", "/placements/" + id,
            Wire.object("revision", row.get("revision").getAsInt()), null, auth);
        accepted = true;
        mc.execute(() -> {
          if (gen != generation) return;
          bridge.finishRemoval(row, retained);
          JsonObject updated = state.deepCopy();
          var placements = Wire.array(updated, "placements");
          for (int i = placements.size() - 1; i >= 0; i--)
            if (id.equals(Wire.string(placements.get(i).getAsJsonObject(), "id", ""))) placements.remove(i);
          Wire.nested(updated, "preferences").remove(id);
          state = updated;
          status = deleteLocal ? "已取消同步并删除投影，蓝图文件保留" : "已取消同步并保留本地投影";
          if (success != null) success.run();
        });
        fetchState(gen, cfg, auth);
      } catch (Exception error) {
        boolean rejected = !accepted;
        mc.execute(() -> { if (gen == generation && rejected) bridge.operation(key, "取消同步失败"); });
        failed(gen, error, false);
      }
    });
  }

  public void message(String value) {
    status = value;
  }

  public boolean targetServer() {
    return mc.level != null
        && mc.getCurrentServer() != null
        && !config.endpoint().isEmpty()
        && ClientConfig.normalizeAddress(mc.getCurrentServer().ip).equals(config.gameAddress());
  }

  public void configure(ClientConfig value) throws Exception {
    value = ClientConfig.validated(value);
    value.save();
    disconnect();
    config = value;
    activeAddress = "";
    needsInvitation = false;
    nextRetry = 0;
    status = "设置已保存；进入对应服务器后自动同步";
  }

  public void tick() {
    if (!targetServer()) {
      if (!activeAddress.isEmpty()) {
        disconnect();
        activeAddress = "";
      }
      return;
    }
    String current = mc.getCurrentServer().ip;
    if (!current.equals(activeAddress)) {
      disconnect();
      activeAddress = current;
      needsInvitation = false;
      nextRetry = 0;
      failures = 0;
      loadCached();
    }
    long now = System.currentTimeMillis();
    if (!connecting
        && !needsInvitation
        && (token.isEmpty() || expires <= now + 60000)
        && now >= nextRetry) connect("");
    if (authenticated() && now >= nextPoll) {
      nextPoll = now + 30000;
      refresh();
    }
    bridge.reconcile(state, cache);
  }

  public void disconnect() {
    generation++;
    token = "";
    expires = 0;
    connecting = false;
    refreshPending.set(false);
    refreshDirty.set(false);
    socketConnecting = false;
    WebSocket old = socket;
    socket = null;
    if (old != null) old.abort();
    bridge.clear();
    state = new JsonObject();
    status = "已断开";
  }

  public void shutdown() {
    disconnect();
    worker.shutdownNow();
  }

  private Path snapshotPath(ClientConfig cfg, String playerId) throws Exception {
    return cache.resolve(
        Wire.hash(
                (cfg.endpoint() + "|" + playerId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + ".json");
  }

  private void loadCached() {
    int gen = generation;
    ClientConfig cfg = config;
    String id = mc.getUser().getProfileId().toString().replace("-", "");
    worker.execute(
        () -> {
          try {
            Path p = snapshotPath(cfg, id);
            if (Files.exists(p)) {
              JsonObject cached = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
              mc.execute(
                  () -> {
                    if (gen == generation) {
                      state = cached;
                      status = "显示缓存投影，正在连接";
                    }
                  });
            }
          } catch (Exception ignored) {
          }
        });
  }

  public void connect(String invite) {
    if (!targetServer()) {
      status = "请先进入设置中指定的 TongCraft 游戏服务器";
      return;
    }
    if (connecting) return;
    if (!token.isEmpty() || socket != null || socketConnecting) {
      generation++;
      refreshPending.set(false);
      refreshDirty.set(false);
    }
    WebSocket previous = socket;
    socket = null;
    socketConnecting = false;
    if (previous != null) previous.abort();
    token = "";
    connecting = true;
    needsInvitation = false;
    status = "正在验证正版账号…";
    int gen = generation;
    ClientConfig cfg = config;
    User user = mc.getUser();
    var sessionService = mc.services().sessionService();
    worker.execute(
        () -> {
          try {
            JsonObject challenge =
                request(
                    cfg,
                    "POST",
                    "/auth/challenge",
                    Wire.object("uuid", user.getProfileId().toString(), "name", user.getName()),
                    null,
                    "");
            // The Minecraft access token is only passed to Mojang's own authlib implementation.
            sessionService.joinServer(
                user.getProfileId(),
                user.getAccessToken(),
                challenge.get("serverId").getAsString());
            JsonObject auth =
                request(
                    cfg,
                    "POST",
                    "/auth/complete",
                    Wire.object(
                        "challengeId",
                        challenge.get("challengeId").getAsString(),
                        "invite",
                        invite),
                    null,
                    "");
            if (gen != generation) return;
            String newToken = auth.get("token").getAsString();
            long newExpires = auth.get("expires").getAsLong();
            mc.execute(
                () -> {
                  if (gen == generation) {
                    token = newToken;
                    expires = newExpires;
                    connecting = false;
                    failures = 0;
                    status = "已连接，正在同步投影…";
                  }
                });
            fetchState(gen, cfg, newToken);
          } catch (Exception e) {
            failed(gen, e, true);
          }
        });
  }

  private void failed(int gen, Exception e, boolean authentication) {
    mc.execute(
        () -> {
          if (gen != generation) return;
          connecting = false;
          if (e instanceof ApiException api
              && (api.code == 401 || (authentication && api.code == 403))) {
            token = "";
            expires = 0;
            needsInvitation = api.code == 403;
            if (api.code == 403) {
              bridge.clear();
              state = new JsonObject();
            }
            WebSocket old = socket;
            socket = null;
            if (old != null) old.abort();
          }
          long delay = Math.min(60000, 5000L << Math.min(4, failures++));
          nextRetry = System.currentTimeMillis() + delay;
          status = (authentication ? "登录失败：" : "同步失败：") + friendly(e);
        });
  }

  private static String friendly(Exception e) {
    String message = e.getMessage();
    if (message == null) return "网络连接失败";
    return switch (message) {
      case "Invitation required" -> "首次加入需要邀请码";
      case "Invalid, expired, or used invitation" -> "邀请码无效、已使用或已过期";
      case "Invitation belongs to another Minecraft account" -> "邀请码绑定了其他正版账号";
      case "Membership disabled" -> "管理员已停用你的成员资格";
      case "Placement changed; refresh before editing" -> "投影已被更新，请刷新后再操作";
      default -> message;
    };
  }

  public void refresh() {
    if (token.isEmpty()) return;
    refreshDirty.set(true);
    if (!refreshPending.compareAndSet(false, true)) return;
    int gen = generation;
    ClientConfig cfg = config;
    String auth = token;
    worker.execute(
        () -> {
          try {
            do {
              refreshDirty.set(false);
              if (gen != generation) return;
              fetchState(gen, cfg, auth);
            } while (gen == generation && refreshDirty.get());
          } catch (Exception e) {
            failed(gen, e, false);
          } finally {
            if (gen == generation) {
              refreshPending.set(false);
              if (refreshDirty.get()) refresh();
            }
          }
        });
  }

  private void fetchState(int gen, ClientConfig cfg, String auth) throws Exception {
    JsonObject fresh = request(cfg, "GET", "/state", null, null, auth);
    if (fresh.get("protocol").getAsInt() != 1) throw new IllegalArgumentException("同步协议版本不兼容");
    Files.createDirectories(cache);
    if (gen != generation) return;
    Path snapshot = snapshotPath(cfg, Wire.string(Wire.nested(fresh, "member"), "uuid", ""));
    // Strip management data from disk snapshots. Cache only the player's projection view.
    JsonObject saved = fresh.deepCopy();
    saved.remove("invites");
    saved.remove("members");
    Files.writeString(snapshot, Wire.GSON.toJson(saved));
    mc.execute(
        () -> {
          if (gen == generation) {
            state = fresh;
            bridge.acceptState(fresh);
            status = "已同步 " + Wire.array(fresh, "placements").size() + " 个投影";
          }
        });
    for (JsonElement el : Wire.array(fresh, "placements")) {
      if (gen != generation) return;
      JsonObject p = el.getAsJsonObject();
      String hash = Wire.string(p, "hash", "");
      if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("服务返回了无效蓝图哈希");
      Path file = cache.resolve(hash + ".litematic");
      if (Files.exists(file)) {
        String fingerprint = Files.size(file) + ":" + Files.getLastModifiedTime(file);
        if (fingerprint.equals(verifiedFiles.get(hash))) continue;
        if (Files.size(file) <= 32 * 1024 * 1024
            && Wire.hash(Files.readAllBytes(file)).equals(hash)) {
          verifiedFiles.put(hash, fingerprint);
          continue;
        }
      }
      mc.execute(
          () -> {
            if (gen == generation) status = "下载投影：" + Wire.string(p, "name", "");
          });
      byte[] bytes = download(cfg, "/schematics/" + hash, auth, 32 * 1024 * 1024);
      if (!Wire.hash(bytes).equals(hash)) throw new IllegalArgumentException("蓝图校验失败");
      Path tmp = cache.resolve(hash + ".part");
      Files.write(tmp, bytes);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      verifiedFiles.put(hash, Files.size(file) + ":" + Files.getLastModifiedTime(file));
    }
    mc.execute(
        () -> {
          if (gen == generation) {
            status = "已同步 " + Wire.array(fresh, "placements").size() + " 个投影";
            bridge.reconcile(state, cache);
          }
        });
    if (socket == null && gen == generation) openSocket(gen, cfg, auth);
  }

  private synchronized void openSocket(int gen, ClientConfig cfg, String auth) {
    if (socket != null || socketConnecting || gen != generation) return;
    socketConnecting = true;
    http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + auth)
        .connectTimeout(Duration.ofSeconds(10))
        .buildAsync(
            URI.create(cfg.endpoint().replaceFirst("^http", "ws") + "/events"),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket ws) {
                if (gen != generation) {
                  ws.abort();
                  return;
                }
                socketConnecting = false;
                socket = ws;
                ws.request(1);
              }

              @Override
              public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                if (gen == generation) refresh();
                ws.request(1);
                return null;
              }

              @Override
              public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                if (gen == generation) {
                  socket = null;
                  if (code == 4001)
                    mc.execute(
                        () -> {
                          if (gen == generation) {
                            token = "";
                            nextRetry = 0;
                            status = "会话已失效，正在重新验证";
                          }
                        });
                }
                return null;
              }

              @Override
              public void onError(WebSocket ws, Throwable error) {
                if (gen == generation) {
                  socket = null;
                  socketConnecting = false;
                }
              }
            })
        .exceptionally(
            e -> {
              if (gen == generation) socketConnecting = false;
              return null;
            });
  }

  public void mutate(String method, String path, JsonObject data, Runnable success) {
    if (!authenticated()) {
      status = "请先登录同步服务";
      return;
    }
    int gen = generation;
    ClientConfig cfg = config;
    String auth = token;
    JsonObject payload =
        data == null
            ? null
            : (path.startsWith("/placements") && !method.equals("DELETE")
                ? Wire.sharedPlacement(data)
                : data.deepCopy());
    status = "正在保存…";
    worker.execute(
        () -> {
          try {
            if (gen != generation) return;
            JsonObject result = request(cfg, method, path, payload, null, auth);
            fetchState(gen, cfg, auth);
            mc.execute(
                () -> {
                  if (gen == generation) {
                    if (result.has("code")) {
                      mc.keyboardHandler.setClipboard(result.get("code").getAsString());
                      status = "邀请码已复制到剪贴板，48 小时内单次有效";
                    }
                    if (success != null) success.run();
                  }
                });
          } catch (Exception e) {
            failed(gen, e, false);
          }
        });
  }

  public void publish(JsonObject transform, Path file, Runnable success) {
    if (!authenticated()) {
      status = "请先登录同步服务";
      return;
    }
    int gen = generation;
    ClientConfig cfg = config;
    String auth = token;
    JsonObject previous = bridge.sharedFor(transform);
    if (previous.has("id") && !editable(previous))
      throw new IllegalArgumentException("只有创建者或管理员可以更新这个共享投影");
    String localId = Wire.string(transform, "hash_code", ""), key = bridge.selectedKey();
    if (bridge.busy(key)) return;
    bridge.operation(key, previous.has("id") ? "正在同步修改" : "正在发布");
    status = "正在上传蓝图…";
    worker.execute(
        () -> {
          boolean accepted = false;
          try {
            if (gen != generation) return;
            if (Files.size(file) > 32 * 1024 * 1024)
              throw new IllegalArgumentException("蓝图不能超过 32 MiB");
            byte[] bytes = Files.readAllBytes(file);
            JsonObject upload = request(cfg, "POST", "/schematics", null, bytes, auth);
            if (gen != generation) return;
            JsonObject data = Wire.sharedPlacement(transform);
            data.addProperty("hash", upload.get("hash").getAsString());
            if (previous.has("id")) data.addProperty("revision", previous.get("revision").getAsInt());
            JsonObject published = request(cfg, previous.has("id") ? "PUT" : "POST",
                previous.has("id") ? "/placements/" + previous.get("id").getAsString() : "/placements", data, null, auth);
            if (!Wire.string(published, "id", "").matches("[a-f0-9-]{36}") || !published.has("revision"))
              throw new IllegalArgumentException("服务返回了无效的共享投影信息，请刷新列表");
            accepted = true;
            mc.execute(() -> {
              if (gen == generation) {
                bridge.bind(localId, published);
                bridge.operation(key, "");
              }
            });
            fetchState(gen, cfg, auth);
            mc.execute(
                () -> {
                  if (gen == generation && success != null) success.run();
                });
          } catch (Exception e) {
            String result = accepted ? "已发布 · 刷新失败" : "同步失败";
            mc.execute(() -> { if (gen == generation) bridge.operation(key, result); });
            failed(gen, e, false);
          }
        });
  }

  public void copyLibraryLoginLink() {
    if (!authenticated()) {
      status = "请先进入游戏服务器并登录同步服务";
      return;
    }
    int gen = generation;
    ClientConfig cfg = config;
    String auth = token;
    status = "正在生成网页登录链接…";
    worker.execute(
        () -> {
          try {
            JsonObject result = request(cfg, "POST", "/library/ticket", null, null, auth);
            String url = result.get("url").getAsString();
            mc.execute(
                () -> {
                  if (gen == generation) {
                    mc.keyboardHandler.setClipboard(url);
                    status = "网页上传链接已复制，五分钟内可使用一次";
                  }
                });
          } catch (Exception e) {
            failed(gen, e, false);
          }
        });
  }

  private JsonObject request(
      ClientConfig cfg, String method, String path, JsonObject data, byte[] raw, String auth)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(cfg.endpoint() + path)).timeout(Duration.ofSeconds(30));
    if (!auth.isEmpty()) builder.header("Authorization", "Bearer " + auth);
    byte[] bytes =
        raw != null
            ? raw
            : (data == null
                ? new byte[0]
                : Wire.GSON.toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    builder.header("Content-Type", raw != null ? "application/octet-stream" : "application/json");
    builder.method(
        method,
        bytes.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(bytes));
    HttpResponse<byte[]> response =
        http.send(builder.build(), info -> new BoundedBody(8 * 1024 * 1024));
    JsonObject json;
    try {
      json =
          JsonParser.parseString(
                  new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
    } catch (Exception e) {
      throw new ApiException(
          response.statusCode(), "同步服务返回了无效响应 (HTTP " + response.statusCode() + ")");
    }
    if (response.statusCode() / 100 != 2)
      throw new ApiException(response.statusCode(), Wire.string(json, "error", "同步请求失败"));
    return json;
  }

  private byte[] download(ClientConfig cfg, String path, String auth, int limit) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(cfg.endpoint() + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + auth)
            .build();
    HttpResponse<byte[]> response = http.send(request, info -> new BoundedBody(limit));
    if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "蓝图下载失败");
    return response.body();
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private int size;
    private Flow.Subscription subscription;

    BoundedBody(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      delegate.onSubscribe(value);
    }

    @Override
    public void onNext(java.util.List<ByteBuffer> buffers) {
      for (ByteBuffer b : buffers) {
        if (b.remaining() > limit - size) {
          subscription.cancel();
          delegate.onError(new IllegalArgumentException("响应超过大小限制"));
          return;
        }
        size += b.remaining();
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
      delegate.onError(error);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }

  private static class ApiException extends Exception {
    final int code;

    ApiException(int code, String message) {
      super(message);
      this.code = code;
    }
  }
}
