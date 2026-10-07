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
import me.aleksilassila.litematica.printer.config.Configs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

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
    meta.put("EnclosingSize", vector(1, 1, 1));
    root.put("Metadata", meta);
    region.put("Position", vector(0, 0, 0));
    region.put("Size", vector(1, 1, 1));
    stone.putString("Name", "minecraft:stone");
    ListTag palette = new ListTag();
    CompoundTag air = new CompoundTag();
    air.putString("Name", "minecraft:air");
    palette.add(air);
    palette.add(stone);
    region.put("BlockStatePalette", palette);
    region.putLongArray("BlockStates", new long[] {1});
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

  @Override
  public void runTest(ClientGameTestContext context) {
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
