import { resolve } from "node:path";
import { createService } from "./server.js";

if (!process.env.TONGCRAFT_ADMIN_UUID)
  throw new Error(
    "Set TONGCRAFT_ADMIN_UUID to the server owner's verified Minecraft UUID",
  );
const service = await createService({
  dataDir: resolve(process.env.TONGCRAFT_DATA_DIR || "./data"),
  adminUuid: process.env.TONGCRAFT_ADMIN_UUID,
});
const port = Number(process.env.PORT || 8787),
  host = process.env.HOST || "127.0.0.1";
service.server.listen(port, host, () =>
  console.log(
    `TongCraft sync listening on ${host}:${port}; publish through an HTTPS reverse proxy`,
  ),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, async () => {
    await service.close();
    process.exit(0);
  });
