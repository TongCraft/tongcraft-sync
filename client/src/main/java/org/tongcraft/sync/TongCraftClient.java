package org.tongcraft.sync;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class TongCraftClient implements ClientModInitializer {
  public static SyncClient SYNC;

  @Override
  public void onInitializeClient() {
    SYNC = new SyncClient();
    KeyMapping key =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.tongcraft_sync.open",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_H,
                KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("tongcraft_sync", "main"))));
    ClientTickEvents.END_CLIENT_TICK.register(
        mc -> {
          SYNC.tick();
          while (key.consumeClick()) mc.gui.setScreen(new SyncScreen(mc.gui.screen()));
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> SYNC.shutdown());
  }
}
