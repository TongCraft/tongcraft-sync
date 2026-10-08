package org.tongcraft.sync;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.aleksilassila.litematica.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Runs in a real offline development client. It does not bypass production authentication. */
public final class SyncGameTest implements FabricClientGameTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static CompoundTag vector(int x, int y, int z) {
    CompoundTag tag = new CompoundTag();
    tag.putInt("x", x);
    tag.putInt("y", y);
    tag.putInt("z", z);
    return tag;
  }

  private static Path schematic(Path dir) throws Exception {
    return schematic(dir, 1, 1, 1);
  }

  private static Path schematic(Path dir, int sx, int sy, int sz) throws Exception {
    CompoundTag root = new CompoundTag(),
        region = new CompoundTag(),
        regions = new CompoundTag(),
        meta = new CompoundTag(),
        stone = new CompoundTag();
    root.putInt("Version", 7);
    root.putInt("SubVersion", 1);
    root.putInt(
        "MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
    meta.putString("Name", "Integration test");
    meta.putInt("RegionCount", 1);
    meta.put("EnclosingSize", vector(sx, sy, sz));
    root.put("Metadata", meta);
    region.put("Position", vector(0, 0, 0));
    region.put("Size", vector(sx, sy, sz));
    stone.putString("Name", "minecraft:stone");
    ListTag palette = new ListTag();
    CompoundTag air = new CompoundTag();
    air.putString("Name", "minecraft:air");
    palette.add(air);
    palette.add(stone);
    region.put("BlockStatePalette", palette);
    long[] states = new long[(sx * sy * sz * 2 + 63) / 64];
    Arrays.fill(states, sx * sy * sz == 1 ? 1L : 0x5555555555555555L);
    region.putLongArray("BlockStates", states);
    regions.put("Main", region);
    root.put("Regions", regions);
    Files.createDirectories(dir);
    Path file = dir.resolve("fixture.litematic");
    NbtIo.writeCompressed(root, file);
    return file;
  }

  private static void inject(SyncClient client, String field, Object value) throws Exception {
    var member = SyncClient.class.getDeclaredField(field);
    member.setAccessible(true);
    member.set(client, value);
  }

  private static void networkTest(ClientGameTestContext context) throws Exception {
    JsonObject snapshot = context.computeOnClient(mc -> TongCraftClient.SYNC.state().deepCopy());
    snapshot.addProperty("protocol", 1);
    String hash =
        snapshot.getAsJsonArray("placements").get(0).getAsJsonObject().get("hash").getAsString();
    byte[] schematic =
        context.computeOnClient(
            mc ->
                Files.readAllBytes(
                    mc.gameDirectory.toPath().resolve("bridge-test").resolve(hash + ".litematic")));
    String credential = "x".repeat(43);
    AtomicBoolean hold = new AtomicBoolean();
    CountDownLatch requested = new CountDownLatch(1),
        release = new CountDownLatch(1),
        responded = new CountDownLatch(1);
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    fixture.setExecutor(executor);
    fixture.createContext(
        "/",
        exchange -> {
          try {
            if (!("Bearer " + credential)
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/events")) {
              exchange.sendResponseHeaders(404, -1);
              return;
            }
            byte[] bytes =
                path.equals("/state")
                    ? Wire.GSON.toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : schematic;
            if (path.equals("/state") && hold.get()) {
              requested.countDown();
              release.await();
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            if (hold.get()) responded.countDown();
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            exchange.close();
          }
        });
    fixture.start();
    try {
      context.runOnClient(
          mc -> {
            TongCraftClient.SYNC.disconnect();
            Files.deleteIfExists(
                TongCraftClient.SYNC.bridge().cacheDir().resolve(hash + ".litematic"));
            inject(
                TongCraftClient.SYNC,
                "config",
                new ClientConfig(
                    "http://127.0.0.1:" + fixture.getAddress().getPort(), "fixture.invalid"));
            inject(TongCraftClient.SYNC, "token", credential);
            inject(TongCraftClient.SYNC, "expires", System.currentTimeMillis() + 600000);
            TongCraftClient.SYNC.refresh();
          });
      context.waitFor(
          mc ->
              Files.exists(TongCraftClient.SYNC.bridge().cacheDir().resolve(hash + ".litematic"))
                  && !DataManager.getSchematicPlacementManager()
                      .getAllSchematicsPlacements()
                      .isEmpty(),
          400);
      context.runOnClient(
          mc ->
              check(
                  Wire.array(TongCraftClient.SYNC.state(), "placements").size() == 1,
                  "HTTP state not applied"));
      hold.set(true);
      context.runOnClient(mc -> TongCraftClient.SYNC.refresh());
      context.waitFor(mc -> requested.getCount() == 0, 200);
      context.runOnClient(mc -> TongCraftClient.SYNC.disconnect());
      release.countDown();
      context.waitFor(mc -> responded.getCount() == 0, 200);
      context.waitTicks(5);
      context.runOnClient(
          mc -> {
            check(
                Wire.array(TongCraftClient.SYNC.state(), "placements").isEmpty(),
                "Late response restored state after disconnect");
            check(
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty(),
                "Late response restored projections after disconnect");
          });
    } finally {
      release.countDown();
      fixture.stop(0);
      executor.close();
    }
  }

  private static void libraryTest(ClientGameTestContext context) throws Exception {
    Path file = context.computeOnClient(mc -> schematic(mc.gameDirectory.toPath().resolve("library-test")));
    byte[] bytes = Files.readAllBytes(file);
    String id = "00000000-0000-4000-8000-000000000001";
    String hash = Wire.hash(bytes);
    JsonObject catalogue = Wire.object("total", 1, "items", List.of(Wire.object(
        "id", id, "title", "素材库测试石块", "description", "本地放置测试",
        "sha256", hash, "size", bytes.length, "blocks", 1,
        "owner", Wire.object("name", "Test"))));
    AtomicBoolean corrupt = new AtomicBoolean(), hold = new AtomicBoolean(), uploaded = new AtomicBoolean();
    CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    fixture.setExecutor(executor);
    fixture.createContext("/", exchange -> {
      try {
        if (!exchange.getRequestMethod().equals("GET")) {
          uploaded.set(true);
          exchange.sendResponseHeaders(405, -1);
          return;
        }
        boolean download = exchange.getRequestURI().getPath().endsWith("/file");
        byte[] body = download ? (corrupt.get() ? new byte[] {1, 2, 3} : bytes)
            : Wire.GSON.toJson(catalogue).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (download && hold.get()) {
          requested.countDown();
          release.await();
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } catch (Exception ignored) {
        // Closing the screen intentionally cancels the pending download.
      } finally { exchange.close(); }
    });
    fixture.start();
    String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
    try {
      context.runOnClient(mc -> {
        TongCraftClient.SYNC.disconnect();
        mc.gui.setScreen(new LibraryScreen(null, base));
      });
      context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen screen && screen.children().stream()
          .anyMatch(child -> child instanceof Button button && button.getMessage().getString().equals("放置投影")), 200);
      context.takeScreenshot("tongcraft-library-local-ui");
      context.clickScreenButton("放置投影");
      context.waitForScreen(FormScreen.class);
      BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition().offset(6, 2, 0));
      context.runOnClient(mc -> {
        List<EditBox> boxes = mc.gui.screen().children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).toList();
        BlockPos playerPos = mc.player.blockPosition();
        check(boxes.get(1).getValue().equals(playerPos.getX() + " " + playerPos.getY() + " " + playerPos.getZ()),
            "Local placement did not default to the player position");
        boxes.get(1).setValue("invalid coordinates");
      });
      context.clickScreenButton("放置投影");
      context.runOnClient(mc -> check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty(),
          "Invalid coordinates created a projection"));
      context.runOnClient(mc -> {
        List<EditBox> boxes = mc.gui.screen().children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).toList();
        boxes.get(1).setValue(origin.getX() + " " + origin.getY() + " " + origin.getZ());
        boxes.get(2).setValue("90");
        boxes.get(3).setValue("LEFT_RIGHT");
      });
      context.takeScreenshot("tongcraft-library-placement-form");
      context.clickScreenButton("放置投影");
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        var placement = manager.getSelectedSchematicPlacement();
        check(placement != null && placement.getName().equals("素材库测试石块"), "Library projection was not selected");
        check(placement.getOrigin().equals(origin), "Local origin was not applied");
        check(placement.getRotation() == Rotation.CLOCKWISE_90, "Local rotation was not applied");
        check(placement.getMirror() == Mirror.LEFT_RIGHT, "Local mirror was not applied");
        check(placement.shouldBeSaved() && !placement.isLocked(), "Local projection is not saved and editable");
        check(mc.gui.screen() == null, "Placement did not return to the world");
        check(TongCraftClient.SYNC.bridge().selectedTransform().get("schematic").getAsString().endsWith(".litematic"),
            "Local projection cannot be published later");
        TongCraftClient.SYNC.disconnect();
        check(manager.getAllSchematicsPlacements().contains(placement), "Disconnect removed the local projection");
      });
      context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
          SchematicWorldHandler.getSchematicWorld().getBlockState(origin).is(Blocks.STONE), 200);
      context.takeScreenshot("tongcraft-library-local-projection");
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        manager.removeSchematicPlacement(manager.getSelectedSchematicPlacement());
        mc.gui.setScreen(new LibraryScreen(null, base));
      });
      corrupt.set(true);
      context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen screen && screen.children().stream()
          .anyMatch(child -> child instanceof Button button && button.getMessage().getString().equals("放置投影")), 200);
      context.clickScreenButton("放置投影");
      context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen screen && screen.children().stream()
          .anyMatch(child -> child instanceof Button button && button.active && button.getMessage().getString().equals("放置投影")), 200);
      context.runOnClient(mc -> check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty(),
          "Hash mismatch created a projection"));
      corrupt.set(false);
      hold.set(true);
      context.clickScreenButton("放置投影");
      context.waitFor(mc -> requested.getCount() == 0, 200);
      context.clickScreenButton("返回");
      release.countDown();
      context.waitTicks(20);
      context.runOnClient(mc -> {
        check(mc.gui.screen() == null, "Late download reopened the placement form");
        check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty(),
            "Late download created a projection after closing");
      });
      check(!uploaded.get(), "Local placement uploaded or shared the schematic");
    } finally {
      release.countDown();
      fixture.stop(0);
      executor.close();
    }
  }

  private static void projectionAction(ClientGameTestContext context, String action) {
    JsonObject row = context.computeOnClient(mc -> TongCraftClient.SYNC.projections().getFirst().deepCopy());
    context.clickScreenButton(row.get("name").getAsString());
    context.waitForScreen(PlacementScreen.class);
    context.clickScreenButton(action == null ? (row.has("id") ? "同步此投影的修改" : "发布此投影") : action);
  }

  private static List<Button> projectionButtons(net.minecraft.client.gui.screens.Screen screen) {
    return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
        .filter(button -> button.getMessage().getString().startsWith("密度测试")).toList();
  }

  private static void densityTest(ClientGameTestContext context) throws Exception {
    int originalScale = context.computeOnClient(mc -> mc.options.guiScale().get());
    try {
      context.runOnClient(mc -> {
        TongCraftClient.SYNC.disconnect();
        Path file = schematic(mc.gameDirectory.toPath().resolve("density-test"));
        for (int i = 1; i <= 10; i++)
          TongCraftClient.SYNC.bridge().placeLocal(TongCraftClient.SYNC.bridge().localTransform(file, "密度测试" + i));
        mc.options.guiScale().set(2);
        mc.resizeGui();
        mc.gui.setScreen(new SyncScreen(null));
        var screen = mc.gui.screen();
        check(screen.height >= 240 && screen.height < 300, "Density test did not use a small logical viewport");
        check(projectionButtons(screen).size() >= 3, "High GUI scale still shows only one projection per page");
        for (Button button : projectionButtons(screen))
          check(button.getY() + button.getHeight() + 9 < screen.height - 59,
              "Projection detail overlaps pagination");
        check(screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .noneMatch(button -> Set.of("选中", "发布选中投影", "取消同步（保留本地）", "取消同步并删除")
                .contains(button.getMessage().getString())), "Projection actions still occupy the main list");
      });
      context.takeScreenshot("tongcraft-compact-scaled-list");
      String first = context.computeOnClient(mc -> projectionButtons(mc.gui.screen()).getFirst().getMessage().getString());
      context.clickScreenButton("下一页");
      context.runOnClient(mc -> check(projectionButtons(mc.gui.screen()).stream()
          .noneMatch(button -> button.getMessage().getString().equals(first)), "Next page retained the first page"));
      context.clickScreenButton("上一页");
      context.runOnClient(mc -> {
        check(projectionButtons(mc.gui.screen()).getFirst().getMessage().getString().equals(first),
            "Previous page did not restore the first page");
        mc.gui.screen().resize(480, 257);
        check(projectionButtons(mc.gui.screen()).size() == 4, "Screenshot-sized viewport does not show four projections");
        mc.resizeGui();
      });
      context.clickScreenButton(first);
      context.waitForScreen(PlacementScreen.class);
      context.runOnClient(mc -> {
        var screen = mc.gui.screen();
        List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        for (Button a : buttons) {
          check(a.getY() + a.getHeight() <= screen.height, "Menu action extends beyond viewport");
          for (Button b : buttons) if (a != b)
            check(a.getX() + a.getWidth() <= b.getX() || b.getX() + b.getWidth() <= a.getX()
                || a.getY() + a.getHeight() <= b.getY() || b.getY() + b.getHeight() <= a.getY(),
                "Projection menu actions overlap");
        }
      });
      context.takeScreenshot("tongcraft-compact-scaled-menu");
      context.clickScreenButton("隐藏投影");
      context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(child -> child instanceof Button b
          && b.getMessage().getString().equals("显示投影")), 100);
      context.clickScreenButton("显示投影");
      context.clickScreenButton("发布此投影");
      context.waitForScreen(FormScreen.class);
      context.runOnClient(mc -> check(DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement()
          .getName().equals(first), "Publication menu selected a different projection"));
      context.clickScreenButton("返回");
      context.runOnClient(mc -> {
        var search = (EditBox) mc.gui.screen().children().stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
        search.setValue("密度测试10");
      });
      context.waitTicks(2);
      context.runOnClient(mc -> check(projectionButtons(mc.gui.screen()).size() == 1
          && projectionButtons(mc.gui.screen()).getFirst().getMessage().getString().equals("密度测试10"),
          "Filtering failed after paging"));
    } finally {
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        for (var placement : List.copyOf(manager.getAllSchematicsPlacements())) manager.removeSchematicPlacement(placement);
        mc.options.guiScale().set(originalScale);
        mc.resizeGui();
        mc.gui.setScreen(null);
      });
    }
  }
  private static void renderVisibilityTest(ClientGameTestContext context) throws Exception {
    context.waitTicks(20);
    BlockPos origin = context.computeOnClient(mc -> {
      Path file = schematic(mc.gameDirectory.toPath().resolve("render-visibility-test"), 32, 8, 1);
      JsonObject data = TongCraftClient.SYNC.bridge().localTransform(file, "渲染隐藏测试");
      BlockPos pos = mc.player.blockPosition().offset(-16, 0, 6);
      data.add("origin", Wire.GSON.toJsonTree(List.of(pos.getX(), pos.getY(), pos.getZ())));
      TongCraftClient.SYNC.bridge().placeLocal(data);
      var placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
      check(placement.isEnabled() && placement.isRenderingEnabled(), "Render fixture is disabled");
      check(placement.getSchematic().getSubRegionContainer("Main").get(16, 2, 0).is(Blocks.STONE),
          "Render fixture contains the wrong block");
      mc.player.setYRot(0);
      mc.player.setXRot(0);
      mc.gui.setScreen(null);
      return pos;
    });
    try {
      context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null
          && SchematicWorldHandler.getSchematicWorld().getBlockState(origin.offset(16, 2, 0)).is(Blocks.STONE), 400);
    } catch (AssertionError error) {
      String diagnostic = context.computeOnClient(mc -> "Placements=" + TongCraftClient.SYNC.projections()
          + " chunks=" + DataManager.getSchematicPlacementManager().getLastVisibleChunksCount()
          + " origin=" + origin + " block=" + SchematicWorldHandler.getSchematicWorld().getBlockState(origin.offset(16, 2, 0)));
      throw new AssertionError(diagnostic, error);
    }
    context.waitTicks(30);
    context.takeScreenshot("tongcraft-render-before-hide");
    context.runOnClient(mc -> {
      check(!fi.dy.masa.litematica.render.LitematicaRenderer.getInstance().getWorldRenderer()
          .getDebugInfoRenders().startsWith("C: 00/"), "Render test never drew the visible wall");
      mc.gui.setScreen(new SyncScreen(null));
    });
    projectionAction(context, "隐藏投影");
    context.clickScreenButton("返回");
    context.clickScreenButton("关闭");
    context.waitFor(mc -> !SchematicWorldHandler.getSchematicWorld().getBlockState(origin.offset(16, 2, 0)).is(Blocks.STONE), 200);
    context.waitTicks(30);
    context.takeScreenshot("tongcraft-render-after-hide");
    context.runOnClient(mc -> {
      check(fi.dy.masa.litematica.render.LitematicaRenderer.getInstance().getWorldRenderer()
          .getDebugInfoRenders().startsWith("C: 00/"), "Hidden wall still has drawn chunks with a stationary camera");
      var manager = DataManager.getSchematicPlacementManager();
      for (var placement : List.copyOf(manager.getAllSchematicsPlacements())) manager.removeSchematicPlacement(placement);
    });
  }
  private static void importTest(ClientGameTestContext context) throws Exception {
    Path file = context.computeOnClient(mc -> schematic(mc.gameDirectory.toPath().resolve("import-test")));
    byte[] bytes = Files.readAllBytes(file);
    String hash = Wire.hash(bytes), credential = "i".repeat(43);
    AtomicInteger posts = new AtomicInteger();
    AtomicInteger creations = new AtomicInteger(), updates = new AtomicInteger(), deletions = new AtomicInteger();
    AtomicBoolean rejectDelete = new AtomicBoolean();
    String memberId = "00000000000040008000000000000001";
    AtomicReference<byte[]> uploaded = new AtomicReference<>();
    AtomicReference<JsonObject> published = new AtomicReference<>();
    AtomicReference<JsonObject> preference = new AtomicReference<>(new JsonObject());
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    fixture.setExecutor(executor);
    fixture.createContext("/", exchange -> {
      try {
        if (!exchange.getRequestMethod().equals("GET")) posts.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/events")) {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        check(("Bearer " + credential).equals(exchange.getRequestHeaders().getFirst("Authorization")),
            "Publishing did not use the fixture credentials");
        byte[] response;
        if (path.equals("/schematics") && exchange.getRequestMethod().equals("POST")) {
          uploaded.set(exchange.getRequestBody().readAllBytes());
          response = Wire.GSON.toJson(Wire.object("hash", hash)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if ((path.equals("/placements") && exchange.getRequestMethod().equals("POST"))
            || (path.startsWith("/placements/") && exchange.getRequestMethod().equals("PUT"))) {
          JsonObject data = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
          boolean update = exchange.getRequestMethod().equals("PUT");
          if (update) updates.incrementAndGet(); else creations.incrementAndGet();
          data.addProperty("id", update ? published.get().get("id").getAsString() : UUID.randomUUID().toString());
          data.addProperty("revision", update ? published.get().get("revision").getAsInt() + 1 : 1);
          data.addProperty("owner", memberId);
          data.addProperty("ownerName", "Player0");
          for (var region : Wire.array(data, "placements")) {
            JsonObject r = region.getAsJsonObject(), nativePlacement = r.getAsJsonObject("placement");
            r.add("placement", Wire.object("name", r.get("name"), "pos", nativePlacement.get("pos"),
                "rotation", nativePlacement.get("rotation"), "mirror", nativePlacement.get("mirror"),
                "enabled", Wire.bool(nativePlacement, "enabled"),
                "rendering_enabled", Wire.bool(nativePlacement, "rendering_enabled"),
                "ignore_entities", Wire.bool(nativePlacement, "ignore_entities")));
          }
          published.set(data);
          response = Wire.GSON.toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (path.startsWith("/placements/") && exchange.getRequestMethod().equals("DELETE")) {
          JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
          if (rejectDelete.get() || body.get("revision").getAsInt() != published.get().get("revision").getAsInt()) {
            response = Wire.GSON.toJson(Wire.object("error", "Placement changed; refresh before editing"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            return;
          }
          deletions.incrementAndGet();
          published.set(null);
          response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (path.startsWith("/preferences/") && exchange.getRequestMethod().equals("PUT")) {
          preference.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
          response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (path.equals("/state")) {
          response = Wire.GSON.toJson(Wire.object("protocol", 1,
              "placements", published.get() == null ? List.of() : List.of(published.get()),
              "member", Wire.object("uuid", memberId, "role", "member"),
              "preferences", published.get() == null ? new JsonObject()
                  : Wire.object(published.get().get("id").getAsString(), preference.get())))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
          response = bytes;
        }
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      } finally { exchange.close(); }
    });
    fixture.start();
    try {
      context.runOnClient(mc -> {
        TongCraftClient.SYNC.disconnect();
        inject(TongCraftClient.SYNC, "config", new ClientConfig(
            "http://127.0.0.1:" + fixture.getAddress().getPort(), "fixture.invalid"));
        SyncScreen screen = new SyncScreen(null);
        mc.gui.setScreen(screen);
        screen.importSchematic(file);
        check(mc.gui.screen() instanceof FormScreen, "Offline import required synchronization login");
        List<EditBox> boxes = mc.gui.screen().children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).toList();
        check(boxes.getFirst().getValue().equals("Integration test"), "Import lost the schematic name");
      });
      context.takeScreenshot("tongcraft-import-local-form");
      context.clickScreenButton("放置投影");
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        var placement = manager.getSelectedSchematicPlacement();
        check(placement != null && placement.shouldBeSaved() && !placement.isLocked(),
            "Import did not create an editable, saved local projection");
        check(mc.gui.screen() == null, "Import did not return to the world");
        check(TongCraftClient.SYNC.projections().size() == 1 &&
            TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().startsWith("未同步"),
            "Imported local projection is missing from the management list");
        TongCraftClient.SYNC.disconnect();
        check(manager.getAllSchematicsPlacements().contains(placement), "Disconnect removed imported local projection");
        manager.removeSchematicPlacement(placement);
        inject(TongCraftClient.SYNC, "token", credential);
        inject(TongCraftClient.SYNC, "expires", System.currentTimeMillis() + 600000);
        SyncScreen screen = new SyncScreen(null);
        mc.gui.setScreen(screen);
        screen.importSchematic(file);
      });
      context.clickScreenButton("返回");
      context.runOnClient(mc -> {
        check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty(),
            "Cancelled import created a projection");
        ((SyncScreen) mc.gui.screen()).importSchematic(file);
      });
      context.clickScreenButton("放置投影");
      context.waitTicks(20);
      check(posts.get() == 0 && published.get() == null, "Import uploaded or published before explicit confirmation");
      context.runOnClient(mc -> mc.gui.setScreen(new SyncScreen(null)));
      context.takeScreenshot("tongcraft-management-local-list");
      BlockPos adjusted = context.computeOnClient(mc -> mc.player.blockPosition().offset(9, 2, 0));
      JsonObject expected = context.computeOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        var placement = manager.getSelectedSchematicPlacement();
        placement.setOrigin(adjusted, null);
        placement.setRotation(Rotation.CLOCKWISE_180, null);
        placement.setMirror(Mirror.LEFT_RIGHT, null);
        manager.setSelectedSchematicPlacement(null);
        manager.setSelectedSchematicPlacement(placement);
        JsonObject data = TongCraftClient.SYNC.bridge().selectedTransform();
        mc.gui.setScreen(new SyncScreen(null));
        return data;
      });
      projectionAction(context, null);
      context.waitForScreen(FormScreen.class);
      context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream()
          .filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("调整后发布"));
      check(posts.get() == 0, "Opening the publication form uploaded the projection");
      context.clickScreenButton("发布");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && published.get() != null &&
          Wire.array(TongCraftClient.SYNC.state(), "placements").size() == 1, 400);
      check(posts.get() == 2 && Arrays.equals(bytes, uploaded.get()), "Explicit publishing did not upload once");
      JsonObject data = published.get();
      check(data.get("name").getAsString().equals("调整后发布"), "Public name was not applied");
      for (String key : List.of("origin", "rotation", "mirror", "dimension"))
        check(data.get(key).equals(expected.get(key)), "Publishing lost final placement setting: " + key);
      JsonObject sentRegion = data.getAsJsonArray("placements").get(0).getAsJsonObject().getAsJsonObject("placement");
      JsonObject expectedRegion = expected.getAsJsonArray("placements").get(0).getAsJsonObject().getAsJsonObject("placement");
      for (String key : List.of("pos", "rotation", "mirror", "enabled", "rendering_enabled"))
        check(sentRegion.get(key).equals(expectedRegion.get(key)), "Publishing lost subregion setting: " + key);
      context.runOnClient(mc -> {
        check(TongCraftClient.SYNC.projections().size() == 1, "Published local projection appears twice");
        check(TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().equals("已同步"),
            "Normalized server settings incorrectly show local modifications");
        check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size() == 1,
            "Publishing created a duplicate rendered placement");
        String localId = TongCraftClient.SYNC.projections().getFirst().get("localId").getAsString();
        ProjectionLinks restored = new ProjectionLinks(TongCraftClient.SYNC.bridge().cacheDir().resolve("projection-links.json"));
        check(restored.get(TongCraftClient.SYNC.config(), localId).get("id").equals(data.get("id")),
            "Publication association was not persisted");
        check(restored.get(new ClientConfig("http://127.0.0.1:1", "other.invalid"), localId).isEmpty(),
            "Publication association leaked into a different sync service");
      });
      context.takeScreenshot("tongcraft-management-synced-list");
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        var local = manager.getSelectedSchematicPlacement();
        JsonObject remoteJson = local.toJson();
        remoteJson.addProperty("hash_code", data.get("id").getAsString());
        var orphan = SchematicPlacement.fromJson(remoteJson);
        orphan.setShouldBeSaved(false);
        manager.addSchematicPlacement(orphan, false);
        check(manager.getAllSchematicsPlacements().size() == 2, "Failed to reproduce an untracked shared copy");
        TongCraftClient.SYNC.bridge().reconcile(TongCraftClient.SYNC.state(), TongCraftClient.SYNC.bridge().cacheDir());
        check(manager.getAllSchematicsPlacements().size() == 1
            && manager.getAllSchematicsPlacements().contains(local), "Reconciliation kept overlapping shared copies");
        // Independent saved placements at the same position remain separate user placements.
        JsonObject independentJson = local.toJson();
        independentJson.addProperty("hash_code", UUID.randomUUID().toString());
        var independent = SchematicPlacement.fromJson(independentJson);
        manager.addSchematicPlacement(independent, false);
        TongCraftClient.SYNC.bridge().reconcile(TongCraftClient.SYNC.state(), TongCraftClient.SYNC.bridge().cacheDir());
        check(manager.getAllSchematicsPlacements().contains(independent), "Duplicate cleanup removed an independent local placement");
        manager.removeSchematicPlacement(independent);
        manager.setSelectedSchematicPlacement(local);
      });
      context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null
          && SchematicWorldHandler.getSchematicWorld().getBlockState(adjusted).is(Blocks.STONE), 200);
      projectionAction(context, "隐藏投影");
      context.waitFor(mc -> TongCraftClient.SYNC.projections().getFirst().get("hidden").getAsBoolean()
          && !SchematicWorldHandler.getSchematicWorld().getBlockState(adjusted).is(Blocks.STONE), 200);
      context.runOnClient(mc -> {
        check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size() == 1,
            "Hiding left a second overlapping native placement");
        check(TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().equals("已同步"),
            "Personal visibility changed public placement settings");
        TongCraftClient.SYNC.refresh();
      });
      context.waitTicks(20);
      context.runOnClient(mc -> check(!SchematicWorldHandler.getSchematicWorld().getBlockState(adjusted).is(Blocks.STONE),
          "Refreshing re-created a hidden shared projection"));
      context.takeScreenshot("tongcraft-synced-hidden-world");
      context.clickScreenButton("显示投影");
      context.waitFor(mc -> !TongCraftClient.SYNC.projections().getFirst().get("hidden").getAsBoolean()
          && SchematicWorldHandler.getSchematicWorld().getBlockState(adjusted).is(Blocks.STONE), 200);
      context.clickScreenButton("返回");
      int menuScale = context.computeOnClient(mc -> mc.options.guiScale().get());
      context.runOnClient(mc -> {
        mc.options.guiScale().set(2);
        mc.resizeGui();
      });
      context.clickScreenButton("调整后发布");
      context.waitForScreen(PlacementScreen.class);
      context.runOnClient(mc -> {
        var screen = mc.gui.screen();
        List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        check(buttons.size() == 10, "Shared projection menu is missing an action");
        for (Button a : buttons) {
          check(a.getY() + a.getHeight() < screen.height - 48 || a.getMessage().getString().equals("返回"),
              "Shared menu action overlaps footer status");
          for (Button b : buttons) if (a != b)
            check(a.getX() + a.getWidth() <= b.getX() || b.getX() + b.getWidth() <= a.getX()
                || a.getY() + a.getHeight() <= b.getY() || b.getY() + b.getHeight() <= a.getY(),
                "Shared projection menu actions overlap");
        }
      });
      context.takeScreenshot("tongcraft-compact-scaled-shared-menu");
      context.clickScreenButton("返回");
      context.runOnClient(mc -> {
        mc.options.guiScale().set(menuScale);
        mc.resizeGui();
      });
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        JsonObject saved = manager.toJson();
        TongCraftClient.SYNC.bridge().clear();
        manager.loadFromJson(saved);
        TongCraftClient.SYNC.bridge().reconcile(TongCraftClient.SYNC.state(), TongCraftClient.SYNC.bridge().cacheDir());
        check(TongCraftClient.SYNC.projections().size() == 1, "Reloaded placement lost the shared association");
        manager.getSelectedSchematicPlacement().setOrigin(adjusted.offset(2, 0, 0), null);
        check(TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().contains("有本地修改"),
            "Local changes are not indicated in synchronization status");
        mc.gui.setScreen(new SyncScreen(null));
      });
      projectionAction(context, null);
      context.clickScreenButton("发布");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && updates.get() == 1 &&
          TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().equals("已同步"), 400);
      check(creations.get() == 1, "Republishing created a second shared projection");
      BlockPos revised = adjusted.offset(4, 0, 0);
      published.updateAndGet(current -> {
        JsonObject updated = current.deepCopy();
        updated.add("origin", Wire.GSON.toJsonTree(List.of(revised.getX(), revised.getY(), revised.getZ())));
        updated.addProperty("revision", current.get("revision").getAsInt() + 1);
        return updated;
      });
      context.runOnClient(mc -> TongCraftClient.SYNC.refresh());
      context.waitFor(mc -> DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement().getOrigin().equals(revised)
          && TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().equals("已同步"), 400);
      rejectDelete.set(true);
      projectionAction(context, "取消同步并删除");
      context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream()
          .filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("DELETE"));
      context.clickScreenButton("确认删除");
      context.waitFor(mc -> TongCraftClient.SYNC.status().contains("投影已被更新"), 200);
      context.runOnClient(mc -> {
        check(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size() == 1
            && TongCraftClient.SYNC.projections().getFirst().has("id"), "Failed revocation removed a local or shared placement");
      });
      check(deletions.get() == 0, "Rejected deletion changed server state");
      rejectDelete.set(false);
      context.clickScreenButton("返回");
      projectionAction(context, "取消同步（保留本地）");
      context.takeScreenshot("tongcraft-management-revoke-confirm");
      context.clickScreenButton("确认取消同步");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && published.get() == null &&
          TongCraftClient.SYNC.projections().size() == 1 && !TongCraftClient.SYNC.projections().getFirst().has("id"), 400);
      context.runOnClient(mc -> {
        var manager = DataManager.getSchematicPlacementManager();
        var local = manager.getSelectedSchematicPlacement();
        check(local.shouldBeSaved() && !local.isLocked() && local.getOrigin().equals(revised),
            "Revocation did not retain the final editable local transform");
        check(TongCraftClient.SYNC.projections().getFirst().get("syncStatus").getAsString().startsWith("未同步"),
            "Revocation did not update synchronization status");
      });
      projectionAction(context, null);
      context.clickScreenButton("发布");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && creations.get() == 2 &&
          TongCraftClient.SYNC.projections().getFirst().has("id"), 400);
      projectionAction(context, "取消同步并删除");
      context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream()
          .filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("DELETE"));
      context.clickScreenButton("确认删除");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && published.get() == null &&
          TongCraftClient.SYNC.projections().isEmpty(), 400);
      check(deletions.get() == 2 && Files.exists(file), "Revocation deleted the blueprint file or missed server removal");
      JsonObject legacy = data.deepCopy();
      legacy.addProperty("id", UUID.randomUUID().toString());
      published.set(legacy);
      context.runOnClient(mc -> {
        JsonObject local = TongCraftClient.SYNC.bridge().localTransform(file);
        for (String key : List.of("origin", "rotation", "mirror", "placements", "ignore_entities"))
          local.add(key, legacy.get(key).deepCopy());
        TongCraftClient.SYNC.bridge().placeLocal(local);
        TongCraftClient.SYNC.refresh();
      });
      context.waitFor(mc -> TongCraftClient.SYNC.projections().size() == 1
          && TongCraftClient.SYNC.projections().getFirst().has("id"), 400);
      context.runOnClient(mc -> {
        check(TongCraftClient.SYNC.projections().getFirst().has("localId"), "Legacy local projection was not associated");
        var manager = DataManager.getSchematicPlacementManager();
        manager.removeSchematicPlacement(manager.getSelectedSchematicPlacement());
        TongCraftClient.SYNC.bridge().clear();
        TongCraftClient.SYNC.bridge().reconcile(TongCraftClient.SYNC.state(), TongCraftClient.SYNC.bridge().cacheDir());
        check(!TongCraftClient.SYNC.projections().getFirst().has("localId"), "Remote-only projection is marked local");
        manager.setSelectedSchematicPlacement(null);
        mc.gui.setScreen(new SyncScreen(null));
      });
      projectionAction(context, "选中并返回游戏");
      context.runOnClient(mc -> mc.gui.setScreen(new SyncScreen(null)));
      projectionAction(context, "取消同步（保留本地）");
      context.clickScreenButton("确认取消同步");
      context.waitFor(mc -> mc.gui.screen() instanceof SyncScreen && published.get() == null
          && TongCraftClient.SYNC.projections().size() == 1 && !TongCraftClient.SYNC.projections().getFirst().has("id"), 400);
      context.runOnClient(mc -> check(DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement().shouldBeSaved(),
          "Revoking a remote-only projection did not create a saved local copy"));
      projectionAction(context, "删除本地投影");
      context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream()
          .filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("DELETE"));
      context.clickScreenButton("确认删除");
      check(deletions.get() == 3 && Files.exists(file), "Remote-only revocation failed or deleted the blueprint");
      context.runOnClient(mc -> {
        JsonObject foreign = Wire.object("id", UUID.randomUUID().toString(), "owner", "other-member", "revision", 1);
        check(!TongCraftClient.SYNC.canRemove(foreign), "Member can revoke another member's projection");
        TongCraftClient.SYNC.bridge().placeLocal(TongCraftClient.SYNC.bridge().localTransform(file));
        mc.gui.setScreen(new SyncScreen(null));
      });
      projectionAction(context, "删除本地投影");
      context.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream()
          .filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("DELETE"));
      context.clickScreenButton("确认删除");
      context.runOnClient(mc -> {
        check(TongCraftClient.SYNC.projections().isEmpty(), "Unpublished local projection was not deleted");
        mc.gui.setScreen(null);
      });
    } finally {
      context.runOnClient(mc -> TongCraftClient.SYNC.disconnect());
      fixture.stop(0);
      executor.close();
    }
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    if ("1".equals(System.getenv("TONGCRAFT_RENDER_ONLY"))) {
      try (var renderWorld = context.worldBuilder().create()) {
        renderVisibilityTest(context);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return;
    }
    context.setScreen(() -> new SyncScreen(null));
    context.waitTicks(3);
    context.takeScreenshot("tongcraft-empty-ui");
    context.clickScreenButton("连接设置");
    context.waitForScreen(FormScreen.class);
    context.takeScreenshot("tongcraft-settings-ui");
    context.clickScreenButton("返回");
    context.waitForScreen(SyncScreen.class);
    try (var world = context.worldBuilder().create()) {
      context.runOnClient(
          mc -> {
            Path dir = mc.gameDirectory.toPath().resolve("bridge-test");
            Path file = schematic(dir);
            String hash = Wire.hash(Files.readAllBytes(file));
            Files.copy(file, dir.resolve(hash + ".litematic"), StandardCopyOption.REPLACE_EXISTING);
            String id = UUID.randomUUID().toString();
            BlockPos origin = mc.player.blockPosition().offset(3, 2, 0);
            JsonObject p =
                Wire.object(
                    "id",
                    id,
                    "name",
                    "共享石块",
                    "hash",
                    hash,
                    "dimension",
                    mc.level.dimension().identifier().toString(),
                    "origin",
                    List.of(origin.getX(), origin.getY(), origin.getZ()),
                    "rotation",
                    "CLOCKWISE_90",
                    "mirror",
                    "NONE",
                    "placements",
                    List.of(
                        Wire.object(
                            "name",
                            "Main",
                            "placement",
                            Wire.object(
                                "name",
                                "Main",
                                "pos",
                                List.of(0, 0, 0),
                                "rotation",
                                "NONE",
                                "mirror",
                                "NONE",
                                "enabled",
                                true,
                                "rendering_enabled",
                                true))));
            JsonObject state =
                Wire.object(
                    "placements",
                    List.of(p),
                    "preferences",
                    new JsonObject(),
                    "member",
                    Wire.object("uuid", "00000000000040008000000000000001", "role", "admin"));
            LitematicaBridge bridge = TongCraftClient.SYNC.bridge();
            bridge.reconcile(state, dir);
            SchematicPlacement placement =
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                    .filter(v -> v.getHashId().toString().equals(id))
                    .findFirst()
                    .orElseThrow();
            check(placement.getOrigin().equals(origin), "Schematic origin was not applied");
            check(
                !placement.shouldBeSaved(), "Shared placement leaked into local saved placements");
            check(
                placement.getSchematic().getAreaSizes().get("Main").equals(new BlockPos(1, 1, 1)),
                "Schematic is not loaded for printer access");
            check(
                placement
                    .getSchematic()
                    .getSubRegionContainer("Main")
                    .get(0, 0, 0)
                    .is(Blocks.STONE),
                "Printer would not read the expected block");
            JsonObject prefs = state.getAsJsonObject("preferences");
            prefs.add(id, Wire.object("hidden", false, "alias", "仅我可见的名字"));
            bridge.reconcile(state, dir);
            bridge.select(id);
            check(
                DataManager.getSchematicPlacementManager()
                    .getSelectedSchematicPlacement()
                    .getName()
                    .equals("仅我可见的名字"),
                "Private alias not applied");
            var manager = DataManager.getSchematicPlacementManager();
            var orphan = SchematicPlacement.fromJson(manager.getSelectedSchematicPlacement().toJson());
            orphan.setShouldBeSaved(false);
            manager.addSchematicPlacement(orphan, false);
            bridge.reconcile(state, dir);
            check(manager.getAllSchematicsPlacements().size() == 1,
                "Unchanged remote projection kept an untracked duplicate");
            orphan = SchematicPlacement.fromJson(manager.getSelectedSchematicPlacement().toJson());
            orphan.setShouldBeSaved(false);
            manager.addSchematicPlacement(orphan, false);
            prefs.add(id, Wire.object("hidden", true, "alias", "别名"));
            bridge.reconcile(state, dir);
            check(
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                    .noneMatch(v -> v.getHashId().toString().equals(id)),
                "Hidden placement remains active");
            prefs.add(id, Wire.object("hidden", false, "alias", ""));
            bridge.reconcile(state, dir);
            bridge.select(id);
            // Inject only view state, not credentials. This is a test source set excluded from the
            // released jar.
            var stateField = SyncClient.class.getDeclaredField("state");
            stateField.setAccessible(true);
            stateField.set(TongCraftClient.SYNC, state);
            mc.gui.setScreen(new SyncScreen(null));
          });
      context.waitTicks(5);
      context.takeScreenshot("tongcraft-shared-ui");
      context.clickScreenButton("共享石块");
      context.waitForScreen(PlacementScreen.class);
      context.takeScreenshot("tongcraft-placement-ui");
      context.clickScreenButton("返回");
      context.clickScreenButton("成员与邀请");
      context.waitForScreen(AdminScreen.class);
      context.takeScreenshot("tongcraft-admin-ui");
      BlockPos printPos = context.computeOnClient(mc -> mc.player.blockPosition().offset(2, 0, 0));
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
                player.inventoryMenu.broadcastChanges();
                player
                    .level()
                    .setBlockAndUpdate(printPos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                player.level().setBlockAndUpdate(printPos, Blocks.AIR.defaultBlockState());
              });
      world.getConnection().waitForClientboundPackets();
      context.runOnClient(
          mc -> {
            JsonObject state = TongCraftClient.SYNC.state(),
                p = state.getAsJsonArray("placements").get(0).getAsJsonObject();
            p.add(
                "origin",
                Wire.GSON.toJsonTree(List.of(printPos.getX(), printPos.getY(), printPos.getZ())));
            TongCraftClient.SYNC
                .bridge()
                .reconcile(state, mc.gameDirectory.toPath().resolve("bridge-test"));
            mc.gui.setScreen(null);
          });
      context.waitFor(
          mc ->
              SchematicWorldHandler.getSchematicWorld() != null
                  && SchematicWorldHandler.getSchematicWorld()
                      .getBlockState(printPos)
                      .is(Blocks.STONE),
          200);
      context.runOnClient(
          mc -> {
            check(
                !mc.level.getBlockState(printPos).is(Blocks.STONE),
                "Sync changed a real world block before printing");
            Configs.PRINT_DEBUG.setBooleanValue(true);
            Configs.PRINT_MODE.setBooleanValue(true);
          });
      try {
        context.waitFor(mc -> mc.level.getBlockState(printPos).is(Blocks.STONE), 200);
        context.waitTicks(20);
        world
            .getServer()
            .runOnServer(
                server ->
                    check(
                        server
                            .getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .level()
                            .getBlockState(printPos)
                            .is(Blocks.STONE),
                        "Printer placement was not accepted by the server"));
      } finally {
        context.runOnClient(
            mc -> {
              Configs.PRINT_MODE.setBooleanValue(false);
              Configs.PRINT_DEBUG.setBooleanValue(false);
            });
      }
      context.takeScreenshot("tongcraft-printer-smoke");
      networkTest(context);
      libraryTest(context);
      densityTest(context);

      importTest(context);

      context.runOnClient(
          mc -> {
            TongCraftClient.SYNC.bridge().clear();
            TongCraftClient.SYNC.disconnect();
          });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }
}
